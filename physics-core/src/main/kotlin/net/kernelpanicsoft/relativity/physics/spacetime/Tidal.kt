package net.kernelpanicsoft.relativity.physics.spacetime

/**
 * Tidal tensors: relative acceleration `a_i = T_ij ξ^j` between two nearby free-falling points separated by `ξ`.
 */
object Tidal {
	/**
	 * Tidal tensor of a Schwarzschild hole of mass [m] at position [p] (relative to the hole), for a static or
	 * radially infalling observer: `T = (m/r³)(3 n⊗n − 1)`. Radial stretch `2m/r³`, transverse squeeze `−m/r³`.
	 * Row-major 3×3 into [out].
	 */
	fun schwarzschild(m: Double, p: DoubleArray, out: DoubleArray) {
		val r2 = p[0] * p[0] + p[1] * p[1] + p[2] * p[2]
		val r = kotlin.math.sqrt(r2)
		val k = m / (r2 * r)
		for (i in 0..2) for (j in 0..2) {
			out[i * 3 + j] = k * (3.0 * p[i] * p[j] / r2 - if (i == j) 1.0 else 0.0)
		}
	}
}
