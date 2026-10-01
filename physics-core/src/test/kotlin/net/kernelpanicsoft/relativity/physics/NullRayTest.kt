package net.kernelpanicsoft.relativity.physics

import net.kernelpanicsoft.relativity.physics.geodesic.NullRay
import net.kernelpanicsoft.relativity.physics.spacetime.Kerr
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NullRayTest {
	/** A ray passing at impact parameter b bends by 4M/b + (15π/4)(M/b)² + … (second-order weak-field deflection). */
	@Test
	fun `distant ray bends by the weak-field deflection`() {
		val m = 1.0
		val b = 40.0
		val path = NullRay.trace(Kerr(m), doubleArrayOf(-2000.0, b, 0.0), doubleArrayOf(1.0, 0.0, 0.0), 4000.0, 2.0 * m, maxSegment = 20.0)
		assertEquals(NullRay.End.LENGTH, path.end)
		val p = path.points
		val a = p[p.size - 2]
		val z = p.last()
		val deflection = -atan2(z[1] - a[1], z[0] - a[0])
		val expected = 4.0 * m / b + 15.0 * PI / 4.0 * (m / b) * (m / b)
		assertTrue(abs(deflection - expected) / expected < 0.03, "deflection $deflection, expected ≈ $expected")
	}

	@Test
	fun `ray aimed at the hole is captured`() {
		val path = NullRay.trace(Kerr(1.0), doubleArrayOf(-20.0, 0.5, 0.0), doubleArrayOf(1.0, 0.0, 0.0), 100.0, 2.0)
		assertEquals(NullRay.End.CAPTURED, path.end)
	}

	@Test
	fun `points are spaced at most the segment length apart`() {
		val path = NullRay.trace(Kerr(1.0), doubleArrayOf(-10.0, 4.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0), 8.0, 2.0, maxSegment = 0.25)
		for (i in 1 until path.points.size) {
			val a = path.points[i - 1]
			val c = path.points[i]
			val d = kotlin.math.sqrt((c[0] - a[0]) * (c[0] - a[0]) + (c[1] - a[1]) * (c[1] - a[1]) + (c[2] - a[2]) * (c[2] - a[2]))
			assertTrue(d <= 0.26, "segment $i is $d long")
		}
	}
}
