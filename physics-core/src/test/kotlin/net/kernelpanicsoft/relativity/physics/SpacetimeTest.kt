package net.kernelpanicsoft.relativity.physics

import net.kernelpanicsoft.relativity.physics.spacetime.EllisWormhole
import net.kernelpanicsoft.relativity.physics.spacetime.Kerr
import net.kernelpanicsoft.relativity.physics.spacetime.Spacetime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** Exact (dual-number / closed-form) Christoffels must match a finite-difference connection from the metric. */
class SpacetimeTest {
	private fun numericChristoffel(st: Spacetime, x: DoubleArray, h: Double = 1e-5): DoubleArray {
		val g = DoubleArray(16).also { st.metric(x, it) }
		val gInv = invert4(g)
		val dg = DoubleArray(64)
		for (k in 0..3) {
			val xp = x.copyOf().also { it[k] += h }
			val xm = x.copyOf().also { it[k] -= h }
			val gp = DoubleArray(16).also { st.metric(xp, it) }
			val gm = DoubleArray(16).also { st.metric(xm, it) }
			for (i in 0..15) dg[k * 16 + i] = (gp[i] - gm[i]) / (2 * h)
		}
		return DoubleArray(64) { idx ->
			val l = idx / 16
			val mu = (idx / 4) % 4
			val nu = idx % 4
			0.5 * (0..3).sumOf { s -> gInv[l * 4 + s] * (dg[mu * 16 + s * 4 + nu] + dg[nu * 16 + s * 4 + mu] - dg[s * 16 + mu * 4 + nu]) }
		}
	}

	private fun assertConnectionMatches(st: Spacetime, x: DoubleArray) {
		val exact = DoubleArray(64).also { st.christoffel(x, it) }
		val numeric = numericChristoffel(st, x)
		for (i in 0..63) {
			assertTrue(abs(exact[i] - numeric[i]) < 1e-7, "Γ[$i] exact ${exact[i]} vs numeric ${numeric[i]} at ${x.toList()}")
		}
	}

	@Test
	fun `schwarzschild connection is exact`() =
		assertConnectionMatches(Kerr(1.0), doubleArrayOf(0.0, 3.0, -2.0, 4.5))

	@Test
	fun `kerr connection is exact off the equator`() =
		assertConnectionMatches(Kerr(1.0, 0.7), doubleArrayOf(0.0, 2.5, 1.5, -1.8))

	@Test
	fun `kerr connection is exact inside the horizon`() =
		assertConnectionMatches(Kerr(1.0, 0.5), doubleArrayOf(0.0, 0.9, 0.6, 0.4))

	@Test
	fun `wormhole connection is exact`() =
		assertConnectionMatches(EllisWormhole(2.0), doubleArrayOf(0.0, -1.3, 1.1, 0.4))

	@Test
	fun `kerr reduces to schwarzschild radius when a is zero`() {
		val k = Kerr(1.0)
		assertClose(5.0, k.radius(3.0, 0.0, 4.0), 1e-14)
		assertClose(2.0, k.horizonRadius, 1e-14)
	}

	companion object {
		fun invert4(m: DoubleArray): DoubleArray {
			val a = Array(4) { r -> DoubleArray(8) { c -> if (c < 4) m[r * 4 + c] else if (c - 4 == r) 1.0 else 0.0 } }
			for (col in 0..3) {
				val pivot = (col..3).maxBy { abs(a[it][col]) }
				val t = a[col]; a[col] = a[pivot]; a[pivot] = t
				val p = a[col][col]
				for (c in 0..7) a[col][c] /= p
				for (r in 0..3) if (r != col) {
					val f = a[r][col]
					for (c in 0..7) a[r][c] -= f * a[col][c]
				}
			}
			return DoubleArray(16) { a[it / 4][4 + it % 4] }
		}
	}
}
