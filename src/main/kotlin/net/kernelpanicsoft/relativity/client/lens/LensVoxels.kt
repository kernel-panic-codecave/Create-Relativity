package net.kernelpanicsoft.relativity.client.lens

import com.mojang.blaze3d.platform.GlStateManager
import net.kernelpanicsoft.relativity.spacetime.BlackHole
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL31
import org.lwjgl.system.MemoryUtil
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The world round the nearest hole, for bent light paths to trace where the camera's frame can't show what they reach
 * (lens_scene.glsl's sceneGeometryHit):
 *
 * - **Fine grid** ([SIZE]³ one-block cells, R32UI): each cell's quads, as (start << 8) | count into the quad buffer.
 * - **Quad buffer** (a texture buffer, [QUAD_TEXELS] RGBA32F texels per quad): the real vertex data of what each block
 *   draws ([BlockQuads]), relative to the cell. Every copy of a block state shares one run; cells a block entity draws
 *   into get their own run (a slab), the cell's block plus the entity's quads, re-recorded in turn.
 * - **Coarse grid** ([SIZE]³ cells of [coarseCell] blocks, RGBA8): a colour and occupancy per cell, for what's beyond
 *   the fine grid. Its cells grow with the hole (64 r_s across at least).
 * - **Texture slots**: the GL textures quads sample, up to [TEXTURE_SLOTS]; quads past that use their flat colour.
 *
 * Both grids fill z slices for up to [BUDGET_MILLIS] a frame until complete, then re-read only slices whose sections
 * changed (block updates, light and chunk arrivals, via LevelRendererMixin), at most [REFRESH_SLICES] fine and
 * [COARSE_REFRESH_SLICES] coarse a frame; they start over when the hole moves to another block.
 */
object LensVoxels {
	const val SIZE = 128
	const val TEXTURE_SLOTS = 8
	const val QUAD_TEXELS = 6
	private const val BUDGET_MILLIS = 3.0
	private const val REFRESH_SLICES = 4
	private const val COARSE_REFRESH_SLICES = 1
	private const val ENTITIES_PER_FRAME = 4
	private const val STATIC_QUADS = 1 shl 17
	private const val SLAB_QUADS = 255
	private const val SLABS = 512
	private const val QUADS = STATIC_QUADS + SLAB_QUADS * SLABS

	/** Fine grid (GL_TEXTURE_3D, R32UI), coarse grid (GL_TEXTURE_3D, RGBA8), quad buffer texture (GL_TEXTURE_BUFFER). */
	var grid = 0
		private set
	var coarse = 0
		private set
	var quadTexture = 0
		private set
	private var quadBuffer = 0

	/** The fine grid's minimum corner (world block position), or null with no hole. */
	var origin: BlockPos? = null
		private set

	/** The coarse grid's minimum corner (world block position) and its cell size (blocks). */
	var coarseOrigin: BlockPos? = null
		private set
	var coarseCell = 2
		private set

	/** GL ids of the textures in slots 0 until [TEXTURE_SLOTS] (0: unused). */
	val textureSlots = IntArray(TEXTURE_SLOTS)

	/** CPU time of the latest [update] (ms). */
	@Volatile
	var updateMillis = 0.0
		private set

	private var filled = 0
	private var coarseFilled = 0
	private val dirty = java.util.BitSet(SIZE)
	private val coarseDirty = java.util.BitSet(SIZE)
	private val gridSlice = MemoryUtil.memAllocInt(SIZE * SIZE)
	private val coarseSlice = MemoryUtil.memAlloc(SIZE * SIZE * 4)

	private val quadData = FloatArray(QUADS * QUAD_TEXELS * 4)
	private var staticQuads = 0
	private var dirtyFrom = Int.MAX_VALUE
	private var dirtyTo = -1

	/** Per block state: its run ((start << 8) | count, 0 for nothing) and its colour from afar (ARGB, alpha 0 for none). */
	private val stateRuns = HashMap<BlockState, Int>()
	private val stateColors = HashMap<BlockState, Int>()

	/** Block entities in the grid, re-recorded in turn, and the cells each draws into (world cell → slab). */
	private val entities = LinkedHashSet<BlockPos>()
	private var entityCursor: Iterator<BlockPos>? = null
	private val entityCells = HashMap<BlockPos, MutableList<Long>>()
	private val cellSlabs = HashMap<Long, Int>()
	private val slabCounts = IntArray(SLABS)
	private val freeSlabs = ArrayDeque((0 until SLABS).toList())

	/** True once both grids are in. */
	val ready: Boolean get() = origin != null && filled >= SIZE && coarseFilled >= SIZE

	/** How many quads the fine grid holds for the block at [pos] (its own, or its block entity's slab); -1 outside it. */
	fun quadsAt(level: ClientLevel, pos: BlockPos): Int {
		val corner = origin ?: return -1
		if (pos.x - corner.x !in 0 until SIZE || pos.y - corner.y !in 0 until SIZE || pos.z - corner.z !in 0 until SIZE) return -1
		cellSlabs[pos.asLong()]?.let { return slabCounts[it] }
		return (stateRuns[level.getBlockState(pos)] ?: 0) and 255
	}

	/** A section changed (block update or chunk arrival): the slices it covers are re-read. */
	@JvmStatic
	fun sectionChanged(sectionX: Int, sectionY: Int, sectionZ: Int) {
		val corner = origin ?: return
		val z0 = sectionZ * 16 - corner.z
		if (sectionX * 16 - corner.x in -15 until SIZE && sectionY * 16 - corner.y in -15 until SIZE && z0 in -15 until SIZE) {
			dirty.set(maxOf(z0, 0), minOf(z0 + 16, SIZE))
		}
		val coarseCorner = coarseOrigin ?: return
		val c0 = Math.floorDiv(sectionZ * 16 - coarseCorner.z, coarseCell)
		val c1 = Math.floorDiv(sectionZ * 16 + 15 - coarseCorner.z, coarseCell)
		if (c1 >= 0 && c0 < SIZE) coarseDirty.set(maxOf(c0, 0), minOf(c1 + 1, SIZE))
	}

	/** Advances both grids round [hole] (nearest first) by this frame's work. */
	fun update(level: ClientLevel, hole: BlackHole?, partialTick: Float) {
		val started = System.nanoTime()
		try {
			if (hole == null) {
				origin = null
				return
			}
			ensureTextures()
			val holePos = BlockPos.containing(hole.x, hole.y, hole.z)
			val corner = holePos.offset(-SIZE / 2, -SIZE / 2, -SIZE / 2)
			val cell = ceil(maxOf(256.0, 64.0 * hole.schwarzschildRadius) / SIZE).toInt().coerceIn(2, 16)
			val coarseCorner = holePos.offset(-SIZE * cell / 2, -SIZE * cell / 2, -SIZE * cell / 2)
			if (corner != origin) {
				for (pos in entityCells.keys.toList()) dropEntity(pos)
				origin = corner
				filled = 0
				dirty.clear()
				entities.clear()
				entityCursor = null
			}
			if (coarseCorner != coarseOrigin || cell != coarseCell) {
				coarseOrigin = coarseCorner
				coarseCell = cell
				coarseFilled = 0
				coarseDirty.clear()
			}
			// While filling, slices until this frame's budget is spent (at least one per grid); then dirty ones only.
			val deadline = started + (BUDGET_MILLIS * 1e6).toLong()
			var count = 0
			while (true) {
				val filling = filled < SIZE
				val z = if (filling) filled else dirty.nextSetBit(0).takeIf { it >= 0 } ?: break
				dirty.clear(z)
				readSlice(level, corner, z, holePos)
				if (filling) filled += 1
				count += 1
				if (if (filling) System.nanoTime() >= deadline else count >= REFRESH_SLICES) break
			}
			count = 0
			while (true) {
				val filling = coarseFilled < SIZE
				val z = if (filling) coarseFilled else coarseDirty.nextSetBit(0).takeIf { it >= 0 } ?: break
				coarseDirty.clear(z)
				readCoarseSlice(level, coarseCorner, cell, z, holePos)
				if (filling) coarseFilled += 1
				count += 1
				if (if (filling) System.nanoTime() >= deadline else count >= COARSE_REFRESH_SLICES) break
			}
			repeat(minOf(ENTITIES_PER_FRAME, entities.size)) { recordNextEntity(level, partialTick, holePos) }
			uploadQuads()
		} finally {
			updateMillis = (System.nanoTime() - started) / 1e6
		}
	}

	/** Reads fine slice [z]: each cell's run (a block entity's slab where it draws), from the chunk sections. */
	private fun readSlice(level: ClientLevel, corner: BlockPos, z: Int, holePos: BlockPos) {
		val worldZ = corner.z + z
		for (i in 0 until SIZE * SIZE) gridSlice.put(i, 0)
		forEachState(level, corner.x, corner.y, worldZ) { x, y, state, chunk ->
			if (state.hasBlockEntity()) {
				val worldPos = BlockPos(corner.x + x, corner.y + y, worldZ)
				if (chunk.getBlockEntity(worldPos) != null) entities += worldPos
			}
			gridSlice.put(y * SIZE + x, run(level, state, holePos))
		}
		for ((key, slab) in cellSlabs) {
			val x = BlockPos.getX(key) - corner.x
			val y = BlockPos.getY(key) - corner.y
			if (BlockPos.getZ(key) == worldZ && x in 0 until SIZE && y in 0 until SIZE) gridSlice.put(y * SIZE + x, slabRun(slab))
		}
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, grid)
		unpackDefaults()
		GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, z, SIZE, SIZE, 1, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, gridSlice)
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
	}

	/**
	 * Reads coarse slice [z]: per cell, the colour of what fills two points on its vertical centre line (a quarter and
	 * three quarters up; none: empty). Each column's chunk is looked up once.
	 */
	private fun readCoarseSlice(level: ClientLevel, corner: BlockPos, cell: Int, z: Int, holePos: BlockPos) {
		val chunks = level.chunkSource
		for (i in 0 until SIZE * SIZE * 4) coarseSlice.put(i, 0)
		val pos = BlockPos.MutableBlockPos()
		val pz = corner.z + z * cell + cell / 2
		for (x in 0 until SIZE) {
			val px = corner.x + x * cell + cell / 2
			val chunk = chunks.getChunk(px shr 4, pz shr 4, false) ?: continue
			for (y in 0 until SIZE) {
				var r = 0
				var g = 0
				var b = 0
				var n = 0
				for (k in 0..1) {
					val state = chunk.getBlockState(pos.set(px, corner.y + y * cell + (if (k == 0) cell / 4 else 3 * cell / 4), pz))
					if (state.isAir) continue
					run(level, state, holePos)
					val color = stateColors[state] ?: 0
					if (color ushr 24 == 0) continue
					r += (color shr 16) and 0xFF
					g += (color shr 8) and 0xFF
					b += color and 0xFF
					n += 1
				}
				if (n == 0) continue
				val at = (y * SIZE + x) * 4
				coarseSlice.put(at, (r / n).toByte())
				coarseSlice.put(at + 1, (g / n).toByte())
				coarseSlice.put(at + 2, (b / n).toByte())
				coarseSlice.put(at + 3, 255.toByte())
			}
		}
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, coarse)
		unpackDefaults()
		GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, z, SIZE, SIZE, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, coarseSlice)
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
	}

	/** Calls [each] for every non-air block of the [SIZE] × [SIZE] slice at z = [worldZ] from ([x0], [y0]), by section. */
	private inline fun forEachState(level: ClientLevel, x0: Int, y0: Int, worldZ: Int, each: (Int, Int, BlockState, LevelChunk) -> Unit) {
		var chunkX = x0 shr 4
		while (chunkX <= (x0 + SIZE - 1) shr 4) {
			val chunk = level.chunkSource.getChunk(chunkX, worldZ shr 4, false)
			if (chunk != null) {
				val xa = maxOf(chunkX * 16, x0)
				val xb = minOf(chunkX * 16 + 15, x0 + SIZE - 1)
				var y = 0
				while (y < SIZE) {
					val worldY = y0 + y
					val sectionIndex = chunk.getSectionIndex(worldY)
					val top = minOf(SIZE, y + 16 - (worldY and 15))
					if (sectionIndex in 0 until chunk.sectionsCount) {
						val section = chunk.getSection(sectionIndex)
						if (!section.hasOnlyAir()) {
							for (yy in y until top) for (wx in xa..xb) {
								val state = section.getBlockState(wx and 15, (y0 + yy) and 15, worldZ and 15)
								if (!state.isAir) each(wx - x0, yy, state, chunk)
							}
						}
					}
					y = top
				}
			}
			chunkX += 1
		}
	}

	/** [state]'s run of quads, recorded on first sight. */
	private fun run(level: ClientLevel, state: BlockState, holePos: BlockPos): Int {
		stateRuns[state]?.let { return it }
		val quads = BlockQuads.stateQuads(state, level, holePos).take(SLAB_QUADS)
		stateColors[state] = averageColor(quads)
		if (quads.isEmpty() || staticQuads + quads.size > STATIC_QUADS) {
			stateRuns[state] = 0
			return 0
		}
		val start = staticQuads
		for ((i, quad) in quads.withIndex()) writeQuad(start + i, quad)
		staticQuads += quads.size
		val run = (start shl 8) or quads.size
		stateRuns[state] = run
		return run
	}

	private fun slabRun(slab: Int) = ((STATIC_QUADS + slab * SLAB_QUADS) shl 8) or slabCounts[slab]

	/** Re-records the next block entity in turn: its quads go into the cells they reach, each with that cell's block. */
	private fun recordNextEntity(level: ClientLevel, partialTick: Float, holePos: BlockPos) {
		var cursor = entityCursor
		if (cursor == null || !cursor.hasNext()) cursor = entities.toList().iterator().also { entityCursor = it }
		if (!cursor.hasNext()) return
		val pos = cursor.next()
		val entity = level.getBlockEntity(pos)
		dropEntity(pos)
		if (entity == null) {
			entities.remove(pos)
			return
		}
		val quads = BlockQuads.entityQuads(entity, partialTick)
		if (quads.isEmpty()) return
		val corner = origin ?: return
		// Each quad goes to every cell its bounds reach (within BlockQuads.REACH), moved into that cell's frame.
		val byCell = HashMap<Long, MutableList<BlockQuads.Quad>>()
		val reach = BlockQuads.REACH
		for (quad in quads) {
			val b = quad.bounds()
			for (cx in floor(b[0]).toInt().coerceAtLeast(-reach)..floor(b[3]).toInt().coerceAtMost(reach))
				for (cy in floor(b[1]).toInt().coerceAtLeast(-reach)..floor(b[4]).toInt().coerceAtMost(reach))
					for (cz in floor(b[2]).toInt().coerceAtLeast(-reach)..floor(b[5]).toInt().coerceAtMost(reach)) {
						byCell.getOrPut(BlockPos.asLong(pos.x + cx, pos.y + cy, pos.z + cz)) { ArrayList() } +=
							quad.moved(-cx.toFloat(), -cy.toFloat(), -cz.toFloat())
					}
		}
		val cells = ArrayList<Long>()
		for ((key, extra) in byCell) {
			val x = BlockPos.getX(key) - corner.x
			val y = BlockPos.getY(key) - corner.y
			val z = BlockPos.getZ(key) - corner.z
			if (x !in 0 until SIZE || y !in 0 until SIZE || z !in 0 until SIZE) continue
			val slab = cellSlabs[key] ?: freeSlabs.removeFirstOrNull() ?: continue
			val own = BlockQuads.stateQuads(level.getBlockState(BlockPos.of(key)), level, holePos)
			val all = (own + extra).take(SLAB_QUADS)
			val start = STATIC_QUADS + slab * SLAB_QUADS
			for ((i, quad) in all.withIndex()) writeQuad(start + i, quad)
			slabCounts[slab] = all.size
			cellSlabs[key] = slab
			cells += key
			setCell(x, y, z, slabRun(slab))
		}
		entityCells[pos] = cells
	}

	/** Frees the slabs of [pos]'s block entity and gives its cells back their blocks' runs. */
	private fun dropEntity(pos: BlockPos) {
		val cells = entityCells.remove(pos) ?: return
		val level = Minecraft.getInstance().level
		val corner = origin
		for (key in cells) {
			val slab = cellSlabs.remove(key) ?: continue
			freeSlabs.addLast(slab)
			if (level == null || corner == null) continue
			val x = BlockPos.getX(key) - corner.x
			val y = BlockPos.getY(key) - corner.y
			val z = BlockPos.getZ(key) - corner.z
			if (x in 0 until SIZE && y in 0 until SIZE && z in 0 until SIZE) {
				setCell(x, y, z, run(level, level.getBlockState(BlockPos.of(key)), BlockPos.of(key)))
			}
		}
	}

	private fun setCell(x: Int, y: Int, z: Int, run: Int) {
		val one = MemoryUtil.memAllocInt(1)
		try {
			one.put(0, run)
			GL11.glBindTexture(GL12.GL_TEXTURE_3D, grid)
			unpackDefaults()
			GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, x, y, z, 1, 1, 1, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, one)
			GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
		} finally {
			MemoryUtil.memFree(one)
		}
	}

	/**
	 * Writes [quad] at [index]: texels 0-3 its corners (xyz) with, in w, its texture slot (-1: none, use the flat
	 * colour), its vertex colour and its flat colour (the bits of ARGB ints), and 0; texels 4-5 its texture coordinates.
	 */
	private fun writeQuad(index: Int, quad: BlockQuads.Quad) {
		val at = index * QUAD_TEXELS * 4
		for (i in 0..3) {
			quadData[at + i * 4] = quad.positions[i * 3]
			quadData[at + i * 4 + 1] = quad.positions[i * 3 + 1]
			quadData[at + i * 4 + 2] = quad.positions[i * 3 + 2]
		}
		quadData[at + 3] = textureSlot(quad.textureId).toFloat()
		quadData[at + 7] = Float.fromBits(quad.color)
		quadData[at + 11] = Float.fromBits(quad.flatColor)
		quadData[at + 15] = 0f
		for (i in 0..7) quadData[at + 16 + i] = quad.uvs[i]
		dirtyFrom = minOf(dirtyFrom, index)
		dirtyTo = maxOf(dirtyTo, index)
	}

	private fun textureSlot(id: Int): Int {
		if (id == 0) return -1
		for (i in 0 until TEXTURE_SLOTS) {
			if (textureSlots[i] == id) return i
			if (textureSlots[i] == 0) {
				textureSlots[i] = id
				return i
			}
		}
		return -1
	}

	private fun uploadQuads() {
		if (dirtyTo < dirtyFrom) return
		val floats = (dirtyTo - dirtyFrom + 1) * QUAD_TEXELS * 4
		val data = MemoryUtil.memAllocFloat(floats)
		try {
			data.put(quadData, dirtyFrom * QUAD_TEXELS * 4, floats).flip()
			GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, quadBuffer)
			GL15.glBufferSubData(GL31.GL_TEXTURE_BUFFER, dirtyFrom.toLong() * QUAD_TEXELS * 16, data)
			GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0)
		} finally {
			MemoryUtil.memFree(data)
		}
		dirtyFrom = Int.MAX_VALUE
		dirtyTo = -1
	}

	private fun averageColor(quads: List<BlockQuads.Quad>): Int {
		var r = 0f
		var g = 0f
		var b = 0f
		var weight = 0f
		for (quad in quads) {
			val color = quad.flatColor
			if (color ushr 24 == 0) continue
			val w = quad.area
			r += w * ((color shr 16) and 0xFF)
			g += w * ((color shr 8) and 0xFF)
			b += w * (color and 0xFF)
			weight += w
		}
		if (weight <= 0f) return 0
		return (0xFF shl 24) or ((r / weight).toInt() shl 16) or ((g / weight).toInt() shl 8) or (b / weight).toInt()
	}

	/** Creates the (empty) textures, so the lens shaders always have something valid bound to their samplers. */
	fun ensureTextures() {
		if (grid != 0) return
		grid = texture3d(GL30.GL_R32UI, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT)
		coarse = texture3d(GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE)
		quadBuffer = GL15.glGenBuffers()
		GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, quadBuffer)
		GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, QUADS.toLong() * QUAD_TEXELS * 16, GL15.GL_DYNAMIC_DRAW)
		GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0)
		quadTexture = GL11.glGenTextures()
		GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, quadTexture)
		GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, quadBuffer)
		GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0)
	}

	private fun texture3d(internalFormat: Int, format: Int, type: Int): Int {
		val id = GL11.glGenTextures()
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, id)
		GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, internalFormat, SIZE, SIZE, SIZE, 0, format, type, null as java.nio.ByteBuffer?)
		GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
		GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
		return id
	}

	private fun unpackDefaults() {
		GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0)
		GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0)
		GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0)
		GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4)
		GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0)
		GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0)
	}
}
