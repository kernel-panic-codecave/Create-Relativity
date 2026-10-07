package net.kernelpanicsoft.relativity.client.lens

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import net.kernelpanicsoft.relativity.mixin.client.RenderTypeTextureAccessors
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import net.minecraft.world.level.BlockAndTintGetter
import net.minecraft.world.level.ColorResolver
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.lighting.LevelLightEngine
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids
import net.neoforged.neoforge.client.model.data.ModelData
import org.lwjgl.opengl.GL11
import org.lwjgl.system.MemoryUtil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * What a block draws, as quads for the lens to trace (LensVoxels): its full render, whatever the method, recorded into
 * a consumer that keeps the vertices. The chunk-mesh path (every render type of its model, and its fluid), and for a
 * block entity its renderer, with Flywheel reporting no visualization meanwhile so renderers that defer to Flywheel
 * instances emit the same model quads themselves (VisualizationManagerImplMixin). Static blocks are drawn on their own
 * (no neighbours, no face shading baked in; the lens shades by face), so one recording serves every copy.
 */
object BlockQuads {
	/** How far a block entity's render may reach from its own block, in blocks. */
	const val REACH = 2

	@Volatile
	private var recording = false

	/** True while a render is being recorded (Flywheel then reports no visualization). */
	@JvmStatic
	fun isRecording(): Boolean = recording

	/**
	 * [state] drawn on its own at the origin (no neighbours), untinted: quads the game tints by position (biome colours,
	 * through the level's getBlockTint) come out white and carry [Quad.tint] ([TINT_BLOCK] for the block's model,
	 * [TINT_FLUID] for its fluid), for the lens to tint per cell. Found by drawing twice, under white and black tints.
	 */
	fun stateQuads(state: BlockState, level: Level): List<Quad> {
		fun model(tint: Int) = record { buffers ->
			val minecraft = Minecraft.getInstance()
			val alone = Alone(state, level, tint)
			val random = RandomSource.create(42L)
			if (state.renderShape == net.minecraft.world.level.block.RenderShape.MODEL) {
				val model = minecraft.blockRenderer.getBlockModel(state)
				for (type in model.getRenderTypes(state, random, ModelData.EMPTY)) {
					minecraft.blockRenderer.renderBatched(state, BlockPos.ZERO, alone, PoseStack(), buffers.getBuffer(type), false, random, ModelData.EMPTY, type)
				}
			}
		}
		fun fluid(tint: Int) = record { buffers ->
			val fluid = state.fluidState
			if (!fluid.isEmpty) {
				Minecraft.getInstance().blockRenderer.renderLiquid(BlockPos.ZERO, Alone(state, level, tint), buffers.getBuffer(RenderType.translucent()), state, fluid)
			}
		}
		return flagTinted(model(0xFFFFFF), model(0), TINT_BLOCK) + flagTinted(fluid(0xFFFFFF), fluid(0), TINT_FLUID)
	}

	const val TINT_NONE = 0
	const val TINT_BLOCK = 1
	const val TINT_FLUID = 2

	/** [white]'s quads, flagged [flag] where [black] (the same draw under a black tint) differs in colour. */
	private fun flagTinted(white: List<Quad>, black: List<Quad>, flag: Int): List<Quad> {
		if (white.size != black.size) return white
		return white.mapIndexed { i, quad -> if (quad.color != black[i].color) quad.withTint(flag) else quad }
	}

	/** What [entity]'s renderer draws, relative to its block; empty if it has no renderer. */
	fun entityQuads(entity: BlockEntity, partialTick: Float): List<Quad> {
		val minecraft = Minecraft.getInstance()
		val renderer = minecraft.blockEntityRenderDispatcher.getRenderer(entity) ?: return emptyList()
		return record { buffers ->
			@Suppress("UNCHECKED_CAST")
			(renderer as net.minecraft.client.renderer.blockentity.BlockEntityRenderer<BlockEntity>)
				.render(entity, partialTick, PoseStack(), buffers, LIGHT_FULL, OverlayTexture.NO_OVERLAY)
		}
	}

	private const val LIGHT_FULL = 0xF000F0

	private fun record(draw: (MultiBufferSource) -> Unit): List<Quad> {
		val recorder = Recorder()
		recording = true
		try {
			draw(recorder)
		} catch (e: Exception) {
			// A renderer that can't draw outside the level render: whatever it recorded is kept.
		} finally {
			recording = false
		}
		return recorder.finish()
	}

	/**
	 * A recorded quad (a triangle has its last vertex repeated): corner positions (x, y, z each) and texture coordinates
	 * (u, v each), the first vertex's colour (ARGB), the texture it samples (null: untextured) and its GL id.
	 */
	class Quad(
		val positions: FloatArray,
		val uvs: FloatArray,
		val color: Int,
		val texture: Texels?,
		val textureId: Int,
		/** [TINT_NONE], or which per-position tint the game multiplies in ([TINT_BLOCK], [TINT_FLUID]). */
		val tint: Int = TINT_NONE,
	) {
		fun withTint(flag: Int) = Quad(positions, uvs, color, texture, textureId, flag)

		/** Unit normal by winding (vanilla quads wind counter-clockwise seen from the front). */
		val normal: FloatArray get() {
			val p = positions
			val ax = p[6] - p[0]
			val ay = p[7] - p[1]
			val az = p[8] - p[2]
			val bx = p[9] - p[3]
			val by = p[10] - p[4]
			val bz = p[11] - p[5]
			val cx = ay * bz - az * by
			val cy = az * bx - ax * bz
			val cz = ax * by - ay * bx
			val l = sqrt(cx * cx + cy * cy + cz * cz)
			return if (l < 1e-9f) floatArrayOf(0f, 0f, 0f) else floatArrayOf(cx / l, cy / l, cz / l)
		}

		/** Its mean texel (all pixels, sampled 4 × 4, alpha included) times vertex colour, ARGB: how a translucent quad blends. */
		val meanColor: Int by lazy {
			val sums = IntArray(4)
			for (i in 0..3) for (j in 0..3) {
				val s = (i + 0.5f) / 4f
				val t = (j + 0.5f) / 4f
				val u = (1 - s) * (1 - t) * uvs[0] + s * (1 - t) * uvs[2] + s * t * uvs[4] + (1 - s) * t * uvs[6]
				val v = (1 - s) * (1 - t) * uvs[1] + s * (1 - t) * uvs[3] + s * t * uvs[5] + (1 - s) * t * uvs[7]
				val texel = texture?.at(u, v) ?: -1
				for (k in 0..3) sums[k] += (texel shr (24 - 8 * k)) and 0xFF
			}
			fun channel(k: Int) = sums[k] / 16 * ((color shr (24 - 8 * k)) and 0xFF) / 255
			(channel(0) shl 24) or (channel(1) shl 16) or (channel(2) shl 8) or channel(3)
		}

		/** Its colour seen from afar: texture (opaque pixels, sampled 4 × 4) times vertex colour, ARGB; alpha 0 if none. */
		val flatColor: Int by lazy {
			var r = 0
			var g = 0
			var b = 0
			var n = 0
			for (i in 0..3) for (j in 0..3) {
				val s = (i + 0.5f) / 4f
				val t = (j + 0.5f) / 4f
				val u = (1 - s) * (1 - t) * uvs[0] + s * (1 - t) * uvs[2] + s * t * uvs[4] + (1 - s) * t * uvs[6]
				val v = (1 - s) * (1 - t) * uvs[1] + s * (1 - t) * uvs[3] + s * t * uvs[5] + (1 - s) * t * uvs[7]
				val texel = texture?.at(u, v) ?: -1
				if (texel ushr 24 < 26) continue
				r += (texel shr 16) and 0xFF
				g += (texel shr 8) and 0xFF
				b += texel and 0xFF
				n += 1
			}
			if (n == 0) 0 else (0xFF shl 24) or
				((r / n) * ((color shr 16) and 0xFF) / 255 shl 16) or
				((g / n) * ((color shr 8) and 0xFF) / 255 shl 8) or
				((b / n) * (color and 0xFF) / 255)
		}

		/** Area (twice the triangles' sum, roughly), for weighting colours. */
		val area: Float get() {
			val p = positions
			val ax = p[3] - p[0]
			val ay = p[4] - p[1]
			val az = p[5] - p[2]
			val bx = p[9] - p[0]
			val by = p[10] - p[1]
			val bz = p[11] - p[2]
			val cx = ay * bz - az * by
			val cy = az * bx - ax * bz
			val cz = ax * by - ay * bx
			return sqrt(cx * cx + cy * cy + cz * cz)
		}

		/** This quad moved by ([dx], [dy], [dz]). */
		fun moved(dx: Float, dy: Float, dz: Float) =
			Quad(FloatArray(12) { positions[it] + if (it % 3 == 0) dx else if (it % 3 == 1) dy else dz }, uvs, color, texture, textureId, tint)

		/** Axis-aligned bounds (min x, y, z, max x, y, z). */
		fun bounds(): FloatArray {
			val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
			for (i in 0..3) for (k in 0..2) {
				b[k] = minOf(b[k], positions[i * 3 + k])
				b[k + 3] = maxOf(b[k + 3], positions[i * 3 + k])
			}
			return b
		}
	}

	/** Records quads per render type; lines and other modes are dropped. */
	private class Recorder : MultiBufferSource {
		private val consumers = LinkedHashMap<RenderType, Consumer>() // in draw order

		override fun getBuffer(type: RenderType): VertexConsumer = consumers.getOrPut(type) { Consumer(type) }

		fun finish(): List<Quad> = consumers.values.flatMap { it.finish() }

		inner class Consumer(type: RenderType) : VertexConsumer {
			private val corners = when (type.mode()) {
				VertexFormat.Mode.QUADS -> 4
				VertexFormat.Mode.TRIANGLES -> 3
				else -> 0
			}
			private val textureId = Texels.idOf(type)
			private val texture = if (textureId == 0) null else Texels.of(textureId)
			private val quads = ArrayList<Quad>()
			private val positions = FloatArray(12)
			private val uvs = FloatArray(8)
			private val colors = IntArray(4)
			private var count = -1 // vertices in the current quad, the last one still being filled

			override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer {
				count += 1
				if (count == corners) flush()
				val i = count
				if (i < 4) {
					positions[i * 3] = x
					positions[i * 3 + 1] = y
					positions[i * 3 + 2] = z
					uvs[i * 2] = 0f
					uvs[i * 2 + 1] = 0f
					colors[i] = -1
				}
				return this
			}

			override fun setColor(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer {
				if (count in 0..3) colors[count] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
				return this
			}

			override fun setUv(u: Float, v: Float): VertexConsumer {
				if (count in 0..3) {
					uvs[count * 2] = u
					uvs[count * 2 + 1] = v
				}
				return this
			}

			override fun setUv1(u: Int, v: Int): VertexConsumer = this
			override fun setUv2(u: Int, v: Int): VertexConsumer = this
			override fun setNormal(x: Float, y: Float, z: Float): VertexConsumer = this

			/** The current quad is complete: keep it, and start the next with the vertex just begun. */
			private fun flush() {
				if (corners == 0) {
					count = 0
					return
				}
				if (corners == 3) {
					for (k in 0..2) positions[9 + k] = positions[6 + k]
					uvs[6] = uvs[4]
					uvs[7] = uvs[5]
					colors[3] = colors[2]
				}
				quads += Quad(positions.copyOf(), uvs.copyOf(), colors[0], texture, textureId)
				count = 0
			}

			fun finish(): List<Quad> {
				if (corners != 0 && count + 1 == corners) {
					count = corners
					flush()
				}
				return quads
			}
		}
	}

	/** A texture's pixels (ARGB), read back from the GPU once and kept until resources reload. */
	class Texels private constructor(private val width: Int, private val height: Int, private val pixels: IntArray) {
		/** The pixel at ([u], [v]) (wrapping), as ARGB. */
		fun at(u: Float, v: Float): Int {
			val x = Math.floorMod(floor(u * width).toInt(), width)
			val y = Math.floorMod(floor(v * height).toInt(), height)
			val abgr = pixels[y * width + x] // RGBA bytes, read as a little-endian int
			return (abgr and 0xFF00FF00.toInt()) or ((abgr and 0xFF) shl 16) or ((abgr shr 16) and 0xFF)
		}

		companion object {
			private val byId = HashMap<Int, Texels?>()

			/** Forgets every texture (after a resource reload, when ids and contents change). */
			fun clear() = byId.clear()

			/** The GL id of the texture [type] draws with; 0 if it has none. */
			fun idOf(type: RenderType): Int {
				val composite = type as? RenderTypeTextureAccessors.Composite ?: return 0
				val shard = (composite.`create_relativity$state`() as RenderTypeTextureAccessors.State).`create_relativity$textureState`()
				val location = (shard as RenderTypeTextureAccessors.Texture).`create_relativity$texture`().orElse(null) ?: return 0
				return Minecraft.getInstance().textureManager.getTexture(location).id
			}

			/** The pixels of GL texture [id]. */
			fun of(id: Int): Texels? = byId.getOrPut(id) { read(id) }

			private fun read(id: Int): Texels? {
				GlStateManager._bindTexture(id)
				val width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH)
				val height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT)
				if (width <= 0 || height <= 0) return null
				val buffer = MemoryUtil.memAlloc(width * height * 4)
				try {
					GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4)
					GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer)
					val pixels = IntArray(width * height)
					buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels)
					return Texels(width, height, pixels)
				} finally {
					MemoryUtil.memFree(buffer)
				}
			}
		}
	}

	/**
	 * A world with just [state] at the origin, every biome tint [tint] (RGB), and no face shading: what a block looks
	 * like on its own, the same anywhere.
	 */
	private class Alone(private val state: BlockState, private val level: Level, private val tint: Int) : BlockAndTintGetter {
		override fun getShade(direction: Direction, shade: Boolean) = 1f
		override fun getLightEngine(): LevelLightEngine = level.lightEngine
		override fun getBlockTint(pos: BlockPos, resolver: ColorResolver) = tint
		override fun getBlockEntity(pos: BlockPos): BlockEntity? = null
		override fun getBlockState(pos: BlockPos): BlockState = if (pos == BlockPos.ZERO) state else Blocks.AIR.defaultBlockState()
		override fun getFluidState(pos: BlockPos): FluidState = if (pos == BlockPos.ZERO) state.fluidState else Fluids.EMPTY.defaultFluidState()
		override fun getHeight() = level.height
		override fun getMinBuildHeight() = level.minBuildHeight
	}
}
