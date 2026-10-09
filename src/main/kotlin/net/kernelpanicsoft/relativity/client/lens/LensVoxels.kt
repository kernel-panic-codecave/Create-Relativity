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
import kotlin.math.floor

/**
 * The world round the nearest hole, for the sky's bent light paths to trace (the Einstein ring and the images round it)
 * (lens_scene.glsl's geometryHit):
 *
 * - **Grid** ([SIZE]³ one-block cells, R32UI): each cell's quads, as (start << 8) | count into the quad buffer.
 * - **Quad buffer** (a texture buffer, [QUAD_TEXELS] RGBA32F texels per quad): the real vertex data of what each block
 *   draws ([BlockQuads]), relative to the cell. Every copy of a block state shares one run; cells a block entity draws
 *   into get their own run (a slab), the cell's block plus the entity's quads, re-recorded in turn.
 * - **Light grid** ([SIZE]³, RG8, alongside the fine grid): each cell's sky and block light (0-15 as 0-1), sampled
 *   with linear filtering half a block in front of the face a ray hits, through the game's lightmap.
 * - **Tint grid** ([SIZE]³, RG32UI, alongside the fine grid): each cell's block tint and fluid tint (RGB), as the game
 *   computes them there (biome colours), for the quads flagged as tinted ([BlockQuads.Quad.tint]).
 * - **Texture slots**: the GL textures quads sample, up to [TEXTURE_SLOTS]; quads past that use their flat colour.
 *
 * Two sets of these grids: [main] round the hole, and [behind], centred [SIZE] / 2 past it along the line from the
 * camera, where the vertex lens leaves the band near that line to the post pass (its images crowd the Einstein ring)
 * all the way out. [behind] has no block-entity renders, and only counts once it's filled after a move.
 *
 * The grids fill z slices for up to [BUDGET_MILLIS] a frame until complete, then re-read only slices whose sections
 * changed (block updates, light and chunk arrivals, via LevelRendererMixin), at most [REFRESH_SLICES] a frame; they
 * start over when they move (the hole to another block; [behind] by [BEHIND_SNAP] blocks). Paths that leave both meet
 * nothing (the sky).
 */
object LensVoxels {
	const val SIZE = 128

	/** Cells per side of a brick, the walk's empty-space skip ([Grid.bricks]); [SIZE] / [BRICK] per side. */
	const val BRICK = 8
	const val BRICKS = SIZE / BRICK
	const val SMALL_BRICK = 2
	const val SMALL_BRICKS = SIZE / SMALL_BRICK
	const val TEXTURE_SLOTS = 8
	const val QUAD_TEXELS = 6
	private const val BUDGET_MILLIS = 3.0
	private const val REFRESH_SLICES = 4
	private const val ENTITIES_PER_FRAME = 4
	private const val STATIC_QUADS = 1 shl 17
	private const val SLAB_QUADS = 255
	private const val SLABS = 512
	private const val QUADS = STATIC_QUADS + SLAB_QUADS * SLABS
	private const val BEHIND_SNAP = 16

	/** One set of grids (cells, light, tint) over [SIZE]³ blocks from [origin]. */
	class Grid(internal val withEntities: Boolean) {
		/** Cells (GL_TEXTURE_3D, R32UI), light (RG8) and tint (RG32UI). */
		var cells = 0
			internal set
		var light = 0
			internal set
		var tint = 0
			internal set

		/** Per brick of [BRICK]³ cells, 1 if any holds quads (GL_TEXTURE_3D, R8UI, [BRICKS]³): the walk skips empty ones. */
		var bricks = 0
			internal set

		/** The same per [SMALL_BRICK]³ cells ([SMALL_BRICKS]³), inside bricks that aren't empty. */
		var smallBricks = 0
			internal set
		internal val occupied = java.util.BitSet(SIZE * SIZE * SIZE)
		internal val brickCounts = IntArray(BRICKS * BRICKS * BRICKS)
		internal val smallBrickCounts = IntArray(SMALL_BRICKS * SMALL_BRICKS * SMALL_BRICKS)
		internal var bricksDirty = true

		/** Records whether cell (x, y, z) holds quads, keeping its brick's count. */
		internal fun mark(x: Int, y: Int, z: Int, filled: Boolean) {
			val index = (z * SIZE + y) * SIZE + x
			if (occupied[index] == filled) return
			occupied[index] = filled
			val change = if (filled) 1 else -1
			brickCounts[((z / BRICK) * BRICKS + y / BRICK) * BRICKS + x / BRICK] += change
			smallBrickCounts[((z / SMALL_BRICK) * SMALL_BRICKS + y / SMALL_BRICK) * SMALL_BRICKS + x / SMALL_BRICK] += change
			bricksDirty = true
		}

		/** The minimum corner (world block position), or null with no hole. */
		var origin: BlockPos? = null
			internal set
		internal var filled = 0
		internal var level = java.lang.ref.WeakReference<ClientLevel>(null)
		internal val dirty = java.util.BitSet(SIZE)

		/** True once filled since it last moved. */
		val ready: Boolean get() = origin != null && filled >= SIZE
	}

	/** Round the hole (with block entities), and past it away from the camera. */
	val main = Grid(true)
	val behind = Grid(false)

	/** The quad buffer's texture (GL_TEXTURE_BUFFER). */
	var quadTexture = 0
		private set
	private var quadBuffer = 0

	/**
	 * Water's surface as it blends over what's under it: its top's mean texel, untinted (RGB 0-1), and its opacity.
	 * Paths pass through tinted fluids (lens_scene.glsl); what they reach under one gets this, times the fluid's tint.
	 */
	val waterSurface = floatArrayOf(0.7f, 0.7f, 0.7f, 0.7f)

	/** GL ids of the textures in slots 0 until [TEXTURE_SLOTS] (0: unused). */
	val textureSlots = IntArray(TEXTURE_SLOTS)

	/** CPU time of the latest [update] (ms). */
	@Volatile
	var updateMillis = 0.0
		private set

	private val gridSlice = MemoryUtil.memAllocInt(SIZE * SIZE)
	private val lightSlice = MemoryUtil.memAlloc(SIZE * SIZE * 2)
	private val tintSlice = MemoryUtil.memAllocInt(SIZE * SIZE * 2)

	private val quadData = FloatArray(QUADS * QUAD_TEXELS * 4)
	private var staticQuads = 0
	private var dirtyFrom = Int.MAX_VALUE
	private var dirtyTo = -1

	/** Per block state: its run ((start << 8) | count, 0 for nothing) and which tints its quads use. */
	private val stateRuns = HashMap<BlockState, Int>()
	private val stateTints = HashMap<BlockState, Int>()

	/** Block entities in the grid, re-recorded in turn, and the cells each draws into (world cell → slab). */
	private val entities = LinkedHashSet<BlockPos>()
	private var entityCursor: Iterator<BlockPos>? = null
	private val entityCells = HashMap<BlockPos, MutableList<Long>>()
	private val cellSlabs = HashMap<Long, Int>()
	private val slabCounts = IntArray(SLABS)
	private val freeSlabs = ArrayDeque((0 until SLABS).toList())

	/** True once the main grids are in. */
	val ready: Boolean get() = main.ready

	/** How many quads the main grid holds for the block at [pos] (its own, or its block entity's slab); -1 outside it. */
	fun quadsAt(level: ClientLevel, pos: BlockPos): Int {
		val corner = main.origin ?: return -1
		if (pos.x - corner.x !in 0 until SIZE || pos.y - corner.y !in 0 until SIZE || pos.z - corner.z !in 0 until SIZE) return -1
		cellSlabs[pos.asLong()]?.let { return slabCounts[it] }
		return (stateRuns[level.getBlockState(pos)] ?: 0) and 255
	}

	/** A section changed (block update or chunk arrival): the slices it covers are re-read. */
	@JvmStatic
	fun sectionChanged(sectionX: Int, sectionY: Int, sectionZ: Int) {
		for (grid in listOf(main, behind)) {
			val corner = grid.origin ?: continue
			val z0 = sectionZ * 16 - corner.z
			if (sectionX * 16 - corner.x in -15 until SIZE && sectionY * 16 - corner.y in -15 until SIZE && z0 in -15 until SIZE) {
				grid.dirty.set(maxOf(z0, 0), minOf(z0 + 16, SIZE))
			}
		}
	}

	/** Advances the grids round [hole] (nearest first), seen from [camera], by this frame's work. */
	fun update(level: ClientLevel, hole: BlackHole?, camera: net.minecraft.world.phys.Vec3, partialTick: Float) {
		val started = System.nanoTime()
		try {
			if (hole == null) {
				main.origin = null
				behind.origin = null
				return
			}
			ensureTextures()
			val holePos = BlockPos.containing(hole.x, hole.y, hole.z)
			place(main, level, holePos.offset(-SIZE / 2, -SIZE / 2, -SIZE / 2))
			// Past the hole along the line from the camera, snapped so it moves only now and then.
			val away = hole.center.subtract(camera).normalize().scale(SIZE / 2.0)
			fun snap(v: Double) = Math.floorDiv(Math.round(v).toInt(), BEHIND_SNAP) * BEHIND_SNAP
			place(behind, level, BlockPos(snap(hole.x + away.x) - SIZE / 2, snap(hole.y + away.y) - SIZE / 2, snap(hole.z + away.z) - SIZE / 2))
			// While filling, slices until this frame's budget is spent (at least one); then dirty ones only.
			val deadline = started + (BUDGET_MILLIS * 1e6).toLong()
			for (grid in listOf(main, behind)) {
				var count = 0
				while (true) {
					val filling = grid.filled < SIZE
					val z = if (filling) grid.filled else grid.dirty.nextSetBit(0).takeIf { it >= 0 } ?: break
					grid.dirty.clear(z)
					readSlice(grid, level, z)
					if (filling) grid.filled += 1
					count += 1
					if (if (filling) System.nanoTime() >= deadline else count >= REFRESH_SLICES) break
				}
				if (System.nanoTime() >= deadline) break
			}
			repeat(minOf(ENTITIES_PER_FRAME, entities.size)) { recordNextEntity(level, partialTick) }
			uploadQuads()
			for (grid in listOf(main, behind)) uploadBricks(grid)
		} finally {
			updateMillis = (System.nanoTime() - started) / 1e6
		}
	}

	/** Moves [grid] to [corner] in [level] (starting over if that's a change). */
	private fun place(grid: Grid, level: ClientLevel, corner: BlockPos) {
		if (corner == grid.origin && grid.level.get() === level) return
		grid.level = java.lang.ref.WeakReference(level)
		if (grid.withEntities) {
			for (pos in entityCells.keys.toList()) dropEntity(pos)
			entities.clear()
			entityCursor = null
		}
		grid.origin = corner
		version += 1
		grid.filled = 0
		grid.dirty.clear()
		grid.occupied.clear()
		grid.brickCounts.fill(0)
		grid.smallBrickCounts.fill(0)
		grid.bricksDirty = true
	}

	/** Counts every change to what the grids hold (a slice read, quads uploaded, a grid moved): the post pass's tables are reused while it stands. */
	@Volatile
	var version = 0L
		private set

	/** Reads [grid]'s slice [z]: each cell's run (a block entity's slab where it draws), from the chunk sections. */
	private fun readSlice(grid: Grid, level: ClientLevel, z: Int) {
		version += 1
		val corner = grid.origin ?: return
		val worldZ = corner.z + z
		for (i in 0 until SIZE * SIZE) gridSlice.put(i, 0)
		for (i in 0 until SIZE * SIZE * 2) tintSlice.put(i, -1)
		val pos = BlockPos.MutableBlockPos()
		val colors = Minecraft.getInstance().blockColors
		forEachState(level, corner.x, corner.y, worldZ) { x, y, state, chunk ->
			if (grid.withEntities && state.hasBlockEntity()) {
				val worldPos = BlockPos(corner.x + x, corner.y + y, worldZ)
				if (chunk.getBlockEntity(worldPos) != null) entities += worldPos
			}
			gridSlice.put(y * SIZE + x, run(level, state))
			val tints = stateTints[state] ?: 0
			if (tints != 0) {
				pos.set(corner.x + x, corner.y + y, worldZ)
				if (tints and (1 shl BlockQuads.TINT_BLOCK) != 0) tintSlice.put((y * SIZE + x) * 2, colors.getColor(state, level, pos, 0))
				if (tints and (1 shl BlockQuads.TINT_FLUID) != 0) tintSlice.put((y * SIZE + x) * 2 + 1, fluidTint(level, state, pos))
			}
		}
		if (grid.withEntities) for ((key, slab) in cellSlabs) {
			val x = BlockPos.getX(key) - corner.x
			val y = BlockPos.getY(key) - corner.y
			if (BlockPos.getZ(key) == worldZ && x in 0 until SIZE && y in 0 until SIZE) gridSlice.put(y * SIZE + x, slabRun(slab))
		}
		for (i in 0 until SIZE * SIZE) grid.mark(i % SIZE, i / SIZE, z, gridSlice.get(i) != 0)
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, grid.cells)
		unpackDefaults()
		GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, z, SIZE, SIZE, 1, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, gridSlice)
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, grid.tint)
		unpackDefaults()
		GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, z, SIZE, SIZE, 1, GL30.GL_RG_INTEGER, GL11.GL_UNSIGNED_INT, tintSlice)
		readLightSlice(level, corner, z)
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, grid.light)
		unpackDefaults()
		GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, z, SIZE, SIZE, 1, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, lightSlice)
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
	}

	/** Sky and block light of slice [z] (17 per level, so 15 maps to 255), per cell, from the light engine's sections. */
	private fun readLightSlice(level: ClientLevel, corner: BlockPos, z: Int) {
		val engine = level.lightEngine
		val sky = engine.getLayerListener(net.minecraft.world.level.LightLayer.SKY)
		val block = engine.getLayerListener(net.minecraft.world.level.LightLayer.BLOCK)
		val worldZ = corner.z + z
		val pos = BlockPos.MutableBlockPos()
		for (y in 0 until SIZE) for (x in 0 until SIZE) {
			pos.set(corner.x + x, corner.y + y, worldZ)
			val at = (y * SIZE + x) * 2
			lightSlice.put(at, (sky.getLightValue(pos) * 17).toByte())
			lightSlice.put(at + 1, (block.getLightValue(pos) * 17).toByte())
		}
	}

	/** The fluid tint at [pos] (RGB), as the fluid renderer computes it. */
	private fun fluidTint(level: ClientLevel, state: BlockState, pos: BlockPos): Int =
		net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions.of(state.fluidState).getTintColor(state.fluidState, level, pos)

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
	private fun run(level: ClientLevel, state: BlockState): Int {
		stateRuns[state]?.let { return it }
		val quads = BlockQuads.stateQuads(state, level).take(SLAB_QUADS)
		stateTints[state] = quads.fold(0) { mask, quad -> if (quad.tint != BlockQuads.TINT_NONE) mask or (1 shl quad.tint) else mask }
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
	private fun recordNextEntity(level: ClientLevel, partialTick: Float) {
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
		val corner = main.origin ?: return
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
			val own = BlockQuads.stateQuads(level.getBlockState(BlockPos.of(key)), level)
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
		val corner = main.origin
		for (key in cells) {
			val slab = cellSlabs.remove(key) ?: continue
			freeSlabs.addLast(slab)
			if (level == null || corner == null) continue
			val x = BlockPos.getX(key) - corner.x
			val y = BlockPos.getY(key) - corner.y
			val z = BlockPos.getZ(key) - corner.z
			if (x in 0 until SIZE && y in 0 until SIZE && z in 0 until SIZE) {
				setCell(x, y, z, run(level, level.getBlockState(BlockPos.of(key))))
			}
		}
	}

	/** Uploads [grid]'s brick occupancy if it changed. */
	private fun uploadBricks(grid: Grid) {
		if (!grid.bricksDirty) return
		grid.bricksDirty = false
		for ((texture, counts, size) in listOf(Triple(grid.bricks, grid.brickCounts, BRICKS), Triple(grid.smallBricks, grid.smallBrickCounts, SMALL_BRICKS))) {
			val data = MemoryUtil.memAlloc(size * size * size)
			try {
				for (i in counts.indices) data.put(i, if (counts[i] > 0) 1 else 0)
				GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture)
				unpackDefaults()
				GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1)
				GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, 0, size, size, size, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, data)
				GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4)
				GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
			} finally {
				MemoryUtil.memFree(data)
			}
		}
	}

	private fun setCell(x: Int, y: Int, z: Int, run: Int) {
		main.mark(x, y, z, run != 0)
		val one = MemoryUtil.memAllocInt(1)
		try {
			one.put(0, run)
			GL11.glBindTexture(GL12.GL_TEXTURE_3D, main.cells)
			unpackDefaults()
			GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, x, y, z, 1, 1, 1, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, one)
			GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
		} finally {
			MemoryUtil.memFree(one)
		}
	}

	/**
	 * Writes [quad] at [index]: texels 0-3 its corners (xyz) with, in w, its texture slot (-1: none, use the flat
	 * colour), its vertex colour and its flat colour (the bits of ARGB ints), and its tint flag; texels 4-5 its texture
	 * coordinates.
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
		quadData[at + 15] = quad.tint.toFloat()
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
		version += 1
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

	/** Creates the (empty) textures, so the lens shaders always have something valid bound to their samplers. */
	fun ensureTextures() {
		if (main.cells != 0) return
		val level = Minecraft.getInstance().level
		if (level != null) {
			BlockQuads.stateQuads(net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), level)
				.firstOrNull { it.tint == BlockQuads.TINT_FLUID && it.normal[1] > 0.7f }
				?.let { quad ->
					val mean = quad.meanColor
					for (k in 0..2) waterSurface[k] = ((mean shr (16 - 8 * k)) and 0xFF) / 255f
					waterSurface[3] = (mean ushr 24) / 255f
				}
		}
		for (grid in listOf(main, behind)) {
			grid.cells = texture3d(GL30.GL_R32UI, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT)
			grid.light = texture3d(GL30.GL_RG8, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, GL11.GL_LINEAR)
			grid.tint = texture3d(GL30.GL_RG32UI, GL30.GL_RG_INTEGER, GL11.GL_UNSIGNED_INT)
			grid.bricks = texture3d(GL30.GL_R8UI, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, size = BRICKS)
			grid.smallBricks = texture3d(GL30.GL_R8UI, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, size = SMALL_BRICKS)
		}
		quadBuffer = GL15.glGenBuffers()
		GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, quadBuffer)
		GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, QUADS.toLong() * QUAD_TEXELS * 16, GL15.GL_DYNAMIC_DRAW)
		GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0)
		quadTexture = GL11.glGenTextures()
		GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, quadTexture)
		GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, quadBuffer)
		GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0)
	}

	private fun texture3d(internalFormat: Int, format: Int, type: Int, filter: Int = GL11.GL_NEAREST, size: Int = SIZE): Int {
		val id = GL11.glGenTextures()
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, id)
		GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, internalFormat, size, size, size, 0, format, type, null as java.nio.ByteBuffer?)
		GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, filter)
		GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, filter)
		GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
		GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
		GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE)
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
