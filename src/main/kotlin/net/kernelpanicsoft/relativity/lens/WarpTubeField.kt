package net.kernelpanicsoft.relativity.lens

import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.spacetime.FieldSource
import net.minecraft.resources.ResourceLocation
import org.joml.Vector3dc

/**
 * Warp tubes ([FieldSource.Tube], maglev trains') as fields (lens_fields/warp_tube.glsl): the push along each spine
 * inside its soft wall, the nearest [MAX_TUBES]; spines with too many points for [MAX_POINTS] thinned evenly (their
 * ends kept).
 *
 * Its share: [0] x tubes, y points, w a pixel's angle; [1 + j] tube j's x radius, y first point, z points, w speed; [1 + MAX_TUBES + i]
 * point i: xyz view space. Each spine reversed (light followed arriving: the push runs the other way).
 */
object WarpTubeField : LensFieldType {
	const val MAX_TUBES = 4
	const val MAX_POINTS = 64

	override val slots = 1 + MAX_TUBES + MAX_POINTS
	override val order = 25
	override val glsl: ResourceLocation = CreateRelativity.id("lens_fields/warp_tube.glsl")

	override fun upload(frame: LensFrame, out: LensFieldWriter): Int {
		val tubes = frame.sources.filterIsInstance<FieldSource.Tube>()
			.filter { it.spine.size >= 2 }
			.sortedBy { it.distance(frame.camera) }
			.take(MAX_TUBES)
		val share = maxOf(2, MAX_POINTS / maxOf(tubes.size, 1))
		val spines = tubes.map { thin(it.spine, share).asReversed() }
		out.put(spines.size.toFloat(), spines.sumOf { it.size }.toFloat(), 0f, frame.pixel.toFloat())
		var first = 0
		for ((j, spine) in spines.withIndex()) {
			out.put(tubes[j].radius.toFloat(), first.toFloat(), spine.size.toFloat(), tubes[j].speed.toFloat())
			first += spine.size
		}
		out.seek(1 + MAX_TUBES)
		for (spine in spines) for (p in spine) out.put(frame.toView(p), 0f)
		return spines.size
	}

	/** [points] down to [most] of them, evenly, the ends kept. */
	private fun thin(points: List<Vector3dc>, most: Int): List<Vector3dc> {
		if (points.size <= most) return points
		return (0 until most).map { points[(it.toLong() * (points.size - 1) / (most - 1)).toInt()] }
	}
}
