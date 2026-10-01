package net.kernelpanicsoft.relativity.physics

import net.kernelpanicsoft.relativity.physics.geodesic.Geodesic
import net.kernelpanicsoft.relativity.physics.spacetime.Kerr
import net.kernelpanicsoft.relativity.physics.spacetime.Minkowski
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

class ProperTimeTest {
	private val hole = Kerr(1.0)

	@Test
	fun `static clock runs slow by the gravitational redshift`() {
		val r = 10.0
		assertClose(sqrt(1 - 2 / r), Geodesic.properTimeRate(hole, doubleArrayOf(0.0, r, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 0.0)), 1e-12)
	}

	@Test
	fun `circular orbit clock runs at sqrt(1 - 3M over r)`() {
		val r = 12.0
		val omega = 1 / (r * sqrt(r))
		val rate = Geodesic.properTimeRate(hole, doubleArrayOf(0.0, r, 0.0, 0.0), doubleArrayOf(0.0, omega * r, 0.0))
		assertClose(sqrt(1 - 3 / r), rate, 1e-12)
	}

	@Test
	fun `moving clock in flat space runs slow by gamma`() {
		assertClose(0.6, Geodesic.properTimeRate(Minkowski, DoubleArray(4), doubleArrayOf(0.8, 0.0, 0.0)), 1e-12)
	}

	@Test
	fun `standing still at the horizon stops the clock`() {
		assertEquals(0.0, Geodesic.properTimeRate(hole, doubleArrayOf(0.0, 2.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 0.0)))
	}
}
