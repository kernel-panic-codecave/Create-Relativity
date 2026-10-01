package net.kernelpanicsoft.relativity.physics

import net.kernelpanicsoft.relativity.physics.geodesic.CoordinateAcceleration
import net.kernelpanicsoft.relativity.physics.spacetime.Kerr
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class CoordinateAccelerationTest {
	private val acc = CoordinateAcceleration(Kerr(1.0))

	@Test
	fun `body momentarily at rest feels the Schwarzschild coordinate pull`() {
		val r = 10.0
		val out = DoubleArray(3)
		acc.compute(doubleArrayOf(0.0, r, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 0.0), out)
		// d²r/dt² = −(M/r²)(1 − 2M/r), identical in Kerr–Schild time at the turning point.
		assertClose(-(1 / (r * r)) * (1 - 2 / r), out[0], 1e-12, "radial")
		assertTrue(kotlin.math.abs(out[1]) < 1e-15 && kotlin.math.abs(out[2]) < 1e-15)
	}

	@Test
	fun `circular orbit needs exactly the centripetal acceleration`() {
		val r = 12.0
		val omega = 1 / (r * sqrt(r))
		val out = DoubleArray(3)
		acc.compute(doubleArrayOf(0.0, r, 0.0, 0.0), doubleArrayOf(0.0, omega * r, 0.0), out)
		assertClose(-omega * omega * r, out[0], 1e-12, "centripetal")
	}
}
