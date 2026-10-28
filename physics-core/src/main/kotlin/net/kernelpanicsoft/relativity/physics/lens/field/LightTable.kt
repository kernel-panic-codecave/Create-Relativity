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
	companion object {
		/** Legs of a path kept: out, back, out again. */
		const val LEGS = 3

		/** The most cells a quad of rays may span in the inverse map. */
		const val MAX_QUAD = 32
	}

	/** Shell k's radius. */
	fun radius(k: Int): Double = near * exp(ln(far / near) * k / (shells - 1))

	/**
	 * Where texel (i, j)'s light crosses shell k on leg l (camera-relative), or null; and its heading there. A leg is the
	 * stretch between turning points of its distance from the camera: 0 going out, 1 coming back (light swung round a
	 * hole), 2 out again. Slot l shells + k, x, y, z per (slot, j, i).
	 */
	private val crossings = DoubleArray(LEGS * shells * width * height * 3) { Double.NaN }
	private val headings = DoubleArray(LEGS * shells * width * height * 3) { Double.NaN }

	/** The holes' centres, camera-relative (each image's signature has a bit per hole). */
	private val holeCentres = field.holes.map { it.centre - camera }

	/**
	 * An image's signature: bit k set if its light (seen along [d] from the camera) passes hole k on the other side from
	 * the point [p] (camera-relative), as seen from the camera: sign((c × d) · (c × p)). For one hole, 0 the primary image
	 * and 1 the secondary; for several, an image's class: within one the map from points to images is continuous (it
	 * breaks only on a hole's camera-hole axis, an asymptote).
	 */
	fun signature(d: V3, p: V3): Int {
		var bits = 0
		for ((k, c) in holeCentres.withIndex()) if (((c cross d) dot (c cross p)) < 0.0) bits = bits or (1 shl k)
		return bits
	}

	/**
	 * The inverse map, per signature: for each shell and each cell of the grid (a point's straight-line direction), the
	 * least bent ray of that signature crossing the shell there (its index, j width + i), or -1; and how many crossings
	 * each signature has.
	 */
	private val seeds = HashMap<Int, IntArray>()
	private val bends = HashMap<Int, DoubleArray>()
	val signatureCounts = HashMap<Int, Int>()

	init {
		for (j in 0 until height) for (i in 0 until width) {
			val d = direction(i + 0.5, j + 0.5)
			val path = field.trace(camera, lens.coordinate(camera, d), reach = far, record = true, recordEvery = 0.0)
			// Every shell the path crosses, on the leg it's on (out, back, out again).
			var leg = 0
			var outward = true
			for (n in 1 until path.points.size) {
				val a = path.points[n - 1] - camera
				val b = path.points[n] - camera
				val goingOut = b.length >= a.length
				if (goingOut != outward) {
					outward = goingOut
					leg++
					if (leg >= LEGS) break
				}
				val lo = minOf(a.length, b.length)
				val hi = maxOf(a.length, b.length)
				for (k in 0 until shells) {
					val r = radius(k)
					if (r < lo || r >= hi) continue
					// (Linear between the two points: they're a small step apart.)
					val f = ((r - a.length) / (b.length - a.length)).coerceIn(0.0, 1.0)
					val p = a + (b - a) * f
					val heading = (b - a).normalized()
					val at = (((leg * shells + k) * height + j) * width + i) * 3
					if (!crossings[at].isNaN()) continue
					crossings[at] = p.x; crossings[at + 1] = p.y; crossings[at + 2] = p.z
					headings[at] = heading.x; headings[at + 1] = heading.y; headings[at + 2] = heading.z
				}
			}
		}
		// The inverse map, rasterized: each quad of four neighbouring rays, all of one signature, covers a patch of
		// straight-line directions at each shell; every cell of that patch gets the quad (where several do, the least bent),
		// so the map is dense where the lens spreads rays apart, and a cell's seed is within a texel of its image.
		val sigs = IntArray(4)
		for (slot in 0 until LEGS * shells) for (j in 0 until height - 1) for (i in 0 until width - 1) {
			val corners = listOf(i to j, i + 1 to j, i to j + 1, i + 1 to j + 1)
			val points = corners.map { (ci, cj) -> at(slot, ci, cj) }
			if (points.any { it == null }) continue
			for ((n, c) in corners.withIndex()) sigs[n] = signature(direction(c.first + 0.5, c.second + 0.5), points[n]!!)
			if (sigs.any { it != sigs[0] }) continue
			val sig = sigs[0]
			signatureCounts.merge(sig, 1, Int::plus)
			val uvs = points.map { straight(it!!) ?: return@map null }
			if (uvs.any { it == null }) continue
			val minU = uvs.minOf { it!!.first }.toInt()
			val maxU = uvs.maxOf { it!!.first }.toInt()
			val minV = uvs.minOf { it!!.second }.toInt()
			val maxV = uvs.maxOf { it!!.second }.toInt()
			// (A quad spanning more than this many cells straddles something it can't stand for: skipped.)
			if (maxU - minU > MAX_QUAD || maxV - minV > MAX_QUAD) continue
			val centreU = uvs.sumOf { it!!.first } / 4.0
			val centreV = uvs.sumOf { it!!.second } / 4.0
			val bend = sqrt((centreU - i - 1.0) * (centreU - i - 1.0) + (centreV - j - 1.0) * (centreV - j - 1.0))
			val seed = seeds.getOrPut(sig) { IntArray(LEGS * shells * width * height) { -1 } }
			val bend0 = bends.getOrPut(sig) { DoubleArray(LEGS * shells * width * height) { Double.MAX_VALUE } }
			for (cv in maxOf(minV, 0)..minOf(maxV, height - 1)) for (cu in maxOf(minU, 0)..minOf(maxU, width - 1)) {
				val cell = (slot * height + cv) * width + cu
				if (bend < bend0[cell]) { bend0[cell] = bend; seed[cell] = j * width + i }
			}
		}
	}

	/** Debug (tests): why the last [imageIn] found nothing. */
	var lastWhy = ""

	/**
	 * The image of [target] (camera-relative) in signature [sig]: seeded from that signature's inverse map at the
	 * target's straight-line cell (or a neighbour's), refined by Newton; null if there's none or it leaves the class.
	 */
	fun imageIn(target: V3, sig: Int, steps: Int = 12): V3? {
		val seed = seeds[sig] ?: return null
		val r = target.length
		val (u, v) = straight(target) ?: return null
		val s = ln(r / near) / ln(far / near) * (shells - 1)
		val k = (s + 0.5).toInt().coerceIn(0, shells - 1)
		val bend0 = bends.getValue(sig)
		// (Each leg in turn: out, back, out again.)
		lastWhy = "no seed"
		for (leg in 0 until LEGS) {
			var best = -1
			var bestBend = Double.MAX_VALUE
			for (dj in -1..1) for (di in -1..1) {
				val ci = u.toInt() + di
				val cj = v.toInt() + dj
				if (ci !in 0 until width || cj !in 0 until height) continue
				val cell = ((leg * shells + k) * height + cj) * width + ci
				if (seed[cell] >= 0 && bend0[cell] < bestBend) { bestBend = bend0[cell]; best = seed[cell] }
			}
			if (best < 0) continue
			lastWhy = "no convergence (leg $leg)"
			val found = image(target, guess = (best % width + 1.0) to (best / width + 1.0), steps = steps, leg = leg) ?: continue
			if (signature(found, target) == sig) return found
			lastWhy = "other signature ${signature(found, target)} (leg $leg)"
		}
		return null
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
	fun pointAt(u: Double, v: Double, r: Double, leg: Int = 0): V3? {
		val s = ln(r / near) / ln(far / near) * (shells - 1)
		val k = s.toInt().coerceIn(0, shells - 2) + leg * shells
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
	fun image(target: V3, guess: Pair<Double, Double>? = null, steps: Int = 8, tolerance: Double = 1e-3, leg: Int = 0): V3? {
		val r = target.length
		var (u, v) = guess ?: straight(target) ?: return null
		repeat(steps) {
			val p = pointAt(u, v, r, leg) ?: return null
			val miss = target - p
			if (miss.length < tolerance * r) return direction(u, v)
			val h = 0.25
			val pu = pointAt(u + h, v, r, leg) ?: return null
			val pv = pointAt(u, v + h, r, leg) ?: return null
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
		return if ((target - (pointAt(u, v, r, leg) ?: return null)).length < tolerance * r) direction(u, v) else null
	}
}
