package net.kernelpanicsoft.relativity.physics.spacetime.field

/**
 * One source of curvature in a [ComposedSpacetime]: what it adds to the inverse metric, `g^{μν} = η^{μν} + Σ δg^{μν}`,
 * on Cartesian coordinates `(t, x, y, z)` in geometric units.
 *
 * Sources are summed in the inverse metric because that's all light needs (its Hamiltonian is `½ g^{μν} p_μ p_ν`), and
 * free fall follows from it too. Near one source the sum is that source exactly; between several it's the usual
 * superposition (exact for weak fields, a model for strong ones far enough apart). Nothing here assumes the sum attracts:
 * negative energy (a negative mass, a warp bubble's walls) enters with its own sign.
 */
interface MetricSource {
	/** Adds this source's `δg^{μν}` at event (t, x, y, z), and its derivatives along t, x, y and z, into [out]. */
	fun contribute(t: Double, x: Double, y: Double, z: Double, out: FieldSample)

	/** Whether (t, x, y, z) is behind this source's horizon, where light and anything else is captured. */
	fun captures(t: Double, x: Double, y: Double, z: Double): Boolean = false
}

/**
 * The inverse metric at an event and its first derivatives: [value]`[μ*4+ν]` = `g^{μν}`, [d]`[k][μ*4+ν]` = `∂_k g^{μν}`
 * with k = 0 for t, 1..3 for x, y, z. Sources add into it; symmetric, both halves filled.
 */
class FieldSample {
	@JvmField val value = DoubleArray(16)
	@JvmField val d = Array(4) { DoubleArray(16) }

	/** Resets to nothing at all (for collecting sources' contributions alone). */
	fun clear() {
		value.fill(0.0)
		for (k in 0..3) d[k].fill(0.0)
	}

	/** Resets to flat spacetime: η = diag(−1, 1, 1, 1), no derivatives. */
	fun flat() {
		value.fill(0.0)
		value[0] = -1.0
		value[5] = 1.0
		value[10] = 1.0
		value[15] = 1.0
		for (k in 0..3) d[k].fill(0.0)
	}

	/** Adds [amount] to `g^{μν}` and `g^{νμ}`. */
	fun add(mu: Int, nu: Int, amount: Double) {
		value[mu * 4 + nu] += amount
		if (mu != nu) value[nu * 4 + mu] += amount
	}

	/** Adds [amount] to `∂_k g^{μν}` and `∂_k g^{νμ}`. */
	fun addDerivative(k: Int, mu: Int, nu: Int, amount: Double) {
		d[k][mu * 4 + nu] += amount
		if (mu != nu) d[k][nu * 4 + mu] += amount
	}
}
