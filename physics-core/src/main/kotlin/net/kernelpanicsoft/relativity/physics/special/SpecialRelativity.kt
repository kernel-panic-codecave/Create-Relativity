package net.kernelpanicsoft.relativity.physics.special

import kotlin.math.sqrt
import kotlin.math.atanh

/** Special-relativistic kinematics with `c = 1`; velocities are 3-vectors as `DoubleArray(3)`. */
object SpecialRelativity {
	fun gamma(beta: Double): Double {
		require(beta in 0.0..<1.0) { "speed must be in [0, 1), was $beta" }
		return 1.0 / sqrt(1.0 - beta * beta)
	}

	fun gamma(v: DoubleArray): Double = gamma(length(v))

	/** Rapidity `atanh β`: adds linearly for collinear boosts, which is how thrust should accumulate. */
	fun rapidity(beta: Double): Double = atanh(beta)

	/**
	 * Velocity of something moving at [u] in a frame that itself moves at [v]
	 * (Einstein addition, general non-collinear form). The result is always slower than light.
	 */
	fun add(v: DoubleArray, u: DoubleArray): DoubleArray {
		val v2 = dot(v, v)
		if (v2 == 0.0) return u.copyOf()
		val g = gamma(sqrt(v2))
		val vu = dot(v, u)
		// Parallel part adds as (u∥ + v)/(1 + v·u); perpendicular part is also divided by γ.
		val parallel = 1.0 + (1.0 - 1.0 / g) * vu / v2
		return DoubleArray(3) { i -> (u[i] / g + parallel * v[i]) / (1.0 + vu) }
	}

	/** Aberration: cosine of a light ray's angle to the motion as seen by an observer moving at speed [beta]. */
	fun aberrate(cosTheta: Double, beta: Double): Double = (cosTheta + beta) / (1.0 + beta * cosTheta)

	/**
	 * Doppler factor `D = 1 / (γ (1 − β·n))` for light arriving from direction [towardSource] (unit vector
	 * from observer to source) at an observer moving with [v]. Observed frequency is `D × emitted`.
	 */
	fun doppler(v: DoubleArray, towardSource: DoubleArray): Double = 1.0 / (gamma(v) * (1.0 - dot(v, towardSource)))

	private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
	private fun length(a: DoubleArray) = sqrt(dot(a, a))
}
