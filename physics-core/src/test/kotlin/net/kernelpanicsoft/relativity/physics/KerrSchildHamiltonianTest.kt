package net.kernelpanicsoft.relativity.physics

import net.kernelpanicsoft.relativity.physics.geodesic.NullRay
import net.kernelpanicsoft.relativity.physics.spacetime.Kerr
import kotlin.math.acos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lens shader marches Kerr light rays with the Kerr–Schild Hamiltonian (g^μν = η^μν − h l^μ l^ν, finite-difference
 * gradient, RK4 with steps of 0.1 r). This mirrors that code in Kotlin and checks it against the Christoffel-based
 * [NullRay] tracer, so the shader's maths is verified without a GPU.
 */
class KerrSchildHamiltonianTest {
	private class V(val x: Double, val y: Double, val z: Double) {
		operator fun plus(o: V) = V(x + o.x, y + o.y, z + o.z)
		operator fun minus(o: V) = V(x - o.x, y - o.y, z - o.z)
		operator fun times(k: Double) = V(x * k, y * k, z * k)
		fun dot(o: V) = x * o.x + y * o.y + z * o.z
		fun length() = sqrt(dot(this))
	}

	// Shader: kerrProfile
	private fun profile(x: V, m: Double, a: Double): Triple<Double, Double, V> {
		val a2 = a * a
		val w = x.dot(x) - a2
		val r2 = 0.5 * w + sqrt(0.25 * w * w + a2 * x.z * x.z)
		val r = sqrt(r2)
		val h = 2.0 * m * r * r2 / (r2 * r2 + a2 * x.z * x.z)
		val d = r2 + a2
		return Triple(r, h, V((r * x.x + a * x.y) / d, (r * x.y - a * x.x) / d, x.z / r))
	}

	private fun potential(x: V, p: V, pt: Double, m: Double, a: Double): Double {
		val (_, h, l) = profile(x, m, a)
		val lp = l.dot(p) - pt
		return h * lp * lp
	}

	private fun derivative(x: V, p: V, pt: Double, m: Double, a: Double): Pair<V, V> {
		val (r, h, l) = profile(x, m, a)
		val lp = l.dot(p) - pt
		val dx = p - l * (h * lp)
		val e = 1e-3 * maxOf(r, m)
		val ex = V(e, 0.0, 0.0)
		val ey = V(0.0, e, 0.0)
		val ez = V(0.0, 0.0, e)
		val dp = V(
			potential(x + ex, p, pt, m, a) - potential(x - ex, p, pt, m, a),
			potential(x + ey, p, pt, m, a) - potential(x - ey, p, pt, m, a),
			potential(x + ez, p, pt, m, a) - potential(x - ez, p, pt, m, a),
		) * (1.0 / (4.0 * e))
		return dx to dp
	}

	/** Shader: kerrMarch from x along coordinate direction d until past [escape]; returns the exit direction. */
	private fun march(start: V, d: V, m: Double, a: Double, escape: Double): V? {
		var x = start
		val (_, h0, l0) = profile(x, m, a)
		val ld = l0.dot(d)
		val qa = 1.0 + h0 * ld * ld
		val qb = 2.0 * h0 * ld
		val qc = h0 - 1.0
		val k = (-qb + sqrt(qb * qb - 4.0 * qa * qc)) / (2.0 * qa)
		val lu = 1.0 + k * ld
		val pt = -1.0 + h0 * lu
		var p = d * k + l0 * (h0 * lu)
		val horizon = m + sqrt(m * m - a * a)
		repeat(400) {
			val (r, _, _) = profile(x, m, a)
			if (r < horizon * 1.02) return null
			val (k1x, k1p) = derivative(x, p, pt, m, a)
			if (x.length() > escape && x.dot(k1x) > 0.0) return k1x * (1.0 / k1x.length())
			val dl = 0.1 * maxOf(r, 0.5 * m) / k1x.length()
			val (k2x, k2p) = derivative(x + k1x * (0.5 * dl), p + k1p * (0.5 * dl), pt, m, a)
			val (k3x, k3p) = derivative(x + k2x * (0.5 * dl), p + k2p * (0.5 * dl), pt, m, a)
			val (k4x, k4p) = derivative(x + k3x * dl, p + k3p * dl, pt, m, a)
			x = x + (k1x + k2x * 2.0 + k3x * 2.0 + k4x) * (dl / 6.0)
			p = p + (k1p + k2p * 2.0 + k3p * 2.0 + k4p) * (dl / 6.0)
		}
		return null
	}

	@Test
	fun `shader march matches the Christoffel tracer around a spinning hole`() {
		val m = 1.0
		val a = 0.9
		val escape = 80.0
		for (b in listOf(6.0, 8.0, -7.0, 12.0)) {
			val start = V(-60.0, b, 0.0)
			val d = V(1.0, 0.0, 0.0)
			val shader = march(start, d, m, a, escape)
			val reference = NullRay.trace(Kerr(m, a), doubleArrayOf(start.x, start.y, start.z), doubleArrayOf(1.0, 0.0, 0.0), 400.0, 1.05 * (m + sqrt(m * m - a * a)), maxSegment = 2.0)
			if (reference.end == NullRay.End.CAPTURED) {
				assertEquals(null, shader, "b = $b: reference captured, shader escaped")
				continue
			}
			assertTrue(shader != null, "b = $b: shader captured, reference escaped")
			val pts = reference.points
			val p0 = pts[pts.size - 2]
			val p1 = pts.last()
			val ref = V(p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]).let { it * (1.0 / it.length()) }
			val angle = acos(shader!!.dot(ref).coerceIn(-1.0, 1.0))
			assertTrue(angle < 0.01, "b = $b: exit directions differ by $angle rad")
		}
	}

	@Test
	fun `prograde and retrograde rays bend differently`() {
		// Frame dragging: a ray passing on the side moving with the spin bends less than one passing against it.
		val m = 1.0
		val a = 0.9
		val with = march(V(-60.0, -8.0, 0.0), V(1.0, 0.0, 0.0), m, a, 80.0)!!
		val against = march(V(-60.0, 8.0, 0.0), V(1.0, 0.0, 0.0), m, a, 80.0)!!
		val bendWith = acos(with.x)
		val bendAgainst = acos(against.x)
		assertTrue(bendWith != bendAgainst && kotlin.math.abs(bendWith - bendAgainst) > 0.02, "bends $bendWith vs $bendAgainst")
	}
}
