package net.kernelpanicsoft.relativity.physics.geodesic

import net.kernelpanicsoft.relativity.physics.spacetime.Spacetime
import kotlin.math.sqrt

/** Light rays as polylines through space, for picking and anything else that needs "what's along this line of sight". */
object NullRay {
	/** Why a [trace] stopped. */
	enum class End { LENGTH, CAPTURED, STALLED }

	class Path(val points: List<DoubleArray>, val end: End)

	/**
	 * Follows the null geodesic leaving spatial point [origin] along coordinate direction [direction] (both 3-vectors in
	 * [spacetime]'s Cartesian chart, centred on its source), until the polyline is [maxLength] long or the ray comes
	 * within [captureRadius] of the centre. Points are at most [maxSegment] apart, and closer near the source.
	 */
	fun trace(
		spacetime: Spacetime,
		origin: DoubleArray,
		direction: DoubleArray,
		maxLength: Double,
		captureRadius: Double,
		maxSegment: Double = 0.25,
	): Path {
		val state = Geodesic.lightlike(spacetime, doubleArrayOf(0.0, origin[0], origin[1], origin[2]), direction)
		val points = mutableListOf(origin.copyOf())
		var length = 0.0
		var end = End.STALLED
		val geodesic = Geodesic(spacetime, rtol = 1e-8, atol = 1e-10)
		// Steps are in affine parameter; the coordinate distance covered per unit of it grows toward the source.
		geodesic.advance(state, Double.MAX_VALUE, stepLimit = { s ->
			minOf(maxSegment, 0.1 * radius(s)) / sqrt(s[5] * s[5] + s[6] * s[6] + s[7] * s[7]).coerceAtLeast(1e-9)
		}) { s ->
			val last = points.last()
			val dx = s[1] - last[0]
			val dy = s[2] - last[1]
			val dz = s[3] - last[2]
			length += sqrt(dx * dx + dy * dy + dz * dz)
			points += doubleArrayOf(s[1], s[2], s[3])
			when {
				radius(s) < captureRadius -> { end = End.CAPTURED; false }
				length >= maxLength -> { end = End.LENGTH; false }
				else -> true
			}
		}
		return Path(points, end)
	}

	private fun radius(s: DoubleArray) = sqrt(s[1] * s[1] + s[2] * s[2] + s[3] * s[3])
}
