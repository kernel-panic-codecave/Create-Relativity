package net.kernelpanicsoft.relativity.client.lens

import com.mojang.blaze3d.platform.GlStateManager
import foundry.veil.api.client.render.VeilLevelPerspectiveRenderer
import foundry.veil.api.client.render.framebuffer.AdvancedFbo
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL30

/**
 * A 360° view, colour and depth, from the nearest black hole. Lensed rays that leave the screen (most of the
 * photon ring points back past the camera), or that would need something the camera's own view hides, are
 * traced through it. One cube face is rendered per frame, round-robin, into 3×2 atlases.
 *
 * Face layout and orientation must match `sampleEnvironment` in black_hole_lens.fsh: face `f` sits at column
 * `f % 3`, row `f / 3`, looking along [FACES]`[f]`.forward with that up; image x runs along `forward × up`.
 */
object LensEnvironment {
	const val FACE_SIZE = 256

	/** Capture projection planes; the shader linearises depth with the same values. */
	const val NEAR = 0.05f
	const val FAR = 1024f

	private class Face(val forward: Vector3f, val up: Vector3f)

	private val FACES = listOf(
		Face(Vector3f(1f, 0f, 0f), Vector3f(0f, 1f, 0f)),
		Face(Vector3f(-1f, 0f, 0f), Vector3f(0f, 1f, 0f)),
		Face(Vector3f(0f, 1f, 0f), Vector3f(0f, 0f, 1f)),
		Face(Vector3f(0f, -1f, 0f), Vector3f(0f, 0f, -1f)),
		Face(Vector3f(0f, 0f, 1f), Vector3f(0f, 1f, 0f)),
		Face(Vector3f(0f, 0f, -1f), Vector3f(0f, 1f, 0f)),
	)

	private var faceFbo: AdvancedFbo? = null

	/** GL id of the 3×2 colour atlas, or 0 before the first capture. */
	var atlasTexture = 0
		private set

	/** GL id of the matching depth atlas (raw depth-buffer values). */
	var depthAtlasTexture = 0
		private set

	/** Where the current capture was taken from, in world coordinates. */
	val captureCenter = Vector3d()

	private var nextFace = 0
	private var capturedFaces = 0
	private var capturedHole: String? = null

	/** True while a capture face is being rendered; weather is skipped then (see LevelRendererMixin). */
	@JvmStatic
	var capturing = false
		private set

	/** True once all six faces have been captured for the current hole. */
	val ready: Boolean get() = capturedFaces == 0b111111

	private val projection = Matrix4f().setPerspective((Math.PI / 2).toFloat(), 1f, NEAR, FAR)

	fun render(event: RenderLevelStageEvent) {
		if (event.stage != RenderLevelStageEvent.Stage.AFTER_LEVEL) return
		if (VeilLevelPerspectiveRenderer.isRenderingPerspective()) return
		if (!RelativityConfig.Client.Render.lensing || !RelativityConfig.Client.Render.environmentCapture) return
		val minecraft = Minecraft.getInstance()
		val level = minecraft.level ?: return
		val camera = event.camera.position
		val hole = BlackHoleLens.visibleHoles(level).minByOrNull { it.center.distanceToSqr(camera) } ?: return
		if (hole.id != capturedHole || captureCenter.x != hole.x || captureCenter.y != hole.y || captureCenter.z != hole.z) {
			capturedHole = hole.id
			capturedFaces = 0
			captureCenter.set(hole.x, hole.y, hole.z)
		}
		ensureResources()

		val face = FACES[nextFace]
		val orientation = Matrix4f().setLookAlong(face.forward, face.up).getNormalizedRotation(Quaternionf())
		capturing = true
		val drawn = try {
			VeilLevelPerspectiveRenderer.render(
				faceFbo!!, Matrix4f(), projection, Vector3d(hole.x, hole.y, hole.z), orientation,
				RelativityConfig.Client.Render.captureDistance.toFloat(), event.partialTick, false,
			)
		} finally {
			capturing = false
		}

		drawn.bindRead()
		val x = (nextFace % 3) * FACE_SIZE
		val y = (nextFace / 3) * FACE_SIZE
		GlStateManager._bindTexture(atlasTexture)
		GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, 0, 0, FACE_SIZE, FACE_SIZE)
		// Into a depth-format texture, glCopyTexSubImage2D reads the depth buffer.
		GlStateManager._bindTexture(depthAtlasTexture)
		GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, 0, 0, FACE_SIZE, FACE_SIZE)
		minecraft.mainRenderTarget.bindWrite(true)

		capturedFaces = capturedFaces or (1 shl nextFace)
		nextFace = (nextFace + 1) % FACES.size
	}

	private fun ensureResources() {
		if (faceFbo == null) {
			faceFbo = AdvancedFbo.withSize(FACE_SIZE, FACE_SIZE)
				.addColorTextureBuffer()
				.setDepthTextureBuffer()
				.setDebugLabel("Create Relativity lens environment face")
				.build(true)
		}
		if (atlasTexture == 0) {
			atlasTexture = atlas(GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, GL11.GL_LINEAR)
			// Depth is never filtered: averaging depths across an edge invents surfaces that aren't there.
			depthAtlasTexture = atlas(GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, GL11.GL_NEAREST)
		}
	}

	private fun atlas(internalFormat: Int, format: Int, type: Int, filter: Int): Int {
		val id = GlStateManager._genTexture()
		GlStateManager._bindTexture(id)
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, 3 * FACE_SIZE, 2 * FACE_SIZE, 0, format, type, null as java.nio.ByteBuffer?)
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter)
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter)
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
		return id
	}
}
