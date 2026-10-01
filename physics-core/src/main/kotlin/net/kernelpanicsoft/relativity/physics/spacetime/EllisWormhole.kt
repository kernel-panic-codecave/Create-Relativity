package net.kernelpanicsoft.relativity.physics.spacetime

import kotlin.math.cos
import kotlin.math.sin

/**
 * The Ellis–Bronnikov ("drainhole") traversable wormhole with throat radius [throat]:
 * `ds² = −dt² + dl² + (l² + b₀²)(dθ² + sin²θ dφ²)` on the chart `(t, l, θ, φ)`.
 *
 * `l` is proper radial distance through the throat: `l < 0` and `l > 0` are the two mouths' sides,
 * each asymptotically flat. No horizon, no tidal singularity; it needs exotic matter to hold open.
 */
class EllisWormhole(val throat: Double) : Spacetime {
	init {
		require(throat > 0.0) { "throat radius must be positive, was $throat" }
	}

	/** Areal radius `√(l² + b₀²)` of the sphere at proper distance [l]. */
	fun arealRadius(l: Double): Double = kotlin.math.sqrt(l * l + throat * throat)

	override fun metric(x: DoubleArray, out: DoubleArray) {
		val area = x[1] * x[1] + throat * throat
		val s = sin(x[2])
		out.fill(0.0)
		out[0] = -1.0
		out[5] = 1.0
		out[10] = area
		out[15] = area * s * s
	}

	override fun christoffel(x: DoubleArray, out: DoubleArray) {
		val l = x[1]
		val s = sin(x[2])
		val c = cos(x[2])
		val k = l / (l * l + throat * throat)
		out.fill(0.0)
		out[gammaIndex(1, 2, 2)] = -l
		out[gammaIndex(1, 3, 3)] = -l * s * s
		out[gammaIndex(2, 1, 2)] = k
		out[gammaIndex(2, 2, 1)] = k
		out[gammaIndex(2, 3, 3)] = -s * c
		out[gammaIndex(3, 1, 3)] = k
		out[gammaIndex(3, 3, 1)] = k
		out[gammaIndex(3, 2, 3)] = c / s
		out[gammaIndex(3, 3, 2)] = c / s
	}
}
