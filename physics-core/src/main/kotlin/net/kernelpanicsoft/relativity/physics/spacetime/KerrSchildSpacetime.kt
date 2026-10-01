package net.kernelpanicsoft.relativity.physics.spacetime

import net.kernelpanicsoft.relativity.physics.math.Dual3

/**
 * A stationary spacetime in Kerr–Schild form `g_{μν} = η_{μν} + H l_μ l_ν` on Cartesian coordinates
 * `(t, x, y, z)`, with `l` null and `l_t = 1`.
 *
 * The inverse is exact, `g^{μν} = η^{μν} − H l^μ l^ν`, and subclasses only describe `H` and `l` over
 * [Dual3], so the Christoffel symbols come out exact without hand-derived derivatives. The chart is
 * regular across the future horizon: things fall in instead of freezing at it.
 */
abstract class KerrSchildSpacetime : Spacetime {
	/** `H` and the spatial covariant components of `l` at a spatial point. */
	class Profile(val h: Dual3, val lx: Dual3, val ly: Dual3, val lz: Dual3)

	protected abstract fun profile(x: Dual3, y: Dual3, z: Dual3): Profile

	override fun metric(x: DoubleArray, out: DoubleArray) {
		val p = profile(Dual3.constant(x[1]), Dual3.constant(x[2]), Dual3.constant(x[3]))
		val l = doubleArrayOf(1.0, p.lx.v, p.ly.v, p.lz.v)
		val h = p.h.v
		for (mu in 0..3) for (nu in 0..3) {
			out[mu * 4 + nu] = h * l[mu] * l[nu] + if (mu == nu) (if (mu == 0) -1.0 else 1.0) else 0.0
		}
	}

	override fun christoffel(x: DoubleArray, out: DoubleArray) {
		val p = profile(Dual3.x(x[1]), Dual3.y(x[2]), Dual3.z(x[3]))
		val h = p.h.v
		val l = doubleArrayOf(1.0, p.lx.v, p.ly.v, p.lz.v)
		val lComponents = arrayOf(p.lx, p.ly, p.lz)

		// dg[k*16 + μ*4 + ν] = ∂_k g_{μν}; time derivatives vanish (stationary).
		val dg = DoubleArray(64)
		for (k in 1..3) {
			val axis = k - 1
			val dh = p.h.d(axis)
			val dl = DoubleArray(4)
			for (i in 1..3) dl[i] = lComponents[i - 1].d(axis)
			for (mu in 0..3) for (nu in 0..3) {
				dg[k * 16 + mu * 4 + nu] = dh * l[mu] * l[nu] + h * (dl[mu] * l[nu] + l[mu] * dl[nu])
			}
		}

		// g^{λσ} = η^{λσ} − H l^λ l^σ with l^μ = η^{μν} l_ν.
		val lUp = doubleArrayOf(-l[0], l[1], l[2], l[3])
		val gInv = DoubleArray(16)
		for (a in 0..3) for (b in 0..3) {
			gInv[a * 4 + b] = -h * lUp[a] * lUp[b] + if (a == b) (if (a == 0) -1.0 else 1.0) else 0.0
		}

		for (lambda in 0..3) for (mu in 0..3) for (nu in mu..3) {
			var sum = 0.0
			for (sigma in 0..3) {
				val gi = gInv[lambda * 4 + sigma]
				if (gi == 0.0) continue
				sum += gi * (dg[mu * 16 + sigma * 4 + nu] + dg[nu * 16 + sigma * 4 + mu] - dg[sigma * 16 + mu * 4 + nu])
			}
			val value = 0.5 * sum
			out[gammaIndex(lambda, mu, nu)] = value
			out[gammaIndex(lambda, nu, mu)] = value
		}
	}
}
