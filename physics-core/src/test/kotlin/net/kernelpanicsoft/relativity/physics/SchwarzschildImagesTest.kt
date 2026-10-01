package net.kernelpanicsoft.relativity.physics

import net.kernelpanicsoft.relativity.physics.lens.SchwarzschildImages
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class SchwarzschildImagesTest {
	private val n0 = 64
	private val nP = 64
	private val nPhi = 128
	private val table by lazy { SchwarzschildImages.buildTable(n0, nP, nPhi) }

	/** The angle at which the ray launched at [psi] from u0 first reaches uP (by the same crossing order the table uses). */
	private fun reached(u0: Double, uP: Double, psi: Double, phi: Double): Double {
		val hits = SchwarzschildImages.crossings(u0, psi, doubleArrayOf(uP))[0]
		// The crossing closest to the target angle: that's the one this ψ was chosen for.
		return hits.filter { !it.isNaN() }.minByOrNull { abs(it - phi) } ?: Double.NaN
	}

	@Test
	fun `table launch angles send light through the point`() {
		val cases = listOf(
			doubleArrayOf(20.0, 40.0, 2.5), // farther than the camera, nearly behind the hole
			doubleArrayOf(20.0, 40.0, 1.0),
			doubleArrayOf(20.0, 5.0, 2.0), // closer in, round the side
			doubleArrayOf(20.0, 5.0, 0.6),
			doubleArrayOf(8.0, 3.0, 1.5), // strong field
			doubleArrayOf(6.0, 12.0, 3.0),
		)
		var checked = 0
		for ((rc, rp, phi) in cases.map { Triple(it[0], it[1], it[2]) }) {
			val u0 = 1.0 / rc
			val uP = 1.0 / rp
			val psi = SchwarzschildImages.sample(table, n0, nP, nPhi, u0, uP, phi)
			assertTrue(!psi.isNaN(), "no image for r_c=$rc r_P=$rp Φ=$phi")
			val got = reached(u0, uP, psi, phi)
			assertTrue(abs(got - phi) < 0.03, "r_c=$rc r_P=$rp Φ=$phi: ψ=$psi reaches r_P at $got")
			checked++
		}
		assertTrue(checked == cases.size)
	}

	@Test
	fun `without bending the launch angle is the flat-space one`() {
		// Far from the hole (both distances huge) light goes straight: ψ = angle of the triangle at the camera.
		val rc = 5000.0
		val rp = 6000.0
		val phi = 0.3
		val psi = SchwarzschildImages.sample(table, n0, nP, nPhi, 1.0 / rc, 1.0 / rp, phi)
		val px = rp * kotlin.math.cos(phi) - rc
		val py = rp * kotlin.math.sin(phi)
		val flat = PI - kotlin.math.atan2(py, px) // from the inward radial (−x) toward P
		assertTrue(abs(psi - flat) < 0.02, "ψ=$psi, flat-space $flat")
	}

	@Test
	fun `random points round-trip through traced rays`() {
		val random = java.util.Random(7)
		val errors = ArrayList<Double>()
		var missing = 0
		repeat(400) {
			val rc = 3.0 + random.nextDouble() * 200.0
			val rp = 1.6 + random.nextDouble() * 300.0
			val phi = random.nextDouble() * 0.95 * PI
			val psi = SchwarzschildImages.sample(table, n0, nP, nPhi, 1.0 / rc, 1.0 / rp, phi)
			if (psi.isNaN()) {
				missing++
				return@repeat
			}
			errors += abs(reached(1.0 / rc, 1.0 / rp, psi, phi) - phi)
		}
		errors.sort()
		val p95 = errors[(errors.size * 0.95).toInt()]
		val worst = errors.last()
		println("round trip: ${errors.size} images, $missing without; median ${errors[errors.size / 2]}, p95 $p95, worst $worst")
		assertTrue(p95 < 0.02, "95th percentile round-trip error $p95 rad")
		assertTrue(missing < 40, "$missing of 400 random points had no image")
	}

	@Test
	fun `a point inside the horizon has no image`() {
		val psi = SchwarzschildImages.sample(table, n0, nP, nPhi, 1.0 / 20.0, 1.25, 1.0) // r = 0.8 r_s
		assertTrue(psi.isNaN(), "got ψ=$psi")
	}
}
