package net.kernelpanicsoft.relativity.physics

import net.kernelpanicsoft.relativity.physics.lens.SchwarzschildLens
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class SchwarzschildLensTest {
	/** Viewing angle at camera distance [rc] (r_s units) that gives impact parameter [b]. */
	private fun psiFor(rc: Double, b: Double) = asin(b * sqrt(1 - 1 / rc) / rc)

	@Test
	fun `distant light bends by 2 r_s over b`() {
		val rc = 1e5
		val b = 200.0
		val psi = psiFor(rc, b)
		val deflection = SchwarzschildLens.escapeAngle(1 / rc, psi) - (PI - psi)
		// 4M/b + 15πM²/4b² with M = 1/2; the half of the bending before a camera this far out is negligible.
		assertClose(2 / b + 15 * PI / (16 * b * b), deflection, 0.01, "deflection")
	}

	@Test
	fun `shadow edge sits at the critical impact parameter`() {
		val rc = 1000.0
		val critical = 1.5 * sqrt(3.0)
		assertTrue(SchwarzschildLens.escapeAngle(1 / rc, psiFor(rc, critical * 0.98)).isNaN(), "inside the shadow")
		assertTrue(!SchwarzschildLens.escapeAngle(1 / rc, psiFor(rc, critical * 1.02)).isNaN(), "just outside it")
	}

	@Test
	fun `near the edge light loops round the hole before escaping`() {
		val rc = 1000.0
		val phi = SchwarzschildLens.escapeAngle(1 / rc, psiFor(rc, 1.5 * sqrt(3.0) * 1.001))
		assertTrue(phi > 2 * PI, "a ray grazing the photon sphere should wrap at least once, got $phi")
	}

	@Test
	fun `looking away from the hole barely bends anything`() {
		val phi = SchwarzschildLens.escapeAngle(0.01, 0.9 * PI)
		assertTrue(kotlin.math.abs(phi - 0.1 * PI) < 0.02, "outward ray at 100 r_s, got ${phi - 0.1 * PI}")
	}

	@Test
	fun `escape angle is smooth looking away from the hole`() {
		// A seam here shows up on screen as a disc around the point directly opposite the hole.
		val u0 = 2.0 / 118.0
		val step = 0.0005
		val psis = generateSequence(0.85 * PI) { it + step }.takeWhile { it < PI - step }.toList()
		val bends = psis.map { SchwarzschildLens.escapeAngle(u0, it) - (PI - it) }
		for (i in 1 until bends.size - 1) {
			val curvature = abs(bends[i + 1] - 2 * bends[i] + bends[i - 1])
			assertTrue(curvature < 1e-7, "bend jumps near psi=${psis[i]}: second difference $curvature")
		}
	}

	@Test
	fun `table marks captured rays`() {
		val table = SchwarzschildLens.buildTable(16, 16)
		// y = 0 is ψ = 0: straight at the hole, always captured.
		for (i in 0 until 16) assertTrue(table[i * 2 + 1] == 1f)
	}
}
