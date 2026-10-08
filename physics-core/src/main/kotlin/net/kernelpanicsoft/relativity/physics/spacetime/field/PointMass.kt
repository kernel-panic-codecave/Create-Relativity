package net.kernelpanicsoft.relativity.physics.spacetime.field

import kotlin.math.sqrt

/**
 * A weak-field mass [m] at ([cx], [cy], [cz]), of either sign: Newtonian potential `Φ = −m / √(r² + ε²)` (softened by
 * [softening] so it stays finite inside the body), in the isotropic weak-field metric `δg^{tt} = 2Φ`, `δg^{ij} = 2Φ δ^{ij}`.
 * Light bends twice as much as Newton says (`4m/b`); a negative mass pushes light and matter away, defocusing.
 * Generators, planets and the far field of anything compact are this; it isn't a black hole (no horizon).
 */
class PointMass(val cx: Double, val cy: Double, val cz: Double, val m: Double, val softening: Double = 0.0) : MetricSource {
	override fun contribute(t: Double, x: Double, y: Double, z: Double, out: FieldSample) {
		val dx = x - cx
		val dy = y - cy
		val dz = z - cz
		val s2 = dx * dx + dy * dy + dz * dz + softening * softening
		val s = sqrt(s2)
		val phi = -m / s
		// ∂_k Φ = m (x_k − c_k) / s³
		val k = m / (s2 * s)
		val grad = doubleArrayOf(k * dx, k * dy, k * dz)
		for (mu in 0..3) {
			out.add(mu, mu, 2.0 * phi)
			for (axis in 1..3) out.addDerivative(axis, mu, mu, 2.0 * grad[axis - 1])
		}
	}
}
