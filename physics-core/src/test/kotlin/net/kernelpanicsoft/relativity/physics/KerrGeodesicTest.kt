package net.kernelpanicsoft.relativity.physics

import net.kernelpanicsoft.relativity.physics.geodesic.Geodesic
import net.kernelpanicsoft.relativity.physics.orbit.KerrOrbits
import net.kernelpanicsoft.relativity.physics.spacetime.Kerr
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class KerrGeodesicTest {
	@Test
	fun `ISCO and photon orbit formulas hit known values`() {
		assertClose(6.0, KerrOrbits.isco(1.0, 0.0), 1e-12)
		assertClose(9.0, KerrOrbits.isco(1.0, 1.0, prograde = false), 1e-12)
		assertClose(1.0, KerrOrbits.isco(1.0, 1.0), 1e-9)
		assertClose(1.2370, KerrOrbits.isco(1.0, 0.998), 1e-3) // Thorne's limit
		assertClose(3.0, KerrOrbits.photonOrbit(1.0, 0.0), 1e-12)
		assertClose(4.0, KerrOrbits.photonOrbit(1.0, 1.0, prograde = false), 1e-12)
	}

	/** Circular equatorial orbit at Boyer–Lindquist radius [r]; Kerr–Schild puts it at cylindrical radius √(r² + a²). */
	private fun circular(hole: Kerr, r: Double, prograde: Boolean): DoubleArray {
		val omega = KerrOrbits.angularVelocity(hole.m, hole.chi, r, prograde)
		val rho = sqrt(r * r + hole.a * hole.a)
		return Geodesic.timelike(hole, doubleArrayOf(0.0, rho, 0.0, 0.0), doubleArrayOf(0.0, omega * rho, 0.0))
	}

	private fun drift(hole: Kerr, r: Double, prograde: Boolean, kick: Double, orbits: Double = 8.0): Double {
		val state = circular(hole, r, prograde)
		val rho0 = radius(state)
		state[1] += kick
		var worst = 0.0
		Geodesic(hole, rtol = 1e-11).advance(state, orbits * 2 * PI * r * sqrt(r), stepLimit = { 0.05 * radius(it) }) {
			worst = maxOf(worst, abs(radius(it) - rho0))
			radius(it) > hole.horizonRadius && worst < 3.0
		}
		return worst
	}

	@Test
	fun `circular orbits use the spin direction correctly`() {
		val hole = Kerr(1.0, 0.5)
		// a = 0.5: prograde ISCO ≈ 4.23M, retrograde ISCO ≈ 7.55M. At 5M only prograde is stable.
		assertTrue(drift(hole, 5.0, prograde = true, kick = 0.0) < 1e-6, "exact prograde circular orbit")
		assertTrue(drift(hole, 5.0, prograde = true, kick = 1e-4) < 0.02, "prograde at 5M is stable")
		assertTrue(drift(hole, 5.0, prograde = false, kick = 1e-4) > 1.0, "retrograde at 5M is unstable")
	}

	@Test
	fun `frame dragging spins up a zero angular momentum faller in the spin direction`() {
		val hole = Kerr(1.0, 0.9)
		// Zero angular momentum: u_φ = x u_y − y u_x = 0. Start from u^i = 0 at r = 6 on the x axis and solve.
		val state = Geodesic.timelike(hole, doubleArrayOf(0.0, 6.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 0.0))
		val low = Geodesic.lower(hole, state)
		// Remove angular momentum by nudging u^y until u_φ = x·u_y vanishes (u_y is linear in u^y).
		val g = DoubleArray(16).also { hole.metric(state, it) }
		state[6] -= low[2] / g[2 * 4 + 2]
		val norm = Geodesic.norm(hole, state)
		for (i in 4..7) state[i] /= sqrt(-norm)
		var angle = 0.0
		Geodesic(hole, rtol = 1e-11).advance(state, 100.0, stepLimit = { 0.05 * radius(it) }) {
			angle = atan2(it[2], it[1])
			radius(it) > 2.5
		}
		assertTrue(angle > 0.05, "a > 0 should drag counter-clockwise about +z, got angle $angle")
	}

	@Test
	fun `energy, angular momentum and norm are conserved on a generic orbit`() {
		val hole = Kerr(1.0, 0.7)
		// Bound and well clear of capture: circular speed at r = 20 is ≈ 0.224, L ≈ 4.2M.
		val state = Geodesic.timelike(hole, doubleArrayOf(0.0, 20.0, 0.0, 3.0), doubleArrayOf(0.0, 0.21, 0.04))
		fun energy(s: DoubleArray) = -Geodesic.lower(hole, s)[0]
		fun lz(s: DoubleArray) = Geodesic.lower(hole, s).let { u -> s[1] * u[2] - s[2] * u[1] }
		val e0 = energy(state)
		val l0 = lz(state)
		val geodesic = Geodesic(hole, rtol = 1e-12, atol = 1e-14)
		val covered = geodesic.advance(state, 20_000.0, stepLimit = { 0.05 * radius(it) }) { radius(it) > 4.0 }
		assertClose(20_000.0, covered, 1e-12, "orbit must stay bound for the whole run")
		assertTrue(geodesic.integrator.acceptedSteps > 15_000, "should be a long run")
		assertClose(e0, energy(state), 1e-9, "energy")
		assertClose(l0, lz(state), 1e-8, "L_z")
		assertTrue(abs(Geodesic.norm(hole, state) + 1) < 1e-8, "u stays normalised")
	}
}
