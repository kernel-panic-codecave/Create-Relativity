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
 * This feeds those shaders: a uniform block (the nearest lensing hole, the level's projection, the field lens's
 * sources, tubes and bubbles) and the Schwarzschild image tables ([SchwarzschildImages.forFiltering]): a lone hole's
 * images from those, anything more composed source by source (the field lens). Every hole is lensed as a non-spinning
 * one. The post pass ([BlackHoleLens]) then draws the shadow, the disk and the bent sky.
 */
object VertexLens {
	private const val TABLE_U0 = 64
	private const val TABLE_UP = 64
	private const val TABLE_PHI = 128
	/** The table's texture unit: the last one, clear of what vanilla, Flywheel and shader packs use. */
	private val TEXTURE_UNIT: Int by lazy { GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS) - 1 }
	private const val BLOCK = "RelativityLens"
	private const val SAMPLER = "RelativityImages"
	private const val BLOCK_BYTES = 2544 // 159 vec4s: see vertex_lens_core.glsl's RelativityLensData

	/** Tube segments a bounding ball covers ([151..158]: vertex_lens_core.glsl's RELATIVITY_TUBE_CLUSTER). */
	private const val TUBE_CLUSTER = 8

	/** The field lens's sources (vertex_lens_core.glsl's RELATIVITY_MAX_FIELD), nearest first. */
	const val MAX_FIELD_SOURCES = 8

	/** The escape table's sampler (BlackHoleLens's LUT) for the field lens's holes, on the unit below the others'. */
	private const val ESCAPE_SAMPLER = "RelativityEscape"

	/** This frame's sources for the field lens (camera-relative positions are taken at upload). */
	private var fieldSources: List<net.kernelpanicsoft.relativity.spacetime.FieldSource> = emptyList()

	/** The field lens's tubes (vertex_lens_core.glsl's RELATIVITY_MAX_TUBES), and their spines' points in all. */
	const val MAX_TUBES = 4
	const val MAX_TUBE_POINTS = 64

	/** Debug (GameTests): at most this many tube points uploaded (the rest as if too many to fit). */
	@JvmField
	var tubePointBudget = MAX_TUBE_POINTS

	/**
	 * Tubes alone (no bubbles) walked through the light table (light_table.comp's tubeWalk): the post pass's sky from
	 * it, not traced per pixel (a fifth of its cost round a long tube); [tubeSeeds] for the vertices. Debug off.
	 */
	@JvmField
	var tubeTable = true

	/** Debug (GameTests): times the level's own drawing on the GPU (after the sky to the end), [levelTimer]. */
	@JvmField
	var timeLevel = false

	/** The level's drawing's GPU time while [timeLevel]. */
	val levelTimer = GpuTimer()

	/** Debug (GameTests): the tube points uploaded last frame. */
	@JvmField
	var lastTubePoints = 0

	/** The tube walk's most events (walls and joints crossed) in the shaders. */
	const val TUBE_EVENTS = 16

	/** Debug (GameTests): the tubes' trace's most events (walls and joints crossed) a trace. */
	@JvmField
	var tubeEvents = TUBE_EVENTS

	/** How far (blocks) a tube's spine as uploaded may stray from its own, at the least: straight runs become one segment. */
	private const val TUBE_TOLERANCE = 0.05

	/** A tube point's tilt rides in its w after the tube's index, scaled by this (vertex_lens_core.glsl's RELATIVITY_TUBE_TILT_SCALE). */
	private const val TUBE_TILT_SCALE = 0.4

	/** How far (pixels) a curved tube's segments may stray from its curve, where it's drawn. */
	private const val TUBE_PIXELS = 2.0

	/** The tube aim's most traces a point (a group of vertices waits for its slowest). */
	@JvmField
	var tubeTraces = 16

	/**
	 * Debug: the tube aim started from the light table's image and refined by at most [seededTraces] (tubes alone,
	 * [tubeTable]). Off: each vertex searches as without the table (the table still gives the post pass its sky).
	 * Measured: refining costs about what searching does (the aim's own cost, not its traces), and a few traces leave it
	 * unconverged (half the pixels wrong inside a tube).
	 */
	@JvmField
	var tubeSeeds = false

	/** The tube aim's most traces refining the light table's image ([tubeSeeds]). */
	@JvmField
	var seededTraces = 3

	/** This frame's tubes for the field lens, nearest first. */
	private var tubeSources: List<net.kernelpanicsoft.relativity.spacetime.FieldSource> = emptyList()

	/** The field lens's warp bubbles (vertex_lens_core.glsl's RELATIVITY_MAX_BUBBLES), and this frame's, nearest first. */
	const val MAX_BUBBLES = 4
	private var bubbleSources: List<net.kernelpanicsoft.relativity.spacetime.FieldSource.Ellipsoid> = emptyList()

	/** Whether the field lens's walls draw [source]: a tube or a bubble. */
	private fun isWall(source: net.kernelpanicsoft.relativity.spacetime.FieldSource) = isTube(source) || source is net.kernelpanicsoft.relativity.spacetime.FieldSource.Ellipsoid

	private fun isTube(source: net.kernelpanicsoft.relativity.spacetime.FieldSource) =
		source is net.kernelpanicsoft.relativity.spacetime.FieldSource.Krasnikov || source is net.kernelpanicsoft.relativity.spacetime.FieldSource.Tube

	private fun spineOf(source: net.kernelpanicsoft.relativity.spacetime.FieldSource): List<org.joml.Vector3dc> = when (source) {
		is net.kernelpanicsoft.relativity.spacetime.FieldSource.Krasnikov -> source.spine
		is net.kernelpanicsoft.relativity.spacetime.FieldSource.Tube -> source.spine
		else -> emptyList()
	}

	private fun tubeRadius(source: net.kernelpanicsoft.relativity.spacetime.FieldSource): Double = when (source) {
		is net.kernelpanicsoft.relativity.spacetime.FieldSource.Krasnikov -> source.radius
		is net.kernelpanicsoft.relativity.spacetime.FieldSource.Tube -> source.radius
		else -> 0.0
	}

	/** The point of [spine] nearest [at]. */
	private fun nearestOnSpine(spine: List<org.joml.Vector3dc>, at: org.joml.Vector3dc): org.joml.Vector3d {
		var best = org.joml.Vector3d(spine.first())
		for (k in 0 until spine.size - 1) {
			val a = spine[k]
			val d = org.joml.Vector3d(spine[k + 1]).sub(a)
			val l2 = d.lengthSquared()
			val u = if (l2 > 0) (org.joml.Vector3d(at).sub(a).dot(d) / l2).coerceIn(0.0, 1.0) else 0.0
			val p = org.joml.Vector3d(a).fma(u, d)
			if (p.distanceSquared(at) < best.distanceSquared(at)) best = p
		}
		return best
	}

	/** [spine] with every point dropped that lies within [tolerance] of the line its kept neighbours make (Douglas-Peucker). */
	private fun simplify(spine: List<org.joml.Vector3dc>, tolerance: Double): List<org.joml.Vector3dc> {
		if (spine.size <= 2) return spine
		val keep = BooleanArray(spine.size)
		keep[0] = true
		keep[spine.size - 1] = true
		val spans = ArrayDeque<Pair<Int, Int>>()
		spans += 0 to spine.size - 1
		while (spans.isNotEmpty()) {
			val (from, to) = spans.removeLast()
			val a = spine[from]
			val d = org.joml.Vector3d(spine[to]).sub(a)
			val l2 = d.lengthSquared()
			var worst = -1
			var worstD = tolerance
			for (i in from + 1 until to) {
				val rel = org.joml.Vector3d(spine[i]).sub(a)
				val u = if (l2 > 0) (rel.dot(d) / l2).coerceIn(0.0, 1.0) else 0.0
				val off = rel.sub(org.joml.Vector3d(d).mul(u)).length()
				if (off > worstD) { worstD = off; worst = i }
			}
			if (worst >= 0) {
				keep[worst] = true
				spans += from to worst
				spans += worst to to
			}
		}
		return spine.filterIndexed { i, _ -> keep[i] }
	}

	/** Whether this frame's sources are composed in the shaders (the field lens): several, of the kinds it has modules for. */
	var composed = false
		private set

	/**
	 * Whether the post pass draws this frame's sky and shadows by the field lens (any source in view): the shadow then
	 * closes round a camera falling in.
	 */
	var fieldSky = false
		private set
	/**
	 * What the lens is centred on: the nearest hole ([hole] set), or with none in view the strongest other source (or
	 * the nearest tube or bubble). [scale] its size (r_s of a hole), [inner] how near it the lens reaches.
	 */
	private class Centre(val x: Double, val y: Double, val z: Double, val scale: Double, val inner: Double, val hole: BlackHole?, val key: Any)

	private fun centreOf(hole: BlackHole?, others: List<net.kernelpanicsoft.relativity.spacetime.FieldSource>, camera: net.minecraft.world.phys.Vec3): Centre? {
		if (hole != null) {
			return Centre(hole.x, hole.y, hole.z, hole.schwarzschildRadius, 1.05 * hole.schwarzschildRadius, hole, hole.id)
		}
		val at = org.joml.Vector3d(camera.x, camera.y, camera.z)
		// (Tubes are the field lens's tube module's: with nothing else in view, the lens centres on the nearest.)
		val tube = others.filter { isTube(it) }.minByOrNull { nearestOnSpine(spineOf(it), at).distanceSquared(at) }
		val strongest = others.filter { it !is net.kernelpanicsoft.relativity.spacetime.FieldSource.Tube && it !is net.kernelpanicsoft.relativity.spacetime.FieldSource.Ellipsoid && it !is net.kernelpanicsoft.relativity.spacetime.FieldSource.Krasnikov }.maxByOrNull { source ->
			when (source) {
				is net.kernelpanicsoft.relativity.spacetime.FieldSource.Mass -> 2.0 * abs(source.mass) / maxOf(source.center.distance(at), 1.0)
				is net.kernelpanicsoft.relativity.spacetime.FieldSource.Bubble -> source.speed * source.radius / maxOf(source.center.distance(at), 1.0)
				is BlackHole -> source.schwarzschildRadius / maxOf(source.center.distanceTo(camera), 1.0)
				is net.kernelpanicsoft.relativity.spacetime.FieldSource.Tube, is net.kernelpanicsoft.relativity.spacetime.FieldSource.Ellipsoid, is net.kernelpanicsoft.relativity.spacetime.FieldSource.Krasnikov -> 0.0
			}
		} ?: return tube?.let {
			val p = nearestOnSpine(spineOf(it), at)
			Centre(p.x, p.y, p.z, tubeRadius(it), 0.05, null, "tubes")
		} ?: others.filterIsInstance<net.kernelpanicsoft.relativity.spacetime.FieldSource.Ellipsoid>().minByOrNull { it.center.distanceSquared(at) }?.let {
			Centre(it.center.x(), it.center.y(), it.center.z(), it.axes.maxOf { a -> a.length() }, 0.05, null, "bubbles")
		}
		return when (strongest) {
			is net.kernelpanicsoft.relativity.spacetime.FieldSource.Mass -> {
				val c = strongest.center
				Centre(c.x(), c.y(), c.z(), maxOf(2.0 * abs(strongest.mass), strongest.softening / 3.0), maxOf(0.25 * strongest.softening, 0.05), null, strongest)
			}
			is net.kernelpanicsoft.relativity.spacetime.FieldSource.Bubble -> {
				val c = strongest.center
				Centre(c.x(), c.y(), c.z(), strongest.radius / 10.0, 0.05, null, strongest.javaClass)
			}
			is BlackHole -> centreOf(strongest, emptyList(), camera)
			is net.kernelpanicsoft.relativity.spacetime.FieldSource.Tube, is net.kernelpanicsoft.relativity.spacetime.FieldSource.Ellipsoid, is net.kernelpanicsoft.relativity.spacetime.FieldSource.Krasnikov -> null
		}
	}

	/** Boxes within this many r_s of the hole skip frustum culling: bent light can show them from anywhere. */
	private const val RELAX_RADIUS = 60.0

	private var table: CompletableFuture<FloatArray>? = null
	/** The secondary-image table (SchwarzschildImages.buildSecondaryTable), and its texture once built. */
	private var secondTable: CompletableFuture<FloatArray>? = null

	/** The image tables as built (SchwarzschildImages.sample / sampleSecondary read them), for the CPU. */
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

	/** Whether this frame's lensing hole is the only source in view (the shader's weak near march follows its light alone). */
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
			RenderLevelStageEvent.Stage.AFTER_SKY -> {
				beginLevel(event.projectionMatrix)
				if (timeLevel) levelTimer.begin()
			}
			RenderLevelStageEvent.Stage.AFTER_ENTITIES -> drawSelfSecondImage(event.partialTick.getGameTimeDeltaPartialTick(false))
			RenderLevelStageEvent.Stage.AFTER_LEVEL -> {
				if (timeLevel) levelTimer.end()
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
		// Every source the field lens has modules for (holes, lensed as non-spinning ones, masses, tubes, bubbles):
		// composed in the shaders, nothing traced or solved per frame; a lone hole by its exact image tables.
		val fieldCapable = holes.size + others.size >= 1
		val fieldWanted = fieldCapable && !(holes.size == 1 && others.isEmpty())
		if (!perspective) {
			composed = fieldWanted
			fieldSky = fieldCapable
			val at = org.joml.Vector3d(camera.x, camera.y, camera.z)
			tubeSources = others.filter { isTube(it) }.sortedBy { nearestOnSpine(spineOf(it), at).distanceSquared(at) }
			bubbleSources = others.filterIsInstance<net.kernelpanicsoft.relativity.spacetime.FieldSource.Ellipsoid>().sortedBy { it.center.distanceSquared(at) }
			fieldSources = (holes + others.filter { !isWall(it) }).sortedBy { s ->
				when (s) {
					is BlackHole -> s.center.distanceToSqr(camera)
					is net.kernelpanicsoft.relativity.spacetime.FieldSource.Mass -> s.center.distanceSquared(camera.x, camera.y, camera.z)
					else -> Double.MAX_VALUE
				}
			}
			loneHole = holes.size == 1 && others.isEmpty()
		}
		// The light table: composed sources with any that bend light (holes, masses) in this frame's own view; or tubes
		// and bubbles alone, walked through their walls (light_table.comp's tubeWalk), each vertex refined from it.
		// (Not with bubbles: the walk through them gave the sky wrong behind them.)
		val walls = tubeTable && tubeSources.isNotEmpty() && bubbleSources.isEmpty()
		LightTable.prepare(!perspective && fieldWanted && (fieldSources.isNotEmpty() || walls), projection)
		upload(centre, camera.x, camera.y, camera.z, view, projection, centre != null)
		// (The table stands while nothing it's made from has changed: the block, as uploaded, is all of it.)
		if (!LightTable.on || uploadHash != builtHash) LightTable.build { linkProgram(it) }
		if (LightTable.on) builtHash = uploadHash

		val previous = GlStateManager._getActiveTexture()
		GlStateManager._activeTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT - 1)
		GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, LightTable.texture)
		GlStateManager._activeTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT - 2)
		GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, LightTable.seeds)
		GlStateManager._activeTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT)
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture)
		GlStateManager._activeTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT - 3)
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, secondTexture)
		GlStateManager._activeTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT - 5)
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, BlackHoleLens.escapeTexture())
		GlStateManager._activeTexture(previous)
		// (Not with the light table yet: it holds first crossings, and the chain's second images through several holes
		// are poor.)
		// (A lone hole's secondary images. With the light table the post pass draws everything inside the Einstein
		// rings, every image, from the light itself: relativityInsideRing.)
		secondsWanted = !perspective && secondImages && !LightTable.on && centre?.hole != null && secondTexture != 0
	}

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
			if (LightTable.on) {
				// The light table's other signatures, a pass each ([11].z its slot): some mirrored, some not, so no
				// culling (depth sorts them).
				val culled = GL11.glIsEnabled(GL11.GL_CULL_FACE)
				GL11.glDisable(GL11.GL_CULL_FACE)
				try {
					for (slot in 1 until LightTable.SLOTS) {
						// (Slots with no signature this frame: nothing to draw.)
						if (!LightTable.slotsInUse[slot]) continue
						setSlot(slot)
						draw.run()
					}
				} finally {
					setSlot(0)
					if (culled) GL11.glEnable(GL11.GL_CULL_FACE)
				}
			} else {
				// A secondary image is the mirror image (the light comes round the other side), so every triangle's
				// winding is reversed: culling the front faces keeps the ones facing the camera along that light.
				GL11.glCullFace(GL11.GL_FRONT)
				draw.run()
			}
		} finally {
			GL11.glCullFace(GL11.GL_BACK)
			setModeFlags(false)
			drawingSecondImages = false
		}
	}

	/** Debug (LensCheck): the light table's slot later draws place vertices in; 0 again after. */
	@JvmStatic
	fun debugSlot(slot: Int) = setSlot(slot)

	/** The block's [11].z: the light table's slot the draw places vertices in (0 its primary signature). */
	private fun setSlot(slot: Int) {
		MemoryStack.stackPush().use { stack ->
			val data = stack.mallocFloat(1).put(0, slot.toFloat())
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, buffer)
			GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 11L * 16 + 8, data)
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0)
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

	/** Block entries [12..27] (once a spinning hole's shadow edge, now unused). */
	private const val EDGE_SAMPLES = 64

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

	/** The block's contents' hash as last uploaded, and as when the light table was last built. */
	private var uploadHash = 0
	private var builtHash = 1

	private fun upload(centre: Centre?, camX: Double, camY: Double, camZ: Double, view: Quaternionf, projection: Matrix4f, enabled: Boolean, target: Int = buffer) {
		MemoryStack.stackPush().use { stack ->
			val data = stack.mallocFloat(BLOCK_BYTES / 4)
			if (centre != null) {
				val at = view.transform(Vector3f((centre.x - camX).toFloat(), (centre.y - camY).toFloat(), (centre.z - camZ).toFloat()))
				data.put(at.x).put(at.y).put(at.z).put(centre.scale.toFloat())
			} else {
				data.put(0f).put(0f).put(0f).put(0f)
			}
			data.put(if (enabled) 1f else 0f).put(if (skipTable) 1f else 0f).put(0f).put(0f)
			projection.get(data.position(), data)
			data.position(data.position() + 16)
			// [6], [7]: the light table's view and size; [8..10] unused; [11]: the pixel check's pixel angle and the draw's
			// flags.
			LightTable.put(data)
			repeat(12) { data.put(0f) }
			data.put(pixelAngle(projection)).put(modeFlags(false, CHECKED_AXIS_CUT)).put(0f).put(0f)
			// [12..27] unused.
			repeat(EDGE_SAMPLES) { data.put(0f) }
			uploadField(data, camX, camY, camZ, view)
			data.position(0).limit(BLOCK_BYTES / 4)
			var hash = 1
			for (i in 0 until BLOCK_BYTES / 4) hash = 31 * hash + data.get(i).toRawBits()
			uploadHash = hash
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, target)
			GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0L, data)
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0)
		}
		GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, binding, target)
	}

	/**
	 * [28..44]: the field lens's sources (vertex_lens_core.glsl's relativityFieldCount and on), when [fieldSky], and
	 * whether the post pass fills the lens's asymptotes from the scene capture ([28].y, relativityCaptureFills): each
	 * centre in view space and its type (1 hole, 2 mass), then its parameters (hole: r_s; mass: mass, softening).
	 */
	private fun uploadField(data: java.nio.FloatBuffer, camX: Double, camY: Double, camZ: Double, view: Quaternionf) {
		val sources = if (fieldSky) fieldSources.take(MAX_FIELD_SOURCES) else emptyList()
		data.put(sources.size.toFloat()).put(0f).put(0f).put(ImageStage.reach)
		for (source in sources) {
			val (centre, type, params) = when (source) {
				is BlackHole -> Triple(source.center.let { org.joml.Vector3d(it.x, it.y, it.z) }, 1f, floatArrayOf(source.schwarzschildRadius.toFloat(), 0f))
				is net.kernelpanicsoft.relativity.spacetime.FieldSource.Mass -> Triple(org.joml.Vector3d(source.center), 2f, floatArrayOf(source.mass.toFloat(), source.softening.toFloat()))
				else -> continue
			}
			val at = view.transform(Vector3f((centre.x - camX).toFloat(), (centre.y - camY).toFloat(), (centre.z - camZ).toFloat()))
			data.put(at.x).put(at.y).put(at.z).put(type)
			data.put(params[0]).put(params[1]).put(0f).put(0f)
		}
		repeat(MAX_FIELD_SOURCES - sources.size) { repeat(8) { data.put(0f) } }
		uploadTubes(data, camX, camY, camZ, view)
	}

	/**
	 * [45..121]: the field lens's tubes (vertex_lens_core.glsl's relativityTubeCount and on), when [fieldSky]: how many,
	 * their points, the walk's and aim's bounds; each tube's radius, its inside's metric and observers
	 * (TubeModule.Interior); then every spine point in view space and its tube. Each spine simplified (as little as lets
	 * them all fit) and reversed: the shaders follow light the way the camera sees it, arriving, and time reversal turns
	 * a tube's push round.
	 */
	private fun uploadTubes(data: java.nio.FloatBuffer, camX: Double, camY: Double, camZ: Double, view: Quaternionf) {
		val tubes = if (fieldSky) tubeSources.take(MAX_TUBES) else emptyList()
		var spines: List<List<org.joml.Vector3dc>> = emptyList()
		// (Each point's tilt too, for tubes whose tilt changes along them: Krasnikov tubes'.)
		var tilts: List<List<Double>?> = emptyList()
		var tolerance = TUBE_TOLERANCE
		// (Curved tubes cut into segments for this view: each piece till it strays under a couple of pixels at its
		// distance; coarser all round only if they don't all fit.)
		val pixel = pixelAngle(lastProjection).toDouble()
		var scale = 1.0
		for (attempt in 0 until 8) {
			val cut = tubes.map { tube ->
				if (tube is net.kernelpanicsoft.relativity.spacetime.FieldSource.Krasnikov) {
					val samples = net.kernelpanicsoft.relativity.spacetime.samples(tube.curve, { p ->
						maxOf(TUBE_TOLERANCE, TUBE_PIXELS * pixel * kotlin.math.sqrt((p.x() - camX) * (p.x() - camX) + (p.y() - camY) * (p.y() - camY) + (p.z() - camZ) * (p.z() - camZ))) * scale
					})
					samples.map { it.first }.asReversed() to samples.map { net.kernelpanicsoft.relativity.spacetime.along(tube.tilts, it.second) }.asReversed()
				} else simplify(spineOf(tube), tolerance).asReversed() to null
			}
			spines = cut.map { it.first }
			tilts = cut.map { it.second }
			if (spines.sumOf { it.size } <= tubePointBudget) break
			tolerance *= 2.0
			scale *= 2.0
		}
		// (Still too many: the nearest that fit.)
		var points = 0
		val kept = spines.takeWhile { points += it.size; points <= tubePointBudget }
		points = kept.sumOf { it.size }
		lastTubePoints = points
		data.put(kept.size.toFloat()).put(points.toFloat()).put(tubeEvents.toFloat()).put(net.kernelpanicsoft.relativity.physics.lens.field.TubeModule.QUICK_STEPS.toFloat())
		for (j in kept.indices) {
			val tube = tubes[j]
			val inside = when (tube) {
				is net.kernelpanicsoft.relativity.spacetime.FieldSource.Krasnikov -> net.kernelpanicsoft.relativity.physics.lens.field.TubeModule.Interior.Krasnikov(tube.k)
				is net.kernelpanicsoft.relativity.spacetime.FieldSource.Tube -> net.kernelpanicsoft.relativity.physics.lens.field.TubeModule.Interior.Warp(tube.speed)
				else -> net.kernelpanicsoft.relativity.physics.lens.field.TubeModule.Interior.Flat
			}
			// (y 1: its points carry their tilts, vertex_lens_core.glsl's relativityTubeTilted.)
			data.put(tubeRadius(tube).toFloat()).put(if (tilts.getOrNull(j) != null) 1f else 0f).put(0f).put(0f)
			data.put(inside.a.toFloat()).put(inside.b.toFloat()).put(inside.c.toFloat()).put(inside.alpha.toFloat())
			data.put(inside.beta.toFloat()).put(inside.gamma.toFloat()).put(0f).put(0f)
		}
		repeat(MAX_TUBES - kept.size) { repeat(12) { data.put(0f) } }
		val placed = ArrayList<Vector3f>()
		val radii = ArrayList<Float>()
		for ((j, spine) in kept.withIndex()) for ((i, p) in spine.withIndex()) {
			val at = view.transform(Vector3f((p.x() - camX).toFloat(), (p.y() - camY).toFloat(), (p.z() - camZ).toFloat()))
			placed += Vector3f(at)
			radii += tubeRadius(tubes[j]).toFloat()
			// (Its tube, and where the tube's tilt changes along it the tilt here: TUBE_TILT_SCALE of it, under a half.)
			val tilt = tilts.getOrNull(j)?.getOrNull(i) ?: 0.0
			data.put(at.x).put(at.y).put(at.z).put(j + (TUBE_TILT_SCALE * tilt).toFloat())
		}
		repeat(MAX_TUBE_POINTS - points) { repeat(4) { data.put(0f) } }
		uploadBubbles(data, camX, camY, camZ, view)
		// [151..158]: a ball round each run of TUBE_CLUSTER segments (points c TUBE_CLUSTER to (c + 1) TUBE_CLUSTER) and
		// its tubes' walls, radius -1 for none: the shaders' walks over segments skip the runs a line or point misses.
		for (c in 0 until MAX_TUBE_POINTS / TUBE_CLUSTER) {
			val from = c * TUBE_CLUSTER
			val to = minOf(from + TUBE_CLUSTER, placed.size - 1)
			if (from >= to) {
				data.put(0f).put(0f).put(0f).put(-1f)
				continue
			}
			val lo = Vector3f(Float.MAX_VALUE)
			val hi = Vector3f(-Float.MAX_VALUE)
			for (i in from..to) { lo.min(placed[i]); hi.max(placed[i]) }
			val centre = Vector3f(lo).add(hi).mul(0.5f)
			var reach = 0f
			for (i in from..to) reach = maxOf(reach, placed[i].distance(centre) + radii[i])
			data.put(centre.x).put(centre.y).put(centre.z).put(reach)
		}
	}

	/**
	 * [122..150]: the field lens's warp bubbles (vertex_lens_core.glsl's relativityBubbleCount and on), when [fieldSky]:
	 * how many; each one's centre, semi-axes (unit and length), its inside (a warp push: TubeModule.Interior.Warp) and the
	 * push's direction, in view space, reversed as the tubes' spines are (light followed arriving).
	 */
	private fun uploadBubbles(data: java.nio.FloatBuffer, camX: Double, camY: Double, camZ: Double, view: Quaternionf) {
		val bubbles = if (fieldSky) bubbleSources.take(MAX_BUBBLES) else emptyList()
		// (And the walls' aim's most traces.)
		data.put(bubbles.size.toFloat()).put(tubeTraces.toFloat()).put(seededTraces.toFloat()).put(if (tubeSeeds) 1f else 0f)
		for (bubble in bubbles) {
			val c = view.transform(Vector3f((bubble.center.x() - camX).toFloat(), (bubble.center.y() - camY).toFloat(), (bubble.center.z() - camZ).toFloat()))
			data.put(c.x).put(c.y).put(c.z).put(0f)
			for (axis in bubble.axes) {
				val u = view.transform(Vector3f(axis.x().toFloat(), axis.y().toFloat(), axis.z().toFloat()).normalize())
				data.put(u.x).put(u.y).put(u.z).put(axis.length().toFloat())
			}
			val inside = net.kernelpanicsoft.relativity.physics.lens.field.TubeModule.Interior.Warp(bubble.speed)
			data.put(inside.a.toFloat()).put(inside.b.toFloat()).put(inside.c.toFloat()).put(inside.alpha.toFloat())
			data.put(inside.beta.toFloat()).put(inside.gamma.toFloat()).put(0f).put(0f)
			val n = view.transform(Vector3f(bubble.direction.x().toFloat(), bubble.direction.y().toFloat(), bubble.direction.z().toFloat()).normalize().negate())
			data.put(n.x).put(n.y).put(n.z).put(0f)
		}
		repeat(MAX_BUBBLES - bubbles.size) { repeat(28) { data.put(0f) } }
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
		for ((name, unit) in listOf(SAMPLER to TEXTURE_UNIT, "RelativityLightTable" to TEXTURE_UNIT - 1, "RelativityLightSeeds" to TEXTURE_UNIT - 2, SECOND_SAMPLER to TEXTURE_UNIT - 3, ESCAPE_SAMPLER to TEXTURE_UNIT - 5)) {
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
