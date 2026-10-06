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

	/**
	 * Light sent from A arrives at B; looking back from B along where it came from, the arriving trace returns to A. Around
	 * a spinning hole the outgoing trace from B goes elsewhere (frame dragging isn't time-symmetric).
	 */
	@Test
	fun `arriving trace retraces the light that arrived`() {
		val kerr = Kerr(1.0, 0.9)
		val a = doubleArrayOf(-12.0, 7.0, 1.0)
		val sent = NullRay.trace(kerr, a, doubleArrayOf(1.0, 0.0, 0.0), 30.0, 2.0, maxSegment = 0.05)
		assertEquals(NullRay.End.LENGTH, sent.end)
		val p = sent.points
		val b = p.last()
		val back = DoubleArray(3) { p[p.size - 2][it] - b[it] }
		val norm = kotlin.math.sqrt(back.sumOf { it * it })
		for (i in 0..2) back[i] /= norm

		fun closest(path: NullRay.Path) = path.points.minOf { q -> kotlin.math.sqrt((0..2).sumOf { (q[it] - a[it]) * (q[it] - a[it]) }) }
		val arriving = NullRay.trace(kerr, b, back, 32.0, 2.0, maxSegment = 0.05, arriving = true)
		val outgoing = NullRay.trace(kerr, b, back, 32.0, 2.0, maxSegment = 0.05)
		assertTrue(closest(arriving) < 0.05, "arriving trace misses A by ${closest(arriving)}")
		assertTrue(closest(outgoing) > 0.3, "outgoing trace also passes A (${closest(outgoing)}): no frame-dragging asymmetry to test")
	}

	@Test
	fun `arriving and outgoing traces coincide around a static hole`() {
		val schwarzschild = Kerr(1.0)
		val origin = doubleArrayOf(-12.0, 7.0, 1.0)
		val arriving = NullRay.trace(schwarzschild, origin, doubleArrayOf(1.0, 0.0, 0.0), 30.0, 2.0, maxSegment = 0.05, arriving = true)
		val outgoing = NullRay.trace(schwarzschild, origin, doubleArrayOf(1.0, 0.0, 0.0), 30.0, 2.0, maxSegment = 0.05)
		val end = (0..2).sumOf { (arriving.points.last()[it] - outgoing.points.last()[it]).let { d -> d * d } }
		assertTrue(kotlin.math.sqrt(end) < 0.05, "end points differ by ${kotlin.math.sqrt(end)}")
	}
}
