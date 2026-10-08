package net.kernelpanicsoft.relativity.physics.geodesic

import net.kernelpanicsoft.relativity.physics.spacetime.field.ComposedSpacetime
import net.kernelpanicsoft.relativity.physics.spacetime.field.FieldSample

/**
 * Light through a [ComposedSpacetime] in Hamiltonian form, needing only the inverse metric: with `H = ½ g^{μν} p_μ p_ν`,
 * `dx^μ/dλ = g^{μν} p_ν` and `dp_μ/dλ = −½ ∂_μ g^{αβ} p_α p_β`. State is `[x⁰..x³, p₀..p₃]`. This is the form the GPU
 * follows (no Christoffels, no metric inverse), so the CPU and the shaders share it.
 */
class HamiltonianRay(val spacetime: ComposedSpacetime, rtol: Double = 1e-9, atol: Double = 1e-12) {
	private val sample = FieldSample()

	val integrator = Dopri5(8, rtol, atol) { y, dydt -> derivative(y, dydt) }

	/** The right-hand side at state [y] into [dydt]. */
	fun derivative(y: DoubleArray, dydt: DoubleArray) {
		spacetime.inverse(y, sample)
		val gi = sample.value
		for (mu in 0..3) {
			var s = 0.0
			for (nu in 0..3) s += gi[mu * 4 + nu] * y[4 + nu]
			dydt[mu] = s
		}
		for (mu in 0..3) {
			val d = sample.d[mu]
			var s = 0.0
			for (a in 0..3) {
				val pa = y[4 + a]
				if (pa == 0.0) continue
				for (b in 0..3) s += d[a * 4 + b] * pa * y[4 + b]
			}
			dydt[4 + mu] = -0.5 * s
		}
	}

	/** Advances [state] by affine parameter [span]; see [Dopri5.integrate]. */
	fun advance(state: DoubleArray, span: Double, stepLimit: ((DoubleArray) -> Double)? = null, onStep: ((DoubleArray) -> Boolean)? = null): Double =
		integrator.integrate(state, span, stepLimit, onStep)

	/** Coordinate velocity `dx^i/dt` of [state] into [out] (3 entries). */
	fun coordinateVelocity(state: DoubleArray, out: DoubleArray) {
		val dydt = DoubleArray(8)
		derivative(state, dydt)
		for (i in 0..2) out[i] = dydt[1 + i] / dydt[0]
	}

	companion object {
		/** The future-directed null state at [x] (t, x, y, z) heading along spatial coordinate direction [direction]. */
		fun lightlike(spacetime: ComposedSpacetime, x: DoubleArray, direction: DoubleArray): DoubleArray {
			val u = Geodesic.lightlike(spacetime, x, direction)
			val g = DoubleArray(16)
			spacetime.metric(x, g)
			val state = DoubleArray(8)
			for (mu in 0..3) {
				state[mu] = x[mu]
				var s = 0.0
				for (nu in 0..3) s += g[mu * 4 + nu] * u[4 + nu]
				state[4 + mu] = s
			}
			return state
		}
	}
}
