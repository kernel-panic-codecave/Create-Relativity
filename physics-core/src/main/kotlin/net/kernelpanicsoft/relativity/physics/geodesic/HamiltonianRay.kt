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

	/**
	 * Light as a polyline through space, like [NullRay.trace] but through every source: from spatial point [origin]
	 * (time 0) along coordinate direction [direction] until the path is [maxLength] long or some source captures it.
	 * Points are at most [maxSegment] apart. With [arriving], the light seen at [origin] looking along [direction],
	 * followed back in time (it differs from light sent out wherever spacetime isn't time-symmetric: spin, moving sources).
	 */
	fun trace(origin: DoubleArray, direction: DoubleArray, maxLength: Double, maxSegment: Double = 0.25, arriving: Boolean = false): NullRay.Path {
		val x = doubleArrayOf(0.0, origin[0], origin[1], origin[2])
		val state = if (arriving) {
			// H is even in p: −p retraces the same path backwards.
			lightlike(spacetime, x, DoubleArray(3) { -direction[it] }).also { for (mu in 4..7) it[mu] = -it[mu] }
		} else {
			lightlike(spacetime, x, direction)
		}
		val points = mutableListOf(origin.copyOf())
		var length = 0.0
		var end = NullRay.End.STALLED
		val dydt = DoubleArray(8)
		try {
			advance(state, Double.MAX_VALUE, stepLimit = { s ->
				derivative(s, dydt)
				maxSegment / kotlin.math.sqrt(dydt[1] * dydt[1] + dydt[2] * dydt[2] + dydt[3] * dydt[3]).coerceAtLeast(1e-9)
			}) { s ->
				val last = points.last()
				val dx = s[1] - last[0]
				val dy = s[2] - last[1]
				val dz = s[3] - last[2]
				length += kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
				points += doubleArrayOf(s[1], s[2], s[3])
				when {
					spacetime.captures(s) -> { end = NullRay.End.CAPTURED; false }
					length >= maxLength -> { end = NullRay.End.LENGTH; false }
					else -> true
				}
			}
		} catch (e: IllegalArgumentException) {
			// Traced back, light that fell in only approaches the horizon; the step size underflows there.
			end = NullRay.End.CAPTURED
		}
		return NullRay.Path(points, end)
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
