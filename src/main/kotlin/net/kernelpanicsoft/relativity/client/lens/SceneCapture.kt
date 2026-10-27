package net.kernelpanicsoft.relativity.client.lens

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import foundry.veil.api.client.render.VeilLevelPerspectiveRenderer
import foundry.veil.api.client.render.framebuffer.AdvancedFbo
import net.kernelpanicsoft.relativity.CreateRelativity
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.RenderFrameEvent
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30

/**
 * The lens's rough capture: the level round the camera, unlensed, on the six faces of a cube at low resolution, for the
 * light the vertex lens can't place (the Einstein ring's flip, higher images, the seams at tubes' and bubbles' walls):
 * the post pass follows such a pixel's light back past every source and looks it up here by where it ends heading.
 * Drawn by the level's own renderer (Veil's perspective render: translucent passes too, and with Iris the pack, which
 * runs a pipeline of its own for it), one face a frame; a face a few frames old barely shows in what's smeared that thin.
 * Faces side by side in one framebuffer, its colour [TEXTURE] and depth [DEPTH] (black_hole_lens.fsh's SceneCapture,
 * SceneCaptureDepth: the depth tells geometry from sky, which the post pass draws sharper itself), with each face's
 * view and projection ([faces]) for finding a direction in it.
 */
object SceneCapture {
	val TEXTURE = CreateRelativity.id("scene_capture")
	val DEPTH = CreateRelativity.id("scene_capture_depth")

	/** Each face's size (pixels). */
	private const val FACE = 512

	/** Each face's field of view (degrees): a little over 90, so neighbours overlap and no direction falls between. */
	private const val FOV = 92f

	/** The near plane (blocks). */
	const val NEAR = 0.05f

	/** Each face's look: +x, −x, +y, −y, +z, −z (game axes). */
	private val looks = listOf(Vector3f(1f, 0f, 0f), Vector3f(-1f, 0f, 0f), Vector3f(0f, 1f, 0f), Vector3f(0f, -1f, 0f), Vector3f(0f, 0f, 1f), Vector3f(0f, 0f, -1f))

	/** Whether to capture (debug; GameTests compare without). */
	@JvmField
	var enabled = true

	/** Each face's projection times its view's rotation (game axes in, clip out): directions only. */
	val faces = Array(6) { Matrix4f() }

	/** Which faces hold a capture yet (bit per face). */
	var ready = 0
		private set

	/** The faces' far plane (blocks). */
	var far = 64f
		private set

	/** The capture's render distance at most (chunks). */
	private const val MAX_CHUNKS = 8

	/** A face is drawn again once the camera's this far from where it was drawn (blocks), or it's this old (frames). */
	private const val MOVED = 0.25
	private const val STALE = 60

	/** Where each face was drawn from, and the frame it was. */
	private val drawnAt = Array(6) { Vector3d(Double.NaN) }
	private val drawnFrame = IntArray(6)
	private var frame = 0

	/** The last face drawn's time (ms, the CPU's: the level renderer's own work, waited for on the GPU's too). */
	@JvmField
	var lastMillis = 0.0

	private var next = 0
	private var face: AdvancedFbo? = null
	private var atlas: AdvancedFbo? = null

	/** Whether captures are wanted this frame: the field lens, on. */
	private fun wanted(): Boolean = enabled && VertexLens.fieldSky && VertexLens.activeThisFrame

	private fun ensure() {
		if (atlas != null) return
		val made = AdvancedFbo.withSize(3 * FACE, 2 * FACE).addColorTextureBuffer().setDepthTextureBuffer().setDebugLabel("Scene capture").build(true)
		made.getColorTextureAttachment(0).setFilter(true, false)
		// (Registered by name: the post program binds them so.)
		val textures = Minecraft.getInstance().textureManager
		textures.register(TEXTURE, made.getColorTextureAttachment(0))
		textures.register(DEPTH, made.depthTextureAttachment!!)
		atlas = made
		face = AdvancedFbo.withSize(FACE, FACE).addColorTextureBuffer().setDepthTextureBuffer().setDebugLabel("Scene capture face").build(true)
	}

	/** Before each frame: the next face, if the lens wants captures. (The atlas is made at once: the post program finds it by name.) */
	fun onRenderFrame(event: RenderFrameEvent.Pre) {
		val minecraft = Minecraft.getInstance()
		// (Only in a world fully there: the level renderer's listeners (Create's track outline) want the player and game mode.)
		if (minecraft.level == null || minecraft.player == null || minecraft.gameMode == null) return
		RenderSystem.assertOnRenderThread()
		ensure()
		if (!wanted()) {
			ready = 0
			return
		}
		frame++
		val camera = minecraft.gameRenderer.mainCamera.position
		// (Turning doesn't call for any: the faces hold every direction. The face most out of date, if any is.)
		val k = (0 until 6).map { (next + it) % 6 }.firstOrNull { f ->
			ready and (1 shl f) == 0 || drawnAt[f].distance(camera.x, camera.y, camera.z) > MOVED || frame - drawnFrame[f] > STALE
		} ?: return
		next = (k + 1) % 6
		val started = System.nanoTime()
		val look = looks[k]
		// The face's camera: looking along its axis (any roll: the post pass finds directions by its matrix).
		val orientation = Quaternionf().rotationTo(Vector3f(0f, 0f, -1f), look)
		val view = Matrix4f().rotation(Quaternionf(orientation).conjugate())
		far = maxOf(MAX_CHUNKS * 16f * 2f, 64f)
		val projection = Matrix4f().perspective(Math.toRadians(FOV.toDouble()).toFloat(), 1f, NEAR, far)
		// (Veil turns its model-view by the orientation (camera to world) after: this undoes that into the view.)
		val modelView = Matrix4f(view).rotate(Quaternionf(orientation).conjugate())
		val rendered = VeilLevelPerspectiveRenderer.render(
			face!!, modelView, projection, Vector3d(camera.x, camera.y, camera.z), orientation,
			minOf(minecraft.options.effectiveRenderDistance, MAX_CHUNKS).toFloat(), minecraft.timer, false,
		)
		// Into its cell of the atlas, depth too.
		val x = (k % 3) * FACE
		val y = (k / 3) * FACE
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, rendered.id)
		GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, atlas!!.id)
		GL30.glBlitFramebuffer(0, 0, FACE, FACE, x, y, x + FACE, y + FACE, GL11.GL_COLOR_BUFFER_BIT or GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST)
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
		minecraft.mainRenderTarget.bindWrite(true)
		faces[k].set(projection).mul(view)
		ready = ready or (1 shl k)
		drawnAt[k].set(camera.x, camera.y, camera.z)
		drawnFrame[k] = frame
		lastMillis = (System.nanoTime() - started) / 1e6
	}

	/** The faces' matrices for the post pass (SceneCaptureFaces: four columns each, game axes). */
	fun columns(): Array<Vector4f> = Array(24) { i -> faces[i / 4].getColumn(i % 4, Vector4f()) }
}
