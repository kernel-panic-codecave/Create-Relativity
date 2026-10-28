package net.kernelpanicsoft.relativity.physics.lens.field

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * A frame's light, tabulated: for a grid of view directions from [camera] (gnomonic over a view [right], [up],
 * [forward] with half-tangents [tanX], [tanY]: texel (i, j) looks along forward + right (2 (i + ½) / [width] - 1) tanX +
 * up (2 (j + ½) / [height] - 1) tanY), where each one's light, marched the whole way through [field] (from the camera's
 * coordinate direction, [lens]'s conversion), first crosses each of [shells] spheres round the camera (radii from
 * [near] to [far], logarithmic). (Marched, not composed: the composer's straight pieces get the light's final heading
 * right but not where it is inside the sources' near spheres, where the geometry is.) A point's image is found by inverting it (Newton on
 * the table, interpolated across the grid and between the two shells round the point's distance): what the vertex lens
 * does per vertex, at a few table reads a step instead of a march.
 */
class LightTable(
	val lens: FieldLens,
	val field: OrbitField,
	val camera: V3,
	val right: V3, val up: V3, val forward: V3,
	val tanX: Double, val tanY: Double,
	val width: Int, val height: Int,
	val shells: Int = 32, val near: Double = 0.5, val far: Double = 512.0,
) {
	/** Shell k's radius. */
	fun radius(k: Int): Double = near * exp(ln(far / near) * k / (shells - 1))

	/** Where texel (i, j)'s light crosses shell k (camera-relative), or null: x, y, z per (k, j, i); and its heading there. */
	private val crossings = DoubleArray(shells * width * height * 3) { Double.NaN }
	private val headings = DoubleArray(shells * width * height * 3) { Double.NaN }

	init {
		for (j in 0 until height) for (i in 0 until width) {
			val d = direction(i + 0.5, j + 0.5)
			val path = field.trace(camera, lens.coordinate(camera, d), reach = far, record = true, recordEvery = 0.0)
			// The shells the path crosses going out, in turn (each the first time it reaches it).
			var k = 0
			for (n in 1 until path.points.size) {
				val a = path.points[n - 1] - camera
				val b = path.points[n] - camera
				while (k < shells && b.length >= radius(k)) {
					val r = radius(k)
					// (Linear between the two points: they're a small step apart.)
					val f = if (b.length > a.length) ((r - a.length) / (b.length - a.length)).coerceIn(0.0, 1.0) else 1.0
					val p = a + (b - a) * f
					val heading = (b - a).normalized()
					val at = ((k * height + j) * width + i) * 3
					crossings[at] = p.x; crossings[at + 1] = p.y; crossings[at + 2] = p.z
					headings[at] = heading.x; headings[at + 1] = heading.y; headings[at + 2] = heading.z
					k++
				}
				if (k >= shells) break
			}
		}
	}

	/** The view direction through table position (u, v) (texel units, centres at half-integers). */
	fun direction(u: Double, v: Double): V3 =
		(forward + right * ((2.0 * u / width - 1.0) * tanX) + up * ((2.0 * v / height - 1.0) * tanY)).normalized()

	private fun at(k: Int, i: Int, j: Int, of: DoubleArray = crossings): V3? {
		if (i !in 0 until width || j !in 0 until height) return null
		val a = ((k * height + j) * width + i) * 3
		if (of[a].isNaN()) return null
		return V3(of[a], of[a + 1], of[a + 2])
	}

	/** Shell k's crossing at table position (u, v), bilinear; null if any corner has none. */
	fun crossingAt(k: Int, u: Double, v: Double): V3? = bilinear(k, u, v, crossings)

	/** Shell k's heading at table position (u, v), bilinear. */
	fun headingAt(k: Int, u: Double, v: Double): V3? = bilinear(k, u, v, headings)?.normalized()

	private fun bilinear(k: Int, u: Double, v: Double, of: DoubleArray): V3? {
		val x = u - 0.5
		val y = v - 0.5
		val i = kotlin.math.floor(x).toInt()
		val j = kotlin.math.floor(y).toInt()
		val fx = x - i
		val fy = y - j
		val a = at(k, i, j, of) ?: return null
		val b = at(k, i + 1, j, of) ?: return null
		val c = at(k, i, j + 1, of) ?: return null
		val d = at(k, i + 1, j + 1, of) ?: return null
		return (a * ((1 - fx) * (1 - fy)) + b * (fx * (1 - fy)) + c * ((1 - fx) * fy) + d * (fx * fy))
	}

	/**
	 * Where the light through (u, v) is at the distance of [r] from the camera: between the shells round r, along a
	 * cubic (Hermite, from the crossings and the headings there: the path curves between them), at the parameter where
	 * its distance is r (two Newton steps from the radii's ratio).
	 */
	fun pointAt(u: Double, v: Double, r: Double): V3? {
		val s = ln(r / near) / ln(far / near) * (shells - 1)
		val k = s.toInt().coerceIn(0, shells - 2)
		val a = crossingAt(k, u, v) ?: return null
		val b = crossingAt(k + 1, u, v) ?: return null
		val ta = headingAt(k, u, v) ?: return null
		val tb = headingAt(k + 1, u, v) ?: return null
		val span = (b - a).length
		fun curve(t: Double): V3 {
			val t2 = t * t
			val t3 = t2 * t
			return a * (2 * t3 - 3 * t2 + 1) + ta * ((t3 - 2 * t2 + t) * span) + b * (-2 * t3 + 3 * t2) + tb * ((t3 - t2) * span)
		}
		var t = ((r - a.length) / (b.length - a.length)).coerceIn(0.0, 1.0)
		repeat(3) {
			val p = curve(t)
			val dp = (curve(minOf(t + 1e-4, 1.0)) - p) * (1.0 / 1e-4)
			val slope = (p dot dp) / p.length
			if (kotlin.math.abs(slope) > 1e-12) t = (t - (p.length - r) / slope).coerceIn(0.0, 1.0)
		}
		return curve(t)
	}

	/** The table position seeing straight along camera-relative [d] (no lens). */
	fun straight(d: V3): Pair<Double, Double>? {
		val z = d dot forward
		if (z <= 0.0) return null
		val u = ((d dot right) / z / tanX + 1.0) * width / 2.0
		val v = ((d dot up) / z / tanY + 1.0) * height / 2.0
		return u to v
	}

	/**
	 * The view direction [target] (camera-relative) is seen in, from table position [guess] (or its straight-line
	 * position): Newton on the table (finite differences across it), [steps] steps; null if it doesn't get within
	 * [tolerance] of its distance.
	 */
	fun image(target: V3, guess: Pair<Double, Double>? = null, steps: Int = 8, tolerance: Double = 1e-3): V3? {
		val r = target.length
		var (u, v) = guess ?: straight(target) ?: return null
		repeat(steps) {
			val p = pointAt(u, v, r) ?: return null
			val miss = target - p
			if (miss.length < tolerance * r) return direction(u, v)
			val h = 0.25
			val pu = pointAt(u + h, v, r) ?: return null
			val pv = pointAt(u, v + h, r) ?: return null
			val ju = (pu - p) * (1.0 / h)
			val jv = (pv - p) * (1.0 / h)
			val a = ju dot ju
			val b = ju dot jv
			val c = jv dot jv
			val det = a * c - b * b
			if (kotlin.math.abs(det) < 1e-30) return null
			val g1 = ju dot miss
			val g2 = jv dot miss
			var du = (c * g1 - b * g2) / det
			var dv = (a * g2 - b * g1) / det
			// (At most a few texels a step: the table's map is anything but linear far from the image.)
			val size = sqrt(du * du + dv * dv)
			if (size > 4.0) { du *= 4.0 / size; dv *= 4.0 / size }
			u += du
			v += dv
		}
		return if ((target - (pointAt(u, v, r) ?: return null)).length < tolerance * r) direction(u, v) else null
	}
}
