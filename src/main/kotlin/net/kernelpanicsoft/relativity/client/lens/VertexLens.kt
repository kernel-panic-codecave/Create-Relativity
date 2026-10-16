package net.kernelpanicsoft.relativity.client.lens

import com.mojang.blaze3d.platform.GlStateManager
import foundry.veil.api.client.render.VeilLevelPerspectiveRenderer
import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.config.RelativityConfig
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
 * The black-hole lens: world geometry is lensed in the vertex shader (vertex_lens_core.glsl), injected into
 * vanilla's world shaders and Sodium's terrain shader through Veil, into Flywheel's (CompilationMixin) and into
 * shader packs' (TransformPatcherMixin). Each vertex moves to where its light appears, at its true distance, so depth
 * testing handles occlusion.
 * This feeds those shaders: a uniform block (the nearest lensing hole, the level's projection, the view's rotation)
 * and two textures: the Schwarzschild inverse image table ([SchwarzschildImages.forFiltering]) and, for a spinning
 * hole, this frame's Kerr path table ([KerrPaths]), which the shader inverts by Newton's method starting from the
 * Schwarzschild image. The post pass ([BlackHoleLens]) then draws the shadow, the disk and the bent sky.
 */
object VertexLens {
	private const val TABLE_U0 = 64
	private const val TABLE_UP = 64
	private const val TABLE_PHI = 128
	/** The table's texture unit: the last one, clear of what vanilla, Flywheel and shader packs use. */
	private val TEXTURE_UNIT: Int by lazy { GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS) - 1 }
	private const val BLOCK = "RelativityLens"
	private const val SAMPLER = "RelativityImages"
	private const val BLOCK_BYTES = 448 // 28 vec4s: see vertex_lens_core.glsl's RelativityLensData
	private const val KERR_SAMPLER = "RelativityKerrPaths"

	/** The Kerr image map's sampler (KerrImageMap), on the unit below the path table's. */
	private const val KERR_MAP_SAMPLER = "RelativityKerrMap"

	/** kerr.glsl's KERR_START: the least the table's paths reach from the hole (r_s). */
	private const val KERR_START = 80.0

	/** How far the path table's paths reach from a centre of [scale]: past everything drawn (the render distance round the camera). */
	private fun pathRadius(scale: Double, cameraDistance: Double): Double =
		maxOf(KERR_START * scale, cameraDistance + 1.5 * Minecraft.getInstance().gameRenderer.renderDistance)

	/**
	 * What the path table and image map are centred on: the nearest hole ([hole] set), or with none in view the
	 * strongest other source. [scale] sets the table's knee (r_s of a hole), [inner] the image map's inner radius.
	 */
	private class Centre(val x: Double, val y: Double, val z: Double, val scale: Double, val inner: Double, val hole: BlackHole?, val key: Any)

	private fun centreOf(hole: BlackHole?, others: List<net.kernelpanicsoft.relativity.spacetime.FieldSource>, camera: net.minecraft.world.phys.Vec3): Centre? {
		if (hole != null) {
			val a = hole.spin * hole.mass
			val horizon = hole.mass + kotlin.math.sqrt(maxOf(hole.mass * hole.mass - a * a, 0.0))
			return Centre(hole.x, hole.y, hole.z, hole.schwarzschildRadius, 1.05 * horizon, hole, hole.id)
		}
		val at = org.joml.Vector3d(camera.x, camera.y, camera.z)
		val strongest = others.maxByOrNull { source ->
			when (source) {
				is net.kernelpanicsoft.relativity.spacetime.FieldSource.Mass -> 2.0 * abs(source.mass) / maxOf(source.center.distance(at), 1.0)
				is net.kernelpanicsoft.relativity.spacetime.FieldSource.Bubble -> source.speed * source.radius / maxOf(source.center.distance(at), 1.0)
			}
		} ?: return null
		return when (strongest) {
			is net.kernelpanicsoft.relativity.spacetime.FieldSource.Mass -> {
				val c = strongest.center
				Centre(c.x(), c.y(), c.z(), maxOf(2.0 * abs(strongest.mass), strongest.softening / 3.0), maxOf(0.25 * strongest.softening, 0.05), null, strongest)
			}
			is net.kernelpanicsoft.relativity.spacetime.FieldSource.Bubble -> {
				val c = strongest.center
				Centre(c.x(), c.y(), c.z(), strongest.radius / 10.0, 0.05, null, strongest.javaClass)
			}
		}
	}

	/** Spin from which the hole is treated as Kerr (kerr.glsl's MIN_KERR_SPIN): images found on [KerrPaths]' table. */
	private const val MIN_KERR_SPIN = 0.05

	/** Boxes within this many r_s of the hole skip frustum culling: bent light can show them from anywhere. */
	private const val RELAX_RADIUS = 60.0

	private var table: CompletableFuture<FloatArray>? = null
	/** The secondary-image table (SchwarzschildImages.buildSecondaryTable), and its texture once built. */
	private var secondTable: CompletableFuture<FloatArray>? = null

	/** The image tables as built (SchwarzschildImages.sample / sampleSecondary read them), for the CPU (DiskMesh). */
	@Volatile
	var primaryImages: FloatArray? = null
		private set
	@Volatile
	var secondaryImages: FloatArray? = null
		private set

	/** The tables' sizes (u0, uP, phi), for those lookups. */
	const val IMAGES_U0 = TABLE_U0
	const val IMAGES_UP = TABLE_UP
	const val IMAGES_PHI = TABLE_PHI
	var secondTexture = 0
		private set
	private const val SECOND_SAMPLER = "RelativitySecondImages"
	/** The Schwarzschild image table (GL_TEXTURE_3D); 0 until built. */
	var texture = 0
		private set
	private var buffer = 0
	private var binding = -1
	/** Shaders' programs as last linked (Veil recompiles programs in place, so ids change under a shader). */
	private val linkedPrograms = java.util.WeakHashMap<net.minecraft.client.renderer.ShaderInstance, Int>()

	/** The hole lensing this frame (world position), or null. */
	@Volatile private var hole: BlackHole? = null

	/** What the lens is centred on this frame, or null. */
	@Volatile private var lensCentre: Centre? = null
	private var savedSmartCull: Boolean? = null

	/** Whether this frame's lensing hole is the only source in view (HorizonMesh's outline and the shader's weak near
	 * march follow its light alone). */
	var loneHole = false
		private set

	/** True while vertex lensing is on for this frame's level render (the post pass then skips geometry). */
	var activeThisFrame = false
		private set

	fun init() {
		table = CompletableFuture.supplyAsync {
			val raw = SchwarzschildImages.buildTable(TABLE_U0, TABLE_UP, TABLE_PHI)
			primaryImages = raw
			SchwarzschildImages.forFiltering(raw, TABLE_U0, TABLE_UP, TABLE_PHI)
		}
		secondTable = CompletableFuture.supplyAsync {
			val raw = SchwarzschildImages.buildSecondaryTable(TABLE_U0, TABLE_UP, TABLE_PHI)
			secondaryImages = raw
			SchwarzschildImages.forFiltering(raw, TABLE_U0, TABLE_UP, TABLE_PHI)
		}
		NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent::class.java, ::onRenderStage)
	}

	/** Debug: draws every vertex at its flat-space direction, without the image table (GameTests). */
	@JvmField
	var skipTable = false

	/** The world shaders' pixel check (vertex_lens_core.glsl's relativityPixelWrong); off for comparison (debug; GameTests). */
	@JvmField
	var pixelCheck = true

	/** Bends a spinning hole as if it didn't spin (debug; GameTests). */
	@JvmField
	var kerrOff = false

	/** Draws the Kerr path table but leaves the vertices' Kerr solve off (debug; GameTests: timing). */
	@JvmField
	var kerrSolveOff = false

	/**
	 * Debug (GameTests): 1 hides vertices a spinning hole's path table didn't place; 2 those it placed on paths going
	 * out, 3 coming in; 0 none.
	 */
	@JvmField
	var kerrDebug = 0

	/** Turns Flywheel's visualization off, so its blocks draw through vanilla renderers (GameTests compare the two). */
	@JvmField
	@Volatile
	var flywheelOff = false

	/** Debug (GameTests): the vertex lens stands down, so the post pass draws all geometry itself (its reference). */
	@JvmField
	var off = false

	private val wanted: Boolean
		get() = RelativityConfig.Client.Render.lensing && !off

	private fun onRenderStage(event: RenderLevelStageEvent) {
		when (event.stage) {
			RenderLevelStageEvent.Stage.AFTER_SKY -> beginLevel(event.projectionMatrix)
			RenderLevelStageEvent.Stage.AFTER_ENTITIES -> drawSelfSecondImage(event.partialTick.getGameTimeDeltaPartialTick(false))
			RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS -> if (activeThisFrame) {
				HorizonMesh.draw(hole, event.modelViewMatrix)
				DiskMesh.draw(hole, event.modelViewMatrix)
			}
			RenderLevelStageEvent.Stage.AFTER_LEVEL -> {
				setEnabled(false)
				secondsWanted = false
			}
			else -> Unit
		}
	}

	/** Before terrain: pick the hole, upload the block, bind the table, link any new programs. */
	/** The level's projection last frame (debug; GameTests). */
	@JvmField
	var lastProjection = Matrix4f()

	/** GPU time of the Kerr path pass and the image map pass (debug; GameTests, with BlackHoleLens.timeGpu). */
	val pathsTimer = GpuTimer()
	val mapTimer = GpuTimer()

	/** Camera movement relative to the hole in one frame past which the Kerr tables are redrawn whole (blocks). */
	private const val JUMP_BLOCKS = 0.5f
	private val lastHoleGame = Vector3f(Float.MAX_VALUE)
	private val sources = Array(3 * BlackHoleLens.MAX_SOURCES) { org.joml.Vector4f() }
	private var jump = false

	private var lastSlotted = false
	private var virtualBuffer = 0

	/** The hole the Kerr image map was last drawn for (a new one starts it over). */
	private var mapHole: Any? = null

	private fun beginLevel(projection: Matrix4f) {
		if (!VeilLevelPerspectiveRenderer.isRenderingPerspective()) lastProjection = Matrix4f(projection)
		if (!ensureResources()) return
		val minecraft = Minecraft.getInstance()
		val level = minecraft.level
		// Perspective renders (Veil level views, such as other mods' cameras) draw the level too, and stay unlensed.
		val perspective = VeilLevelPerspectiveRenderer.isRenderingPerspective()
		val camera = minecraft.gameRenderer.mainCamera.position
		val holes = if (wanted && level != null && !perspective) BlackHoleLens.visibleHoles(level).sortedBy { it.center.distanceToSqr(camera) } else emptyList()
		val others = if (wanted && level != null && !perspective) BlackHoleLens.visibleSources(level) else emptyList()
		val nearest = holes.firstOrNull()
		val centre = centreOf(nearest, others, camera)
		if (!perspective) {
			hole = nearest
			lensCentre = centre
			activeThisFrame = centre != null
			relaxCaveCulling(centre != null)
		}
		// The level's view rotation (as GameRenderer builds it): the shader works in view space.
		val view = Quaternionf(minecraft.gameRenderer.mainCamera.rotation()).conjugate()
		// A spinning hole or anything but holes: this frame's path table through every source, which the image map
		// inverts. (Non-spinning holes alone keep the Schwarzschild image table.)
		val (count, general) = BlackHoleLens.packSources(holes, others, camera, sources)
		// A lone spinning hole: the live map from baked camera slots when they're ready (KerrSlots), no solving.
		val lone = centre?.hole
		val loneKerr = lone != null && holes.size == 1 && others.isEmpty() && abs(lone.spin) >= MIN_KERR_SPIN && !kerrOff
		// (Or approximated from the non-spinning lens in the shaders (kerrApprox): nothing to solve or bake at all.)
		val approximated = loneKerr && kerrApprox
		val slotted = !perspective && loneKerr && !approximated && !kerrSolveOff && KerrSlots.update(lone!!, camera)
		var kerr = approximated
		if (centre != null && general && !slotted && !approximated && !(kerrOff && others.isEmpty())) {
			val holeGame = Vector3f((centre.x - camera.x).toFloat(), (centre.y - camera.y).toFloat(), (centre.z - camera.z).toFloat())
			// A jump (a teleport, a fast flight) redraws the tables whole; walking catches them up a slice a frame.
			jump = lastHoleGame.distance(holeGame) > JUMP_BLOCKS
			lastHoleGame.set(holeGame)
			if (BlackHoleLens.timeGpu) pathsTimer.begin()
			kerr = KerrPaths.draw(
				holeGame, centre.scale.toFloat(), pathRadius(centre.scale, holeGame.length().toDouble()).toFloat(),
				sources, count, jump,
			)
			if (BlackHoleLens.timeGpu) pathsTimer.end()
		}
		if (slotted) kerr = true
		if (!perspective) loneHole = holes.size == 1 && others.isEmpty()
		upload(centre, camera.x, camera.y, camera.z, view, projection, centre != null, kerr && !kerrSolveOff, if (slotted) KerrSlots.outer(lone!!) else null)

		val previous = GlStateManager._getActiveTexture()
		GlStateManager._activeTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT)
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture)
		GlStateManager._activeTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT - 1)
		GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, KerrPaths.texture)
		GlStateManager._activeTexture(previous)
		// The image map the vertices read, solved on the path table (the block and the table bound for it).
		KerrImageMap.ensureTexture()
		if (!perspective) {
			// (Switching between the blend and solving: the blend's grid reaches further, so the solver starts over.)
			if (!kerr || centre?.key != mapHole || slotted != lastSlotted) {
				KerrImageMap.invalidate()
				KerrSecondImageMap.invalidate()
			}
			mapHole = centre?.key
			lastSlotted = slotted
			if (kerr && !kerrSolveOff && !slotted && !approximated) {
				if (BlackHoleLens.timeGpu) mapTimer.begin()
				KerrImageMap.draw(KerrPaths.changed, jump, sources, count)
				// The secondary images too, for the second-image pass (a lone hole: they follow its light alone).
				if (secondImages && secondTexture != 0 && loneHole) KerrSecondImageMap.draw(KerrPaths.changed, jump, sources, count)
				if (BlackHoleLens.timeGpu) mapTimer.end()
			}
		}
		GlStateManager._activeTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT - 2)
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, KerrImageMap.texture)
		GlStateManager._activeTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT - 3)
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, secondTexture)
		GlStateManager._activeTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT - 4)
		val perFrameSecond = kerr && !kerrSolveOff && !slotted && !approximated && loneHole
		KerrSecondImageMap.ensureTexture()
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, if (perFrameSecond) KerrSecondImageMap.texture else KerrSlots.secondTexture)
		GlStateManager._activeTexture(previous)
		// Round a spinning hole: its secondary map solved per frame, or the slots' blended one.
		secondsWanted = !perspective && centre?.hole != null && secondTexture != 0 && secondImages && (!kerr || approximated || perFrameSecond || (slotted && KerrSlots.secondBlending))
	}

	/**
	 * A lone spinning hole's images approximated from the non-spinning one (vertex_lens_core.glsl relativityKerrApprox:
	 * displaced, turned by frame dragging), not solved: no per-frame Kerr tables, no lag. Off: the per-frame solver.
	 */
	@JvmField
	var kerrApprox = true

	/** With [kerrApprox]: images by the ring refined to the exact ones in closed form (relativityKerrExactImage). */
	@JvmField
	var kerrExact = false

	/** The exact solve's reach (shadow radii) and evaluations per vertex ([7].z, w while approximated). */
	@JvmField
	var exactReach = 1.6f
	@JvmField
	var exactPasses = 24

	/** Draws the secondary images (the light the long way round the hole, by the Einstein ring) of what's drawn. */
	@JvmField
	var secondImages = true


	/** Whether this frame's level draws its secondary images ([drawSecondImages]): a non-spinning hole (for now). */
	private var secondsWanted = false

	/**
	 * Whether an immediate draw (vanilla's batched entities, block entities, particles: BufferUploader) made with
	 * [projection] should be drawn again at its secondary images: while the level is drawn with them, and only for the
	 * level's own projection (not the hand, GUI items, other views; those would be drawn twice on top of themselves).
	 */
	@JvmStatic
	fun wantsImmediateSecondImages(projection: Matrix4f): Boolean =
		secondsWanted && !drawingSecondImages && !ownSecondImages && projection.equals(lastProjection, 1e-6f)

	/** While set, immediate draws aren't repeated for their secondary images: the drawer does that itself (DiskMesh,
	 * whose secondary images are coloured apart). */
	@JvmField
	var ownSecondImages = false

	/** Whether this frame draws secondary images at all. */
	val secondImagesThisFrame: Boolean get() = secondsWanted

	/** The player's own body drawn for its secondary image (its own buffers: flushed in that mode only). */
	private val selfBuffers by lazy { net.minecraft.client.renderer.MultiBufferSource.immediate(com.mojang.blaze3d.vertex.ByteBufferBuilder(1 shl 16)) }

	/**
	 * In first person the game doesn't draw the player at all: here it's drawn for its secondary image alone, the light
	 * that leaves it and comes round the hole back to the eye (by the photon sphere, looking along the orbit: the back of
	 * your own head). Its primary image (inside the camera) isn't drawn.
	 */
	private fun drawSelfSecondImage(partialTick: Float) {
		if (!secondsWanted || !selfImage) return
		val minecraft = Minecraft.getInstance()
		val camera = minecraft.gameRenderer.mainCamera
		val player = minecraft.player ?: return
		if (camera.isDetached || camera.entity !== player || player.isSpectator) return
		val dispatcher = minecraft.entityRenderDispatcher
		val x = net.minecraft.util.Mth.lerp(partialTick.toDouble(), player.xOld, player.x) - camera.position.x
		val y = net.minecraft.util.Mth.lerp(partialTick.toDouble(), player.yOld, player.y) - camera.position.y
		val z = net.minecraft.util.Mth.lerp(partialTick.toDouble(), player.zOld, player.z) - camera.position.z
		val yaw = net.minecraft.util.Mth.lerp(partialTick, player.yRotO, player.yRot)
		val light = dispatcher.getPackedLightCoords(player, partialTick)
		// (All of it in that mode: a batch is drawn as soon as the model moves on to another render type, mid-render.)
		drawSecondImages {
			dispatcher.render(player, x, y, z, yaw, partialTick, com.mojang.blaze3d.vertex.PoseStack(), selfBuffers, light)
			selfBuffers.endBatch()
		}
	}

	/**
	 * Draws the player's own secondary image in first person ([drawSelfSecondImage]). Off: it's the famous "back of your
	 * own head" by the photon sphere, but light that loops a full turn is squeezed about e^(2 pi) times, so a head's
	 * image is a ring round the shadow a hundredth of a pixel thick, wherever the camera is (measured, selfImage test).
	 */
	@JvmField
	var selfImage = false

	/** True while a draw places vertices at their secondary images. */
	@JvmStatic
	var drawingSecondImages = false
		private set

	/**
	 * Runs [draw] again with the vertices placed at their secondary images (vertex_lens_core.glsl's second-image mode):
	 * the block's [11].y gets its bit 2 for the draw, then loses it. Nothing if this frame has none to draw.
	 */
	@JvmStatic
	fun drawSecondImages(draw: Runnable) {
		if (!secondsWanted || drawingSecondImages) return
		drawingSecondImages = true
		try {
			setModeFlags(true)
			// A secondary image is the mirror image (the light comes round the other side), so every triangle's winding
			// is reversed: culling the front faces keeps the ones facing the camera along that light.
			GL11.glCullFace(GL11.GL_FRONT)
			draw.run()
		} finally {
			GL11.glCullFace(GL11.GL_BACK)
			setModeFlags(false)
			drawingSecondImages = false
		}
	}

	/** The near-axis cut (blocks) for draws whose shaders carry the pixel check (vertex_lens_core.glsl's relativityAxisCut). */
	const val CHECKED_AXIS_CUT = 0.01
	/** The same for draws without it (Flywheel's): vertex_lens_core.glsl's RELATIVITY_AXIS_CUT. */
	const val UNCHECKED_AXIS_CUT = 2.0

	/** The block's [11].y: bit 1 the pixel check off (debug), bit 2 the secondary images, the rest the axis cut. */
	private fun modeFlags(second: Boolean, axisCut: Double): Float =
		(if (pixelCheck) 0f else 1f) + (if (second) 2f else 0f) + 4f * (Math.round(axisCut * 100.0).toFloat() + if (fineCut) 1000f else 0f)

	/** Draws without the pixel check (Flywheel's) take the full near-axis cut while [on]; then the default again. */
	@JvmStatic
	fun uncheckedAxisCut(on: Boolean) {
		if (buffer == 0 || !activeThisFrame) return
		uncheckedCut = on
		setModeFlags(drawingSecondImages)
	}
	private var uncheckedCut = false

	/** Draws whose geometry is fine and carry the pixel check take the fine cut even round a spinning hole while [on]. */
	@JvmStatic
	fun fineAxisCut(on: Boolean) {
		if (buffer == 0 || !activeThisFrame) return
		fineCut = on
		setModeFlags(drawingSecondImages)
	}
	private var fineCut = false

	private fun setModeFlags(second: Boolean, axisCut: Double = if (uncheckedCut) UNCHECKED_AXIS_CUT else CHECKED_AXIS_CUT) {
		val value = modeFlags(second, axisCut)
		MemoryStack.stackPush().use { stack ->
			val data = stack.mallocFloat(1).put(0, value)
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, buffer)
			GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 11L * 16 + 4, data)
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0)
		}
	}

	/** The angle one screen pixel covers through [projection] (radians, vertically). */
	private fun pixelAngle(projection: Matrix4f): Float =
		(2.0 / (projection.m11() * Minecraft.getInstance().window.height)).toFloat()

	/**
	 * The block for a virtual camera at [cameraRel] from [hole] (game axes, blocks), looking along no particular way (the
	 * tables don't depend on it): for the slot baker ([KerrSlots]), with the image map reaching [mapOuter]. The live
	 * block is uploaded again after, by the frame's own [beginLevel].
	 */
	internal fun uploadVirtual(hole: BlackHole, cameraRel: org.joml.Vector3dc, mapOuter: Double) {
		val centre = centreOf(hole, emptyList(), hole.center) ?: return
		// Its own buffer: rewriting the live one while the GPU may still read it would stall.
		if (virtualBuffer == 0) {
			virtualBuffer = GL15.glGenBuffers()
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, virtualBuffer)
			GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, BLOCK_BYTES.toLong(), GL15.GL_DYNAMIC_DRAW)
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0)
		}
		upload(centre, hole.x + cameraRel.x(), hole.y + cameraRel.y(), hole.z + cameraRel.z(), Quaternionf(), lastProjection, false, true, mapOuter, virtualBuffer)
	}

	/** Binds [texture] as the path table the image map program reads (the slot baker's own, while it bakes). */
	internal fun bindPathTable(texture: Int) {
		val previous = GlStateManager._getActiveTexture()
		GlStateManager._activeTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT - 1)
		GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texture)
		GlStateManager._activeTexture(previous)
	}

	/** Switches lensing in the shaders on or off without changing the hole (e.g. around gizmos drawn after the lens). */
	fun setEnabled(enabled: Boolean) {
		if (buffer == 0) return
		MemoryStack.stackPush().use { stack ->
			val data = stack.mallocFloat(1)
			data.put(0, if (enabled && lensCentre != null) 1f else 0f)
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, buffer)
			GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 16L, data)
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0)
		}
	}

	private fun upload(centre: Centre?, camX: Double, camY: Double, camZ: Double, view: Quaternionf, projection: Matrix4f, enabled: Boolean, kerr: Boolean, mapOuter: Double? = null, target: Int = buffer) {
		MemoryStack.stackPush().use { stack ->
			val data = stack.mallocFloat(BLOCK_BYTES / 4)
			if (centre != null) {
				val at = view.transform(Vector3f((centre.x - camX).toFloat(), (centre.y - camY).toFloat(), (centre.z - camZ).toFloat()))
				data.put(at.x).put(at.y).put(at.z).put(centre.scale.toFloat())
			} else {
				data.put(0f).put(0f).put(0f).put(0f)
			}
			data.put(if (enabled) 1f else 0f).put(if (skipTable) 1f else 0f).put(if (kerr) 1f else 0f).put(kerrDebug.toFloat())
			projection.get(data.position(), data)
			data.position(data.position() + 16)
			if (centre != null && kerr) {
				val dx = centre.x - camX
				val dy = centre.y - camY
				val dz = centre.z - camZ
				data.put(dx.toFloat()).put(dy.toFloat()).put(dz.toFloat()).put((centre.hole?.spin ?: 0.0).toFloat())
				// [7].y: 1 if the centre is a hole (the Schwarzschild image is a fair first guess and cut there).
				// ([7].y 2: the hole is the only source in view: the near march, which follows its light alone, may stand in
				// for the map further out.)
				data.put(centre.scale.toFloat()).put(if (centre.hole == null) 0f else if (loneHole) (if (kerrApprox) (if (kerrExact) 4f else 3f) else 2f) else 1f).put(if (kerrApprox && loneHole) exactReach else KerrPaths.width.toFloat()).put(if (kerrApprox && loneHole) exactPasses.toFloat() else KerrPaths.height.toFloat())
				val viewToGame = org.joml.Matrix3f().set(Quaternionf(view).conjugate())
				for (column in 0..2) {
					val c = viewToGame.getColumn(column, Vector3f())
					data.put(c.x).put(c.y).put(c.z).put(0f)
				}
				val camR = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
				val reach = mapOuter ?: pathRadius(centre.scale, camR)
				// The pixel check's pixel angle; the image map: from just outside the horizon (or the centre's own inner
				// radius) out to the paths' end.
				data.put(pixelAngle(projection)).put(modeFlags(false, CHECKED_AXIS_CUT)).put(centre.inner.toFloat()).put(reach.toFloat())
				// [12..27]: an approximated lone spinning hole's exact shadow edge (HorizonMesh.edges), or 0s.
				val hole = centre.hole
				val edges = if (hole != null && hole.spin != 0.0 && loneHole && kerrApprox) HorizonMesh.edges(hole, camX, camY, camZ) else null
				for (k in 0 until HorizonMesh.EDGE_SAMPLES) data.put(edges?.get(k) ?: 0f)
			} else {
				repeat(20) { data.put(0f) }
				data.put(pixelAngle(projection)).put(modeFlags(false, CHECKED_AXIS_CUT)).put(0f).put(0f)
				repeat(HorizonMesh.EDGE_SAMPLES) { data.put(0f) }
			}
			data.position(0).limit(BLOCK_BYTES / 4)
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, target)
			GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0L, data)
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0)
		}
		GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, binding, target)
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
		if (secondTexture == 0) secondTable?.getNow(null)?.let {
			secondTexture = uploadTable(it)
			secondTable = null
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

	/** Links [shader]'s program (see [linkProgram]) unless it's the one already linked. */
	@JvmStatic
	fun ensureLinked(shader: net.minecraft.client.renderer.ShaderInstance) {
		val id = shader.id
		if (linkedPrograms[shader] == id) return
		linkedPrograms[shader] = id
		linkProgram(id)
	}

	/** Points [program]'s lens block at our binding and its table sampler at our texture unit (if it has them). */
	@JvmStatic
	fun linkProgram(program: Int) {
		if (binding < 0) binding = GL11.glGetInteger(GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS) - 1
		val block = GL31.glGetUniformBlockIndex(program, BLOCK)
		if (block != GL31.GL_INVALID_INDEX) GL31.glUniformBlockBinding(program, block, binding)
		for ((name, unit) in listOf(SAMPLER to TEXTURE_UNIT, KERR_SAMPLER to TEXTURE_UNIT - 1, KERR_MAP_SAMPLER to TEXTURE_UNIT - 2, SECOND_SAMPLER to TEXTURE_UNIT - 3, "RelativityKerrSecondMap" to TEXTURE_UNIT - 4)) {
			val sampler = GL20.glGetUniformLocation(program, name)
			if (sampler < 0) continue
			if (GL.getCapabilities().OpenGL41) {
				GL41.glProgramUniform1i(program, sampler, unit)
			} else {
				val current = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
				GL20.glUseProgram(program)
				GL20.glUniform1i(sampler, unit)
				GL20.glUseProgram(current)
			}
		}
	}

	/** Flywheel's line placing each instanced vertex on screen (flywheel:internal/common.vert, both backends). */
	private const val FLYWHEEL_POSITION = "gl_Position = flw_viewProjection * flw_vertexPos;"

	/** The vertex lens's declarations and functions (vertex_lens_core.glsl), read once. */
	private val lensSource: String? by lazy {
		val location = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CreateRelativity.MOD_ID, "pinwheel/shaders/include/vertex_lens_core.glsl")
		val resource = Minecraft.getInstance().resourceManager.getResource(location).orElse(null) ?: return@lazy null
		resource.open().use { String(it.readAllBytes(), Charsets.UTF_8) }
	}

	/** Shader-pack programs not lensed: the shadow pass (drawn from the sun), the hand, sky, clouds, Distant Horizons. */
	private val IRIS_SKIPPED = listOf("shadow", "gbuffers_hand", "gbuffers_sky", "gbuffers_clouds", "dh_")

	/** A shader-pack program's `main`, renamed so the lens can run after it. */
	private val IRIS_MAIN = Regex("""\bvoid\s+main\s*\(\s*(void\s*)?\)""")

	/**
	 * A shader pack's vertex source for program [name] (as Iris patched it) with the lens applied to its final position:
	 * its `main` renamed, and a new one that runs it, then bends gl_Position. Unchanged for skipped programs, and for
	 * programs with later stages that set the position (geometry or tessellation; the lens would have to go there).
	 */
	@JvmStatic
	fun irisSource(name: String, source: String, laterStages: Boolean): String {
		val lens = lensSource ?: return source
		if (laterStages || IRIS_SKIPPED.any { name.startsWith(it) }) return source
		val main = IRIS_MAIN.find(source) ?: return source
		return source.substring(0, main.range.first) + "void relativity_packMain()" + source.substring(main.range.last + 1) +
			"\n" + lens + "\nvoid main() {\n    relativity_packMain();\n    gl_Position = relativityLensLevel(gl_Position);\n}\n"
	}

	/**
	 * A Flywheel shader's full source with the lens applied where it places vertices on screen (so Create's instanced
	 * parts bend like everything else, with Flywheel's instancing kept); other sources unchanged.
	 */
	@JvmStatic
	fun flywheelSource(source: CharSequence): CharSequence {
		val text = source.toString()
		val at = text.indexOf(FLYWHEEL_POSITION)
		val main = text.indexOf("void _flw_main(")
		val lens = lensSource
		if (at < 0 || main < 0 || main > at || lens == null) return source
		return text.substring(0, main) + "\n" + lens + "\n" + text.substring(main, at) +
			"gl_Position = relativityLens(flw_viewProjection * flw_vertexPos, flw_projection);" +
			text.substring(at + FLYWHEEL_POSITION.length)
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

	/** Frustum override: boxes near the lens centre (the lensing hole, or another source) are always drawn. */
	@JvmStatic
	fun alwaysVisible(box: AABB): Boolean {
		if (!activeThisFrame) return false
		val hole = lensCentre ?: return false
		val radius = minOf(RELAX_RADIUS * hole.scale, 256.0)
		val dx = maxOf(box.minX - hole.x, 0.0, hole.x - box.maxX)
		val dy = maxOf(box.minY - hole.y, 0.0, hole.y - box.maxY)
		val dz = maxOf(box.minZ - hole.z, 0.0, hole.z - box.maxZ)
		return dx * dx + dy * dy + dz * dz < radius * radius
	}
}
