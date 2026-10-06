package net.kernelpanicsoft.relativity.client.lens

import com.mojang.blaze3d.platform.GlStateManager
import foundry.veil.api.client.render.VeilLevelPerspectiveRenderer
import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.mixin.client.GameRendererShadersAccessor
import net.kernelpanicsoft.relativity.physics.lens.SchwarzschildImages
import net.kernelpanicsoft.relativity.spacetime.BlackHole
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.AABB
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.common.NeoForge
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL31
import org.lwjgl.opengl.GL41
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.util.concurrent.CompletableFuture
import kotlin.math.abs

/**
 * High-quality lens preset: world geometry is lensed in the vertex shader (vertex_lens.glsl, injected into vanilla's
 * world shaders through Veil). Each vertex moves to where its light appears, at its true distance, so depth testing
 * handles occlusion: no near/far cutoff, no doubling, nothing culled where the screen-space lens left holes. This feeds
 * those shaders: a uniform block (the nearest lensing hole, the level's projection) and the inverse image table
 * ([SchwarzschildImages.forFiltering]: correction and validity) as a 3D texture. The post pass then only lenses the sky and draws the shadow.
 */
object VertexLens {
	private const val TABLE_U0 = 64
	private const val TABLE_UP = 64
	private const val TABLE_PHI = 128
	private const val TEXTURE_UNIT = 15
	private const val BLOCK = "RelativityLens"
	private const val SAMPLER = "RelativityImages"
	private const val BLOCK_BYTES = 96 // vec4 hole, vec4 params, mat4 projection (std140)

	/** Spin from which the hole is treated as Kerr (kerr.glsl's MIN_KERR_SPIN), which this preset doesn't handle. */
	private const val MIN_KERR_SPIN = 0.05

	/** Boxes within this many r_s of the hole skip frustum culling: bent light can show them from anywhere. */
	private const val RELAX_RADIUS = 60.0

	private var table: CompletableFuture<FloatArray>? = null
	private var texture = 0
	private var buffer = 0
	private var binding = -1
	private val linkedPrograms = HashSet<Int>()

	/** The hole lensing this frame (world position), or null. */
	@Volatile private var hole: BlackHole? = null
	private var savedSmartCull: Boolean? = null

	/** True while vertex lensing is on for this frame's level render (the post pass then skips geometry). */
	var activeThisFrame = false
		private set

	fun init() {
		table = CompletableFuture.supplyAsync {
			SchwarzschildImages.forFiltering(SchwarzschildImages.buildTable(TABLE_U0, TABLE_UP, TABLE_PHI), TABLE_U0, TABLE_UP, TABLE_PHI)
		}
		NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent::class.java, ::onRenderStage)
	}

	/** Debug: draws every vertex at its flat-space direction, without the image table (GameTests). */
	@JvmField
	var skipTable = false

	/** Overrides the Vertex Lensing setting when set (GameTests). */
	@JvmField
	var forced: Boolean? = null

	private val wanted: Boolean
		get() = RelativityConfig.Client.Render.lensing && (forced ?: RelativityConfig.Client.Render.vertexLensing)

	private fun onRenderStage(event: RenderLevelStageEvent) {
		when (event.stage) {
			RenderLevelStageEvent.Stage.AFTER_SKY -> beginLevel(event.projectionMatrix)
			RenderLevelStageEvent.Stage.AFTER_LEVEL -> setEnabled(false)
			else -> Unit
		}
	}

	/** Before terrain: pick the hole, upload the block, bind the table, link any new programs. */
	private fun beginLevel(projection: Matrix4f) {
		if (!ensureResources()) return
		linkPrograms()
		val minecraft = Minecraft.getInstance()
		val level = minecraft.level
		// Perspective renders (Veil level views, such as other mods' cameras) draw the level too, and stay unlensed.
		val perspective = VeilLevelPerspectiveRenderer.isRenderingPerspective()
		val camera = minecraft.gameRenderer.mainCamera.position
		// The image table is Schwarzschild's: a spinning hole is left to the post pass, which marches Kerr.
		val nearest = if (wanted && level != null && !perspective) {
			BlackHoleLens.visibleHoles(level).minByOrNull { it.center.distanceToSqr(camera) }?.takeIf { abs(it.spin) < MIN_KERR_SPIN }
		} else null
		if (!perspective) {
			hole = nearest
			activeThisFrame = nearest != null
			relaxCaveCulling(nearest != null)
		}
		// The level's view rotation (as GameRenderer builds it): the shader works in view space.
		val view = Quaternionf(minecraft.gameRenderer.mainCamera.rotation()).conjugate()
		upload(nearest, camera.x, camera.y, camera.z, view, projection, nearest != null)

		val previous = GlStateManager._getActiveTexture()
		GlStateManager._activeTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT)
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture)
		GlStateManager._activeTexture(previous)
	}

	/** Switches lensing in the shaders on or off without changing the hole (e.g. around gizmos drawn after the lens). */
	fun setEnabled(enabled: Boolean) {
		if (buffer == 0) return
		MemoryStack.stackPush().use { stack ->
			val data = stack.mallocFloat(1)
			data.put(0, if (enabled && hole != null) 1f else 0f)
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, buffer)
			GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 16L, data)
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0)
		}
	}

	private fun upload(hole: BlackHole?, camX: Double, camY: Double, camZ: Double, view: Quaternionf, projection: Matrix4f, enabled: Boolean) {
		MemoryStack.stackPush().use { stack ->
			val data = stack.mallocFloat(BLOCK_BYTES / 4)
			if (hole != null) {
				val at = view.transform(Vector3f((hole.x - camX).toFloat(), (hole.y - camY).toFloat(), (hole.z - camZ).toFloat()))
				data.put(at.x).put(at.y).put(at.z).put(hole.schwarzschildRadius.toFloat())
			} else {
				data.put(0f).put(0f).put(0f).put(0f)
			}
			data.put(if (enabled) 1f else 0f).put(if (skipTable) 1f else 0f).put(0f).put(0f)
			projection.get(data.position(), data)
			data.position(0).limit(BLOCK_BYTES / 4)
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, buffer)
			GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0L, data)
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0)
		}
		GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, binding, buffer)
	}

	/** Creates the buffer and, once the table is built, its texture. False until both exist. */
	private fun ensureResources(): Boolean {
		if (buffer == 0) {
			binding = GL11.glGetInteger(GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS) - 1
			buffer = GL15.glGenBuffers()
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, buffer)
			GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, BLOCK_BYTES.toLong(), GL15.GL_DYNAMIC_DRAW)
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0)
		}
		if (texture == 0) {
			val data = table?.getNow(null) ?: return false
			texture = uploadTable(data)
			table = null
			CreateRelativity.LOGGER.info("Vertex lens image table ready ({}x{}x{})", TABLE_U0, TABLE_UP, TABLE_PHI)
		}
		return true
	}

	private fun uploadTable(data: FloatArray): Int {
		val id = GL11.glGenTextures()
		val previous = GlStateManager._getActiveTexture()
		GlStateManager._activeTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT)
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, id)
		GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0)
		GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0)
		GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0)
		GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4)
		GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0)
		GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0)
		val pixels = MemoryUtil.memAllocFloat(data.size)
		try {
			pixels.put(data).flip()
			GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_RG32F, TABLE_U0, TABLE_UP, TABLE_PHI, 0, GL30.GL_RG, GL11.GL_FLOAT, pixels)
		} finally {
			MemoryUtil.memFree(pixels)
		}
		GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
		GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
		GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
		GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
		GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE)
		GlStateManager._activeTexture(previous)
		return id
	}

	/**
	 * Points each vanilla program's lens block at our binding and its table sampler at our texture unit. Programs are
	 * rebuilt on resource reloads (and by Veil), so new ids are picked up as they appear.
	 */
	private fun linkPrograms() {
		val shaders = (Minecraft.getInstance().gameRenderer as GameRendererShadersAccessor).`create_relativity$getShaders`()
		for (shader in shaders.values) {
			val program = shader.id
			if (!linkedPrograms.add(program)) continue
			val block = GL31.glGetUniformBlockIndex(program, BLOCK)
			if (block != GL31.GL_INVALID_INDEX) GL31.glUniformBlockBinding(program, block, binding)
			val sampler = GL20.glGetUniformLocation(program, SAMPLER)
			if (sampler >= 0) {
				if (GL.getCapabilities().OpenGL41) {
					GL41.glProgramUniform1i(program, sampler, TEXTURE_UNIT)
				} else {
					val current = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
					GL20.glUseProgram(program)
					GL20.glUniform1i(sampler, TEXTURE_UNIT)
					GL20.glUseProgram(current)
				}
			}
		}
	}

	/** Light bent round the hole shows sections a straight line of sight is blocked from: cave culling would drop them. */
	private fun relaxCaveCulling(relax: Boolean) {
		val minecraft = Minecraft.getInstance()
		if (relax && savedSmartCull == null) {
			savedSmartCull = minecraft.smartCull
			minecraft.smartCull = false
			minecraft.levelRenderer.needsUpdate()
		} else if (!relax && savedSmartCull != null) {
			minecraft.smartCull = savedSmartCull!!
			savedSmartCull = null
			minecraft.levelRenderer.needsUpdate()
		}
	}

	/** Frustum override: boxes near the lensing hole are always drawn. */
	@JvmStatic
	fun alwaysVisible(box: AABB): Boolean {
		if (!activeThisFrame) return false
		val hole = hole ?: return false
		val radius = minOf(RELAX_RADIUS * hole.schwarzschildRadius, 256.0)
		val dx = maxOf(box.minX - hole.x, 0.0, hole.x - box.maxX)
		val dy = maxOf(box.minY - hole.y, 0.0, hole.y - box.maxY)
		val dz = maxOf(box.minZ - hole.z, 0.0, hole.z - box.maxZ)
		return dx * dx + dy * dy + dz * dz < radius * radius
	}
}
