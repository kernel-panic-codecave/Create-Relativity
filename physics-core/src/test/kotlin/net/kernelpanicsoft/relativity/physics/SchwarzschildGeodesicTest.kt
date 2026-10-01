package net.kernelpanicsoft.relativity.physics

import net.kernelpanicsoft.relativity.physics.geodesic.Geodesic
import net.kernelpanicsoft.relativity.physics.spacetime.Kerr
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/** Schwarzschild (M = 1) geodesics against textbook results. */
class SchwarzschildGeodesicTest {
	private val hole = Kerr(1.0)

	private fun circularOrbit(r: Double): DoubleArray {
		val omega = 1.0 / (r * sqrt(r)) // Kepler's law holds exactly for Schwarzschild in coordinate time
		return Geodesic.timelike(hole, doubleArrayOf(0.0, r, 0.0, 0.0), doubleArrayOf(0.0, omega * r, 0.0))
	}

	/** Largest |r − r0| over [orbits] orbits of a circular orbit at [r0] nudged outward by [kick]. */
	private fun drift(r0: Double, orbits: Double, kick: Double): Double {
		val state = circularOrbit(r0)
		state[1] += kick
		val period = 2 * PI * r0 * sqrt(r0) * sqrt(1 - 3 / r0) // proper time per orbit
		var worst = 0.0
		Geodesic(hole, rtol = 1e-11).advance(state, orbits * period, stepLimit = { 0.05 * radius(it) }) {
			worst = maxOf(worst, abs(radius(it) - r0))
			radius(it) > 1.5 && worst < 3.0
		}
		return worst
	}

	@Test
	fun `circular orbit outside the ISCO stays circular`() {
		assertTrue(drift(10.0, 5.0, 0.0) < 1e-6)
	}

	@Test
	fun `perturbed orbit outside the ISCO stays near`() {
		assertTrue(drift(8.0, 10.0, 1e-4) < 0.01)
	}

	@Test
	fun `perturbed orbit inside the ISCO runs away`() {
		assertTrue(drift(5.5, 10.0, 1e-4) > 1.0)
	}

	@Test
	fun `perihelion advances by 6 pi M over p per orbit`() {
		val p = 400.0
		val e = 0.2
		val rp = p / (1 + e)
		// Schwarzschild constants for an orbit with turning points p/(1±e).
		val l = sqrt(p * p / (p - 3 - e * e))
		val energy = sqrt(((p - 2) * (p - 2) - 4 * e * e) / (p * (p - 3 - e * e)))
		val dPhiDt = (l / (rp * rp)) / (energy / (1 - 2 / rp))
		val state = Geodesic.timelike(hole, doubleArrayOf(0.0, rp, 0.0, 0.0), doubleArrayOf(0.0, dPhiDt * rp, 0.0))

		val periapsisAngles = mutableListOf<Double>()
		var lastR = radius(state)
		var falling = false
		var unwrapped = 0.0
		var lastAngle = 0.0
		Geodesic(hole, rtol = 1e-12).advance(state, 1e7, stepLimit = { 0.02 * radius(it) }) {
			val r = radius(it)
			val angle = atan2(it[2], it[1])
			var d = angle - lastAngle
			if (d < -PI) d += 2 * PI
			if (d > PI) d -= 2 * PI
			unwrapped += d
			lastAngle = angle
			if (falling && r > lastR) periapsisAngles += unwrapped
			falling = r < lastR
			lastR = r
			periapsisAngles.size < 3
		}
		val perOrbit = (periapsisAngles[2] - periapsisAngles[0]) / 2 - 2 * PI
		assertClose(6 * PI / p, perOrbit, 0.02, "precession per orbit")
	}

	@Test
	fun `light bends by 4M over b`() {
		val b = 200.0
		val far = 1e5
		val state = Geodesic.lightlike(hole, doubleArrayOf(0.0, -far, b, 0.0), doubleArrayOf(1.0, 0.0, 0.0))
		Geodesic(hole, rtol = 1e-11).advance(state, 1e7, stepLimit = { 0.05 * radius(it) }) { it[1] < far }
		val deflection = -atan2(state[6], state[5]) // bends toward the hole, i.e. toward −y
		assertClose(4.0 / b + 15 * PI / (4 * b * b), deflection, 0.01, "deflection")
	}

	@Test
	fun `photon sphere holds light for an orbit and splits it either side`() {
		// The orbit is unstable (e-folding time 3√3 M), so only one orbit is checked on the sphere itself.
		// One orbit is t = 2π·3√3 M; with u^t = 3 at r = 3M that is ~11 in affine parameter.
		fun photonAt(r: Double, span: Double = 400.0): Double {
			val state = Geodesic.lightlike(hole, doubleArrayOf(0.0, r, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0))
			var end = r
			Geodesic(hole, rtol = 1e-12).advance(state, span, stepLimit = { 0.02 * radius(it) }) {
				end = radius(it)
				end in 1.0..20.0
			}
			return end
		}
		assertTrue(abs(photonAt(3.0, span = 11.0) - 3.0) < 1e-3, "light at 3M should stay for ~1 orbit")
		assertTrue(photonAt(2.95) < 2.0, "light just inside the photon sphere falls through the horizon")
		assertTrue(photonAt(3.05) > 10.0, "light just outside escapes")
	}

	@Test
	fun `infall from rest crosses the horizon and matches the cycloid proper time`() {
		val r0 = 10.0
		val state = Geodesic.timelike(hole, doubleArrayOf(0.0, r0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 0.0))
		var tau = 0.0
		var crossed = false
		val geodesic = Geodesic(hole, rtol = 1e-12)
		while (radius(state) > 1.0) {
			tau += geodesic.advance(state, 1e-3)
			if (radius(state) < 2.0) crossed = true
		}
		// Overshoot below r = 1 is at most one tiny step; compare against the cycloid at the reached radius.
		val r = radius(state)
		val eta = acos(2 * r / r0 - 1)
		val expected = sqrt(r0 * r0 * r0 / 8) * (eta + sin(eta))
		assertTrue(crossed)
		assertClose(expected, tau, 1e-6, "proper time to r=$r")
	}
}
