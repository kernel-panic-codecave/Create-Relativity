package net.kernelpanicsoft.relativity.physics

import net.kernelpanicsoft.relativity.physics.geodesic.Geodesic
import net.kernelpanicsoft.relativity.physics.spacetime.EllisWormhole
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class WormholeTest {
	private val hole = EllisWormhole(2.0)

	/** Equatorial photon from l = −20 with impact parameter [h]; returns the final l. */
	private fun photon(h: Double): Double {
		val l0 = -20.0
		val area = l0 * l0 + hole.throat * hole.throat
		val state = doubleArrayOf(0.0, l0, PI / 2, 0.0, 1.0, sqrt(1 - h * h / area), 0.0, h / area)
		Geodesic(hole, rtol = 1e-11).advance(state, 200.0) { kotlin.math.abs(it[1]) < 25.0 }
		return state[1]
	}

	@Test
	fun `light aimed inside the throat goes through, outside bounces back`() {
		assertTrue(photon(1.5) > 20.0, "impact parameter below the throat radius passes to the other side")
		assertTrue(photon(2.5) < -20.0, "impact parameter above the throat radius is reflected")
	}

	@Test
	fun `a radial traveller crosses the throat and keeps its energy`() {
		val state = doubleArrayOf(0.0, -10.0, PI / 2, 0.0, 0.0, 0.5, 0.0, 0.0)
		state[4] = sqrt(1 + 0.25) // −(u^t)² + (u^l)² = −1
		Geodesic(hole).advance(state, 100.0) { it[1] < 10.0 }
		assertTrue(state[1] >= 10.0)
		assertClose(sqrt(1.25), state[4], 1e-9, "u^t is conserved (static, g_tt = −1)")
	}
}
