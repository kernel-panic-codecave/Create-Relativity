package net.kernelpanicsoft.relativity.physics

import net.kernelpanicsoft.relativity.physics.geodesic.NullRay
import net.kernelpanicsoft.relativity.physics.lens.SchwarzschildImages
import net.kernelpanicsoft.relativity.physics.spacetime.Kerr
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * vertex_lens.glsl moves each vertex to the direction its light arrives from. This mirrors that formula (camera at the
 * origin, hole at h, r_s = 2) and checks it end to end: light traced back from the camera along the drawn direction
 * must pass through the vertex. (Without converting the table's static-observer angle to a coordinate direction it
 * missed by up to 1.6 blocks.)
 */
class VertexLensMathTest {
	private val table by lazy { SchwarzschildImages.buildTable(64, 64, 128) }

	private fun sub(a: DoubleArray, b: DoubleArray) = DoubleArray(3) { a[it] - b[it] }
	private fun dot(a: DoubleArray, b: DoubleArray) = (0..2).sumOf { a[it] * b[it] }
	private fun scale(a: DoubleArray, k: Double) = DoubleArray(3) { a[it] * k }
	private fun length(a: DoubleArray) = sqrt(dot(a, a))

	// Shader: relativityLens, with the table sampled as SchwarzschildImages.sample does (NaN: no image).
	private fun drawnDirection(p: DoubleArray, h: DoubleArray, rs: Double): DoubleArray? {
		val rc = length(h)
		val fromHole = sub(p, h)
		val rp = length(fromHole)
		val u0 = rs / rc
		val uP = rs / rp
		val e1 = scale(h, -1.0 / rc)
		val radial = scale(fromHole, 1.0 / rp)
		val cosPhi = dot(radial, e1).coerceIn(-1.0, 1.0)
		val side = sub(radial, scale(e1, cosPhi))
		val e2 = scale(side, 1.0 / length(side))
		val phi = acos(cosPhi)
		val local = SchwarzschildImages.sample(table, 64, 64, 128, u0, uP, phi)
		if (local.isNaN()) return null
		// The table's angle is in a static observer's frame; the camera's is a coordinate direction.
		val psi = kotlin.math.atan2(sin(local), cos(local) * sqrt(1.0 - u0))
		return DoubleArray(3) { -cos(psi) * e1[it] + sin(psi) * e2[it] }
	}

	@Test
	fun `light seen along the drawn direction passes through the vertex`() {
		val rs = 2.0
		val h = doubleArrayOf(15.0, 1.0, 0.5) // hole relative to the camera
		val failures = mutableListOf<String>()
		var checked = 0
		val random = java.util.Random(3)
		repeat(60) {
			val p = DoubleArray(3) { h[it] + random.nextGaussian() * 12.0 }
			if (length(sub(p, h)) < 2.5 * rs) return@repeat
			val dir = drawnDirection(p, h, rs) ?: return@repeat
			// Physics frame centred on the hole.
			val origin = scale(h, -1.0)
			val path = NullRay.trace(Kerr(rs / 2.0), origin, dir, 3.0 * length(p) + 40.0, 1.02 * rs, maxSegment = 0.05, arriving = true)
			val target = sub(p, h)
			val miss = path.points.minOf { length(sub(it, target)) }
			checked++
			if (miss > 0.05 * maxOf(1.0, length(p) / 15.0)) failures += "P=${p.toList().map { "%.1f".format(it) }} misses by ${"%.3f".format(miss)}"
		}
		assertTrue(checked > 30, "only $checked points checked")
		assertTrue(failures.isEmpty(), "${failures.size}/$checked: " + failures.take(6).joinToString("; "))
	}
}
