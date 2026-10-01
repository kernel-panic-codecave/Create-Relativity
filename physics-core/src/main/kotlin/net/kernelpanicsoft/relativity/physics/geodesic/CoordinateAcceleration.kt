package net.kernelpanicsoft.relativity.physics.geodesic

import net.kernelpanicsoft.relativity.physics.spacetime.Spacetime

/**
 * Geodesic motion re-expressed as `d²x^i/dt²` in coordinate time, for integrators that step positions and
 * velocities in t (Sable's rigid bodies, entity movement) instead of in proper time.
 *
 * With `v^α = (1, dx^i/dt)`: `a^i = −Γ^i_{αβ} v^α v^β + Γ^0_{αβ} v^α v^β v^i`. This is exact: integrating it
 * reproduces the geodesic, including precession, frame dragging and horizon crossing.
 */
class CoordinateAcceleration(private val spacetime: Spacetime) {
	private val gamma = DoubleArray(64)
	private val v = DoubleArray(4)

	/** Writes `d²x^i/dt²` at coordinates [x] (4 entries) with coordinate velocity [velocity] (3 entries) into [out]. */
	fun compute(x: DoubleArray, velocity: DoubleArray, out: DoubleArray) {
		spacetime.christoffel(x, gamma)
		v[0] = 1.0
		v[1] = velocity[0]
		v[2] = velocity[1]
		v[3] = velocity[2]
		val time = contract(0)
		for (i in 1..3) out[i - 1] = -contract(i) + time * v[i]
	}

	private fun contract(lambda: Int): Double {
		var sum = 0.0
		val base = lambda * 16
		for (a in 0..3) {
			val va = v[a]
			if (va == 0.0) continue
			for (b in 0..3) sum += gamma[base + a * 4 + b] * va * v[b]
		}
		return sum
	}
}
