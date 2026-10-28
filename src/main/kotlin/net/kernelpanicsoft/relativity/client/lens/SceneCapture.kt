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
 * The lens's captures: the level, unlensed, on the six faces of a cube round each of a few viewpoints: the camera's
 * own, and the nearest holes' (light near a hole sees what's round it from there: places a pillar hides from the camera).
 * The post pass follows each pixel's light inside the Einstein rings and looks up what it meets in the capture of the
 * viewpoint nearest where it is (black_hole_lens.fsh's sceneCaptureMarch). Built to be general: a wormhole's far mouth
 * is a viewpoint too.
 * Drawn by the level's own renderer (Veil's perspective render: translucent passes too, and with Iris the pack, which
 * runs a pipeline of its own for it): a camera face a frame when it moves, a hole's face now and then (what's round a hole
 * doesn't change as the camera moves). All in one framebuffer, colour [TEXTURE] and depth [DEPTH]: the camera's faces
 * ([CAMERA_FACE] pixels) in the bottom two thirds, the holes' ([HOLE_FACE]) side by side above; each view's faces'
 * matrices, origin and place in it for the post pass ([columns], [origins], [cells]).
 */
object SceneCapture {
	val TEXTURE = CreateRelativity.id("scene_capture")
	val DEPTH = CreateRelativity.id("scene_capture_depth")

	/** The faces' sizes (pixels): the camera's, a hole's. */
	private const val CAMERA_FACE = 1024
	private const val HOLE_FACE = 512

	/** Views: the camera's and this many holes' (black_hole_lens.fsh's SCENE_CAPTURE_VIEWS). */
	const val HOLES = 2
	const val VIEWS = 1 + HOLES

	/** Each face's field of view (degrees): a little over 90, so neighbours overlap and no direction falls between. */
	private const val FOV = 92f

	/** The near plane (blocks). */
	const val NEAR = 0.05f

	/** Each face's look: +x, −x, +y, −y, +z, −z (game axes). */
	private val looks = listOf(Vector3f(1f, 0f, 0f), Vector3f(-1f, 0f, 0f), Vector3f(0f, 1f, 0f), Vector3f(0f, -1f, 0f), Vector3f(0f, 0f, 1f), Vector3f(0f, 0f, -1f))

	/** Whether to capture (debug; GameTests compare without). */
	@JvmField
	var enabled = true

	/** A viewpoint's capture: where it's drawn from, each face's matrix (projection times view rotation, game axes in), when. */
	private class View(val face: Int, val blockX: Int, val blockY: Int) {
		val origin = Vector3d(Double.NaN)
		val faces = Array(6) { Matrix4f() }
		val drawnAt = Array(6) { Vector3d(Double.NaN) }
		val drawnFrame = IntArray(6)
		var ready = 0
		var next = 0
		var active = false
	}

	private val views = Array(VIEWS) { v ->
		if (v == 0) View(CAMERA_FACE, 0, 0) else View(HOLE_FACE, (v - 1) * 3 * HOLE_FACE, 2 * CAMERA_FACE)
	}

	/** Which faces hold a capture (6 bits a view, the camera's lowest). */
	val ready: Int get() = views.withIndex().sumOf { (v, view) -> if (view.active) view.ready shl (6 * v) else 0 }

	/** The faces' far plane (blocks). */
	var far = 64f
		private set

	/** The capture's render distance at most (chunks). */
	private const val MAX_CHUNKS = 8

	/** A face is drawn again once its viewpoint's this far from where it was drawn (blocks), or it's this old (frames). */
	private const val MOVED = 0.25
	private const val STALE = 60

	private var frame = 0

	/** The last face drawn's time (ms, the CPU's: the level renderer's own work, waited for on the GPU's too). */
	@JvmField
	var lastMillis = 0.0

	private var cameraFace: AdvancedFbo? = null
	private var holeFace: AdvancedFbo? = null
	private var atlas: AdvancedFbo? = null

	/** Whether captures are wanted this frame: the field lens, on. */
	private fun wanted(): Boolean = enabled && VertexLens.fieldSky && VertexLens.activeThisFrame

	private fun ensure() {
		if (atlas != null) return
		val made = AdvancedFbo.withSize(3 * CAMERA_FACE, 2 * CAMERA_FACE + 2 * HOLE_FACE).addColorTextureBuffer().setDepthTextureBuffer().setDebugLabel("Scene capture").build(true)
		made.getColorTextureAttachment(0).setFilter(true, false)
		// (Registered by name: the post program binds them so.)
		val textures = Minecraft.getInstance().textureManager
		textures.register(TEXTURE, made.getColorTextureAttachment(0))
		textures.register(DEPTH, made.depthTextureAttachment!!)
		atlas = made
		cameraFace = AdvancedFbo.withSize(CAMERA_FACE, CAMERA_FACE).addColorTextureBuffer().setDepthTextureBuffer().setDebugLabel("Scene capture face").build(true)
		holeFace = AdvancedFbo.withSize(HOLE_FACE, HOLE_FACE).addColorTextureBuffer().setDepthTextureBuffer().setDebugLabel("Scene capture hole face").build(true)
	}

	/** Before each frame: a camera face if it's moved, a hole's face if one's due. (The atlas is made at once: the post program finds it by name.) */
	fun onRenderFrame(event: RenderFrameEvent.Pre) {
		val minecraft = Minecraft.getInstance()
		// (Only in a world fully there: the level renderer's listeners (Create's track outline) want the player and game mode.)
		val level = minecraft.level ?: return
		if (minecraft.player == null || minecraft.gameMode == null) return
		RenderSystem.assertOnRenderThread()
		ensure()
		if (!wanted()) {
			for (view in views) view.ready = 0
			return
		}
		frame++
		val camera = minecraft.gameRenderer.mainCamera.position
		views[0].origin.set(camera.x, camera.y, camera.z)
		views[0].active = true
		val holes = net.kernelpanicsoft.relativity.spacetime.SpacetimeField.blackHoles(level).sortedBy { it.center.distanceToSqr(camera) }.take(HOLES)
		for (v in 1 until VIEWS) {
			val hole = holes.getOrNull(v - 1)
			views[v].active = hole != null
			if (hole != null) views[v].origin.set(hole.x, hole.y, hole.z) else views[v].ready = 0
		}
		far = maxOf(MAX_CHUNKS * 16f * 2f, 64f)
		val started = System.nanoTime()
		drawDue(views[0], cameraFace!!, minecraft)
		// (One hole's face a frame at most: what's round a hole changes only with the level.)
		views.drop(1).filter { it.active }.firstOrNull { due(it) >= 0 }?.let { drawDue(it, holeFace!!, minecraft) }
		lastMillis = (System.nanoTime() - started) / 1e6
	}

	/** The face of [view] most out of date, if any is: not drawn, its viewpoint moved, or stale. */
	private fun due(view: View): Int = (0 until 6).map { (view.next + it) % 6 }.firstOrNull { f ->
		view.ready and (1 shl f) == 0 || view.drawnAt[f].distance(view.origin) > MOVED || frame - view.drawnFrame[f] > STALE
	} ?: -1

	private fun drawDue(view: View, fbo: AdvancedFbo, minecraft: Minecraft) {
		val k = due(view)
		if (k < 0) return
		view.next = (k + 1) % 6
		val look = looks[k]
		// The face's camera: looking along its axis (any roll: the post pass finds directions by its matrix).
		val orientation = Quaternionf().rotationTo(Vector3f(0f, 0f, -1f), look)
		val viewMatrix = Matrix4f().rotation(Quaternionf(orientation).conjugate())
		val projection = Matrix4f().perspective(Math.toRadians(FOV.toDouble()).toFloat(), 1f, NEAR, far)
		// (Veil turns its model-view by the orientation (camera to world) after: this undoes that into the view.)
		val modelView = Matrix4f(viewMatrix).rotate(Quaternionf(orientation).conjugate())
		val rendered = VeilLevelPerspectiveRenderer.render(
			fbo, modelView, projection, Vector3d(view.origin), orientation,
			minOf(minecraft.options.effectiveRenderDistance, MAX_CHUNKS).toFloat(), minecraft.timer, false,
		)
		// Into its cell of the atlas, depth too.
		val x = view.blockX + (k % 3) * view.face
		val y = view.blockY + (k / 3) * view.face
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, rendered.id)
		GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, atlas!!.id)
		GL30.glBlitFramebuffer(0, 0, view.face, view.face, x, y, x + view.face, y + view.face, GL11.GL_COLOR_BUFFER_BIT or GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST)
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
		minecraft.mainRenderTarget.bindWrite(true)
		view.faces[k].set(projection).mul(viewMatrix)
		view.ready = view.ready or (1 shl k)
		view.drawnAt[k].set(view.origin)
		view.drawnFrame[k] = frame
	}

	/** Every view's faces' matrices for the post pass (SceneCaptureFaces: four columns a face, game axes). */
	fun columns(): Array<Vector4f> = Array(24 * VIEWS) { i -> views[i / 24].faces[(i / 4) % 6].getColumn(i % 4, Vector4f()) }

	/** Each view's origin relative to [camera] (game axes; w 1 if it's in use). */
	fun origins(camera: net.minecraft.world.phys.Vec3): Array<Vector4f> = Array(VIEWS) { v ->
		val o = views[v].origin
		if (!views[v].active) Vector4f() else Vector4f((o.x - camera.x).toFloat(), (o.y - camera.y).toFloat(), (o.z - camera.z).toFloat(), 1f)
	}

	/** Each view's faces' place in the atlas (texture coordinates: corner, and the size of its three by two faces). */
	fun cells(): Array<Vector4f> {
		val w = 3f * CAMERA_FACE
		val h = 2f * CAMERA_FACE + 2f * HOLE_FACE
		return Array(VIEWS) { v -> views[v].let { Vector4f(it.blockX / w, it.blockY / h, 3f * it.face / w, 2f * it.face / h) } }
	}
}
