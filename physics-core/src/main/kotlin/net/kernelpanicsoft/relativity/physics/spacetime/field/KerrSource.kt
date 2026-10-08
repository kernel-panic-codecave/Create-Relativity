package net.kernelpanicsoft.relativity.physics.spacetime.field

import net.kernelpanicsoft.relativity.physics.math.Dual3
import net.kernelpanicsoft.relativity.physics.math.plus
import net.kernelpanicsoft.relativity.physics.math.times
import net.kernelpanicsoft.relativity.physics.spacetime.Kerr

/**
 * A Kerr black hole of mass [m] and spin [a] (spin along +z) centred at ([cx], [cy], [cz]), in Kerr–Schild form:
 * `δg^{μν} = −H l^μ l^ν`. Alone, that's [Kerr] exactly (`g^{μν} = η^{μν} − H l^μ l^ν`). It holds still: a hole that
 * moves is placed afresh each moment, not boosted.
 */
class KerrSource(val cx: Double, val cy: Double, val cz: Double, val m: Double, val a: Double = 0.0) : MetricSource {
	private val kerr = Kerr(m, a)

	override fun contribute(t: Double, x: Double, y: Double, z: Double, out: FieldSample) {
		val px = Dual3.x(x - cx)
		val py = Dual3.y(y - cy)
		val pz = Dual3.z(z - cz)
		val a2 = a * a
		val w = px * px + py * py + pz * pz - a2
		val r2 = 0.5 * w + (0.25 * (w * w) + a2 * (pz * pz)).sqrt()
		val r = r2.sqrt()
		val denom = r2 + a2
		val h = 2.0 * m * (r * r2) / (r2 * r2 + a2 * (pz * pz))
		// l^μ = η^{μν} l_ν with l_ν = (1, lx, ly, lz), as in Kerr.
		val l = arrayOf(Dual3.constant(-1.0), (r * px + a * py) / denom, (r * py - a * px) / denom, pz / r)
		for (mu in 0..3) for (nu in mu..3) {
			val term = h * l[mu] * l[nu]
			out.add(mu, nu, -term.v)
			for (k in 1..3) out.addDerivative(k, mu, nu, -term.d(k - 1))
		}
	}

	override fun captures(t: Double, x: Double, y: Double, z: Double): Boolean =
		kerr.radius(x - cx, y - cy, z - cz) < kerr.horizonRadius
}
