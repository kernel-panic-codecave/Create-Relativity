package net.kernelpanicsoft.relativity.lens

import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.spacetime.FieldSource
import net.kernelpanicsoft.relativity.spacetime.along
import net.kernelpanicsoft.relativity.spacetime.samples
import net.minecraft.resources.ResourceLocation
import kotlin.math.sqrt

/**
 * Krasnikov tubes ([FieldSource.Krasnikov]) as smooth fields (lens_fields/tube.glsl): the tilt eased in across a wall
 * as the physics has it (KrasnikovTube), the axis along the spine. Each tube's curve cut into points for this view
 * (about [PIXELS] off the curve at its distance, coarser all round if they don't all fit), nearest tubes first.
 *
 * Its share: [0] x tubes, y points, z the walls' sharpness ([lensSharpness]), w a pixel's angle; [1 + j] tube j's x radius, y first point, z points; [1 + MAX_TUBES + i] point i:
 * xyz view space, w the tube's tilt k there; [1 + MAX_TUBES + MAX_POINTS + c] cluster c's bounding ball (xyz its centre,
 * w its radius): segments [CLUSTER] at a time from each tube's first, tube j's first cluster in its w. Each spine reversed (light followed arriving: the field runs the other way).
 */
object TubeField : LensFieldType {
	const val MAX_TUBES = 4
	const val MAX_POINTS = 256

	/** How far (pixels) the cut may stray from a tube's curve. */
	private const val PIXELS = 0.2

	/** The finest cut (blocks). */
	private const val FINEST = 0.02

	/** Segments a bounding ball covers. */
	const val CLUSTER = 8
	const val MAX_CLUSTERS = MAX_POINTS / CLUSTER + MAX_TUBES

	override val slots = 1 + MAX_TUBES + MAX_POINTS + MAX_CLUSTERS

	/**
	 * The lens's walls' sharpness (times the radius; the physics' KrasnikovTube is 8): lower is softer, its image
	 * smoother for the light table, and further from the physics' (being tried: GameTests).
	 */
	@JvmField
	var lensSharpness = 8.0
	override val order = 20
	override val glsl: ResourceLocation = CreateRelativity.id("lens_fields/tube.glsl")

	override fun upload(frame: LensFrame, out: LensFieldWriter): Int {
		val camera = frame.camera
		fun distance(x: Double, y: Double, z: Double) = sqrt((x - camera.x()) * (x - camera.x()) + (y - camera.y()) * (y - camera.y()) + (z - camera.z()) * (z - camera.z()))
		val tubes = frame.sources.filterIsInstance<FieldSource.Krasnikov>()
			.sortedBy { tube -> tube.spine.minOf { distance(it.x(), it.y(), it.z()) } }
			.take(MAX_TUBES)
		// (The cut kept while the tubes and the pixel's the same and the camera's within half a block: cutting a long
		// tube's curve this finely, every frame, stalls them.)
		val cached = cache
		val cut = if (cached != null && cached.tubes == tubes && cached.pixel == frame.pixel && cached.camera.distance(camera) < 0.5) cached.cut
			else cut(tubes, frame, ::distance).also { cache = Cached(tubes, org.joml.Vector3d(camera), frame.pixel, it) }
		var points = 0
		val kept = cut.takeWhile { points += it.size; points <= MAX_POINTS }
		out.put(kept.size.toFloat(), kept.sumOf { it.size }.toFloat(), lensSharpness.toFloat(), frame.pixel.toFloat())
		var first = 0
		var firstCluster = 0
		for ((j, spine) in kept.withIndex()) {
			out.put(tubes[j].radius.toFloat(), first.toFloat(), spine.size.toFloat(), firstCluster.toFloat())
			first += spine.size
			firstCluster += (spine.size - 1 + CLUSTER - 1) / CLUSTER
		}
		out.seek(1 + MAX_TUBES)
		val views = kept.map { spine -> spine.map { (p, tilt) -> frame.toView(p) to tilt } }
		for (spine in views) for ((v, tilt) in spine) out.put(v, tilt.toFloat())
		out.seek(1 + MAX_TUBES + MAX_POINTS)
		for (spine in views) for (start in 0 until spine.size - 1 step CLUSTER) {
			val points = spine.subList(start, minOf(start + CLUSTER + 1, spine.size)).map { it.first }
			val centre = org.joml.Vector3f()
			for (p in points) centre.add(p)
			centre.div(points.size.toFloat())
			out.put(centre, points.maxOf { it.distance(centre) })
		}
		return kept.size
	}

	private class Cached(val tubes: List<FieldSource.Krasnikov>, val camera: org.joml.Vector3dc, val pixel: Double, val cut: List<List<Pair<org.joml.Vector3dc, Double>>>)

	private var cache: Cached? = null

	/** Each of [tubes]' curves cut into points (about [PIXELS] off it at its distance, coarser all round if they don't all fit). */
	private fun cut(tubes: List<FieldSource.Krasnikov>, frame: LensFrame, distance: (Double, Double, Double) -> Double): List<List<Pair<org.joml.Vector3dc, Double>>> {
		var scale = 1.0
		var cut = emptyList<List<Pair<org.joml.Vector3dc, Double>>>()
		for (attempt in 0 until 10) {
			cut = tubes.map { tube ->
				samples(tube.curve, { p -> maxOf(FINEST, PIXELS * frame.pixel * distance(p.x(), p.y(), p.z())) * scale })
					.map { (p, at) -> p to along(tube.tilts, at) }
					// (Reversed: the lens follows light from the camera back the way it came, and turning time round turns a
					// tube's tilt round.)
					.asReversed()
			}
			if (cut.sumOf { it.size } <= MAX_POINTS) break
			scale *= 2.0
		}
		return cut
	}
}
