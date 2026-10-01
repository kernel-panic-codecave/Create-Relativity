package net.kernelpanicsoft.relativity.physics.geodesic

import net.kernelpanicsoft.relativity.physics.spacetime.Spacetime
import kotlin.math.sqrt

/**
 * Geodesic motion in [spacetime]. State is `[x⁰..x³, u⁰..u³]` with `u^μ = dx^μ/dλ`; for a timelike
 * geodesic normalised to `g(u,u) = −1`, λ is proper time (in blocks; divide by c for seconds).
 */
class Geodesic(val spacetime: Spacetime, rtol: Double = 1e-9, atol: Double = 1e-12) {
	private val gamma = DoubleArray(64)

	val integrator = Dopri5(8, rtol, atol) { y, dydt ->
		spacetime.christoffel(y, gamma)
		for (mu in 0..3) dydt[mu] = y[4 + mu]
		for (lambda in 0..3) {
			var acc = 0.0
			val base = lambda * 16
			for (mu in 0..3) {
				val um = y[4 + mu]
				if (um == 0.0) continue
				for (nu in 0..3) acc += gamma[base + mu * 4 + nu] * um * y[4 + nu]
			}
			dydt[4 + lambda] = -acc
		}
	}

	/** Advances [state] by affine parameter [span]; see [Dopri5.integrate]. */
	fun advance(
		state: DoubleArray,
		span: Double,
		stepLimit: ((DoubleArray) -> Double)? = null,
		onStep: ((DoubleArray) -> Boolean)? = null,
	): Double = integrator.integrate(state, span, stepLimit, onStep)

	companion object {
		/** `g_{μν} a^μ b^ν` at [x]. */
		fun dot(spacetime: Spacetime, x: DoubleArray, a: DoubleArray, aOffset: Int, b: DoubleArray, bOffset: Int): Double {
			val g = DoubleArray(16)
			spacetime.metric(x, g)
			var sum = 0.0
			for (mu in 0..3) for (nu in 0..3) sum += g[mu * 4 + nu] * a[aOffset + mu] * b[bOffset + nu]
			return sum
		}

		/**
		 * Proper-time rate `dτ/dt = √(−g_{μν} v^μ v^ν)` of something at [x] moving with coordinate 3-velocity [v]
		 * (`v^μ = (1, v)`, c = 1): how fast its clock runs against coordinate time. 0 if that motion isn't timelike
		 * there (at or past light speed, or "at rest" inside a horizon).
		 */
		fun properTimeRate(spacetime: Spacetime, x: DoubleArray, v: DoubleArray): Double {
			val dir = doubleArrayOf(1.0, v[0], v[1], v[2])
			val n = dot(spacetime, x, dir, 0, dir, 0)
			return if (n < 0.0) sqrt(-n) else 0.0
		}

		/** `g(u, u)` of a state: −1 for a normalised timelike geodesic, 0 for a null one. */
		fun norm(spacetime: Spacetime, state: DoubleArray): Double = dot(spacetime, state, state, 4, state, 4)

		/** Covariant components `u_μ = g_{μν} u^ν` of a state's velocity. */
		fun lower(spacetime: Spacetime, state: DoubleArray): DoubleArray {
			val g = DoubleArray(16)
			spacetime.metric(state, g)
			return DoubleArray(4) { mu -> (0..3).sumOf { nu -> g[mu * 4 + nu] * state[4 + nu] } }
		}

		/**
		 * Timelike state at [x] moving with coordinate 3-velocity [v] = `dx^i/dt`, normalised so λ is proper time.
		 * Fails if that velocity is not timelike there (e.g. "at rest" inside a horizon).
		 */
		fun timelike(spacetime: Spacetime, x: DoubleArray, v: DoubleArray): DoubleArray {
			val dir = doubleArrayOf(1.0, v[0], v[1], v[2])
			val n = dot(spacetime, x, dir, 0, dir, 0)
			require(n < 0.0) { "velocity is not timelike here (g(u,u) = $n)" }
			val ut = 1.0 / sqrt(-n)
			return doubleArrayOf(x[0], x[1], x[2], x[3], ut, ut * v[0], ut * v[1], ut * v[2])
		}

		/** Null state at [x] heading along spatial coordinate direction [direction], with `u⁰ = 1`. */
		fun lightlike(spacetime: Spacetime, x: DoubleArray, direction: DoubleArray): DoubleArray {
			val g = DoubleArray(16)
			spacetime.metric(x, g)
			var a = 0.0
			var b = 0.0
			for (i in 1..3) {
				b += g[i] * direction[i - 1]
				for (j in 1..3) a += g[i * 4 + j] * direction[i - 1] * direction[j - 1]
			}
			val c = g[0]
			// a k² + 2 b k + c = 0, future-directed root.
			val k = (-b + sqrt(b * b - a * c)) / a
			return doubleArrayOf(x[0], x[1], x[2], x[3], 1.0, k * direction[0], k * direction[1], k * direction[2])
		}
	}
}
