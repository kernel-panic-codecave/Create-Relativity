package net.kernelpanicsoft.relativity.physics

import kotlin.math.pow

/**
 * The warp scale (Star Trek: The Next Generation's): warp factor w to speed in multiples of c. `w^(10/3)` up to warp 9
 * (warp 1 is light speed, warp 9 about 1516 c), then rising ever faster to infinity at warp 10, which can't be reached.
 */
object WarpScale {
	/** The factor past which the curve bends up toward warp 10. */
	const val BEND = 9.0

	/** Warp factor [w] (0 to under 10) as a multiple of c. */
	fun speed(w: Double): Double {
		if (w <= 0.0) return 0.0
		require(w < 10.0) { "warp 10 is infinitely fast" }
		val base = w.pow(10.0 / 3.0)
		if (w <= BEND) return base
		// Past warp 9, divided by the square of the way left to 10 (1 at warp 9: continuous; infinite at 10).
		val left = (10.0 - w) / (10.0 - BEND)
		return base / (left * left)
	}

	/** The warp factor of [speed] (times c, nonnegative): [speed]'s inverse. */
	fun factor(speed: Double): Double {
		if (speed <= 0.0) return 0.0
		if (speed <= speed(BEND)) return speed.pow(3.0 / 10.0)
		var lo = BEND
		var hi = 10.0
		repeat(80) {
			val mid = 0.5 * (lo + hi)
			if (speed(mid) < speed) lo = mid else hi = mid
		}
		return 0.5 * (lo + hi)
	}
}
