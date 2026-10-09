package net.kernelpanicsoft.relativity.physics.lens

import java.util.stream.IntStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The vertex lens's image map for one camera slot round a lone spinning hole, on the CPU (the client's KerrSlots bakes
 * these on spare cores): for points round the hole on its map grid, how each one's primary image is turned from the
 * straight line of sight. Nothing is searched for: a fan of rays from the camera (the path table's directions, dense
 * near the hole) is traced back through the Kerr metric, each ray's crossings of the map's radius shells are kept, and
 * on each shell the triangles of neighbouring rays are rasterized onto its texels, each texel taking the view direction
 * interpolated across the triangle covering it. Triangles turned over (the mirrored images past a fold, round the
 * Einstein ring) are left out; of those left, the one nearest the straight line of sight wins.
 *
 * Frames: the "map frame" is the game's axes round the hole (y the spin axis, positive spin turning +z toward +x), as the
 * map texels are (kerr_image_map.fsh): texel (i, j, k) is azimuth ((i + 0.5) / [width] - 0.5) 2 pi (atan2(z, x)), cosine
 * of the angle from +y j / ([height] - 1) 2 - 1, radius [inner] ([outer] / [inner])^(k / ([depth] - 1)). The Kerr-Schild
 * light step works on local axes (game z, x, y) as kerr.glsl does. Lengths in blocks; the hole of mass [m] and spin
 * parameter [a] at the origin; the camera at [camera] (map frame).
 */
class KerrSlotBaker(
	val m: Double, val a: Double, val camera: DoubleArray,
	val inner: Double, val outer: Double,
	val width: Int = 128, val height: Int = 64, val depth: Int = 48,
	val fanAzimuths: Int = 256, val fanRows: Int = 320,
) {
	private val horizon = m + sqrt(max(m * m - a * a, 0.0))
	private val cameraRadius = sqrt(camera[0] * camera[0] + camera[1] * camera[1] + camera[2] * camera[2])

	/** The map: per texel (x fastest, then y, then layer) the turn from the straight line of sight per block of the
	 * point's distance from the camera (map frame), and 1 where there's an image (else all 0). */
	fun bake(): FloatArray = pool.submit<FloatArray> { bakeHere() }.get()

	/** The last bake's phases (ms: fan, rasterizing, refining, checking) and texels refined (debug). */
	var timings = DoubleArray(3)
	val refined = java.util.concurrent.atomic.AtomicInteger()
	val dropped = java.util.concurrent.atomic.AtomicInteger()

	private fun bakeHere(): FloatArray {
		val tStart = System.nanoTime()
		// The fan: its corners (fanRows x fanAzimuths) and each cell's centre after them (one per cell: a check on the map's
		// curvature across it, and the vertex the cell's four triangles share).
		val corners = fanAzimuths * fanRows
		val rays = corners + fanAzimuths * (fanRows - 1)
		// Per ray, shell and crossing (in, out): the crossing's shell texel coordinates; NaN where none.
		val crossU = FloatArray(rays * depth * 2) { Float.NaN }
		val crossV = FloatArray(rays * depth * 2) { Float.NaN }
		val directions = Array(rays) { DoubleArray(3) }
		val radii = DoubleArray(depth) { inner * (outer / inner).pow(it / (depth - 1.0)) }
		IntStream.range(0, 2 * fanRows - 1).parallel().forEach { line ->
			val centres = line >= fanRows
			val row = if (centres) line - fanRows else line
			for (column in 0 until fanAzimuths) {
				val ray = (if (centres) corners else 0) + row * fanAzimuths + column
				val dir = if (centres) fanDirection(column + 1.0, row + 1.0) else fanDirection(column + 0.5, row + 0.5)
				directions[ray] = dir
				trace(dir, radii) { shell, crossing, u, v ->
					val at = (ray * depth + shell) * 2 + crossing
					crossU[at] = u
					crossV[at] = v
				}
			}
		}
		val tFan = System.nanoTime()
		val map = FloatArray(width * height * depth * 4)
		val best = FloatArray(width * height * depth) { Float.MAX_VALUE }
		val rough = FloatArray(width * height * depth * 4)
		val roughBest = FloatArray(width * height * depth) { Float.MAX_VALUE }
		// The orientation unbent light gives a triangle (from the outermost shell, where the rays are nearly straight).
		val parity = orientation(crossU, crossV)
		IntStream.range(0, depth).parallel().forEach { shell ->
			for (row in 0 until fanRows - 1) for (column in 0 until fanAzimuths) {
				val next = (column + 1) % fanAzimuths
				val r00 = row * fanAzimuths + column
				val r10 = row * fanAzimuths + next
				val r01 = (row + 1) * fanAzimuths + column
				val r11 = (row + 1) * fanAzimuths + next
				val centre = corners + row * fanAzimuths + column
				for (crossing in 0..1) {
					// (A cell too curved to interpolate across still gives its texels a first guess, for refine().)
					val smooth = smooth(shell, crossing, r00, r10, r11, r01, centre, crossU, crossV)
					val into = if (smooth) map else rough
					val bestInto = if (smooth) best else roughBest
					triangle(into, bestInto, shell, crossing, r00, r10, centre, crossU, crossV, directions, parity, radii)
					triangle(into, bestInto, shell, crossing, r10, r11, centre, crossU, crossV, directions, parity, radii)
					triangle(into, bestInto, shell, crossing, r11, r01, centre, crossU, crossV, directions, parity, radii)
					triangle(into, bestInto, shell, crossing, r01, r00, centre, crossU, crossV, directions, parity, radii)
				}
			}
		}
		val tRaster = System.nanoTime()
		// Texels left without an image but with a guess (from a curved cell, or a neighbour's image): solved directly.
		IntStream.range(0, depth).parallel().forEach { shell ->
			for (vi in 0 until height) for (column in 0 until width) {
				val texel = (shell * height + vi) * width + column
				if (map[texel * 4 + 3] > 0.5f) continue
				val guesses = ArrayList<FloatArray>(3)
				if (rough[texel * 4 + 3] > 0.5f) guesses += floatArrayOf(rough[texel * 4], rough[texel * 4 + 1], rough[texel * 4 + 2])
				for ((di, dj) in NEIGHBOURS) {
					val j = vi + dj
					if (j < 0 || j >= height) continue
					val n = (shell * height + j) * width + Math.floorMod(column + di, width)
					if (map[n * 4 + 3] > 0.5f && guesses.size < 3) guesses += floatArrayOf(map[n * 4], map[n * 4 + 1], map[n * 4 + 2])
				}
				if (guesses.isNotEmpty()) { refined.incrementAndGet(); refine(map, shell, vi, column, radii[shell], guesses) }
			}
		}
		val tRefine = System.nanoTime()
		// Every interpolated image checked: its light followed from the camera must pass within [VERIFY] pixels of its
		// point, else the texel is left without one (the vertex lens hides it; the post pass draws it). One wrong texel
		// makes a slab across the screen; a missing one only a small gap.
		IntStream.range(0, depth).parallel().forEach { shell ->
			val landed = DoubleArray(3)
			for (vi in 0 until height) for (column in 0 until width) {
				val texel = (shell * height + vi) * width + column
				if (map[texel * 4 + 3] == VERIFIED) {
					map[texel * 4 + 3] = 1f
					continue
				}
				if (map[texel * 4 + 3] < 0.5f) continue
				val point = texelPoint(shell, vi, column, radii[shell])
				val game = DoubleArray(3) { point[it] - camera[it] }
				val span = norm(game)
				val dir = normalize(DoubleArray(3) { game[it] / span + map[texel * 4 + it] * span })
				val ok = land(dir, radii[shell], point, landed) &&
					sqrt((landed[0] - point[0]).let { it * it } + (landed[1] - point[1]).let { it * it } + (landed[2] - point[2]).let { it * it }) / span < VERIFY * PIXEL
				if (!ok) {
					for (c in 0..3) map[texel * 4 + c] = 0f
					dropped.incrementAndGet()
				}
			}
		}
		timings = doubleArrayOf((tFan - tStart) / 1e6, (tRaster - tFan) / 1e6, (tRefine - tRaster) / 1e6, (System.nanoTime() - tRefine) / 1e6)
		return map
	}

	/** The fan's direction at (column, row) in fan texels: relativityTableDir (vertex_lens_core.glsl), map frame. */
	fun fanDirection(column: Double, row: Double): DoubleArray {
		val rc = sqrt(camera[0] * camera[0] + camera[1] * camera[1] + camera[2] * camera[2])
		val axis = doubleArrayOf(-camera[0] / rc, -camera[1] / rc, -camera[2] / rc)
		val up = if (abs(axis[1]) < 0.99) doubleArrayOf(0.0, 1.0, 0.0) else doubleArrayOf(1.0, 0.0, 0.0)
		val dot = up[0] * axis[0] + up[1] * axis[1] + up[2] * axis[2]
		val zero = normalize(doubleArrayOf(up[0] - dot * axis[0], up[1] - dot * axis[1], up[2] - dot * axis[2]))
		val quarter = cross(axis, zero)
		val rs = 2.0 * m
		val knee = min(4.0 * asin(min(2.6 * rs / rc, 1.0)), 0.6 * PI)
		val u = (row / fanRows).coerceIn(0.0, 1.0)
		val alpha = if (u <= KNEE) u / KNEE * knee else knee + (u - KNEE) / (1.0 - KNEE) * (PI - knee)
		val azimuth = column / fanAzimuths * 2.0 * PI
		val sa = sin(alpha)
		return DoubleArray(3) { cos(alpha) * axis[it] + sa * (cos(azimuth) * zero[it] + sin(azimuth) * quarter[it]) }
	}

	/**
	 * Follows the light seen along [dir] back from the camera, calling [crossed] (shell, crossing 0 or 1, texel u, v) at
	 * each of its first two crossings of each shell radius.
	 */
	private fun trace(dir: DoubleArray, radii: DoubleArray, crossed: (Int, Int, Float, Float) -> Unit) {
		val x = local(camera)
		val d = local(dir)
		val p = DoubleArray(3)
		val ptOut = DoubleArray(1)
		nullMomentum(x, doubleArrayOf(-d[0], -d[1], -d[2]), p, ptOut)
		val pt = ptOut[0]
		val counts = IntArray(depth)
		val dx = DoubleArray(3)
		val dp = DoubleArray(3)
		val k2x = DoubleArray(3); val k2p = DoubleArray(3)
		val k3x = DoubleArray(3); val k3p = DoubleArray(3)
		val k4x = DoubleArray(3); val k4p = DoubleArray(3)
		val tx = DoubleArray(3); val tp = DoubleArray(3)
		var rPrevious = norm(x)
		val previous = x.copyOf()
		var swept = 0.0 // the angle the light has gone round the hole
		for (step in 0 until MAX_STEPS) {
			val r = backward(x, p, pt, dx, dp)
			if (r < 1.02 * horizon) return
			val radius = norm(x)
			if (radius > outer * 1.01 && x[0] * dx[0] + x[1] * dx[1] + x[2] * dx[2] > 0.0) return
			val dl = STEP * max(r, 0.5 * m) / max(norm(dx), 1e-9)
			for (i in 0..2) { tx[i] = x[i] + 0.5 * dl * dx[i]; tp[i] = p[i] + 0.5 * dl * dp[i] }
			backward(tx, tp, pt, k2x, k2p)
			for (i in 0..2) { tx[i] = x[i] + 0.5 * dl * k2x[i]; tp[i] = p[i] + 0.5 * dl * k2p[i] }
			backward(tx, tp, pt, k3x, k3p)
			for (i in 0..2) { tx[i] = x[i] + dl * k3x[i]; tp[i] = p[i] + dl * k3p[i] }
			backward(tx, tp, pt, k4x, k4p)
			for (i in 0..2) previous[i] = x[i]
			for (i in 0..2) {
				x[i] += dl / 6.0 * (dx[i] + 2.0 * k2x[i] + 2.0 * k3x[i] + k4x[i])
				p[i] += dl / 6.0 * (dp[i] + 2.0 * k2p[i] + 2.0 * k3p[i] + k4p[i])
			}
			val rNow = norm(x)
			swept += acos(((previous[0] * x[0] + previous[1] * x[1] + previous[2] * x[2]) / (rPrevious * rNow)).coerceIn(-1.0, 1.0))
			// Shells crossed this step (log-spaced: their indices between the two radii's).
			val lo = min(rPrevious, rNow)
			val hi = max(rPrevious, rNow)
			if (hi >= inner && lo <= outer) {
				val kLo = max(0, ceil(ln(max(lo, inner) / inner) / ln(outer / inner) * (depth - 1) - 1e-9).toInt())
				val kHi = min(depth - 1, floor(ln(min(hi, outer) / inner) / ln(outer / inner) * (depth - 1) + 1e-9).toInt())
				for (shell in kLo..kHi) {
					val target = inner * (outer / inner).pow(shell / (depth - 1.0))
					if ((rPrevious - target) * (rNow - target) > 0.0 || rNow == rPrevious) continue
					if (counts[shell] >= 2) continue
					val f = (target - rPrevious) / (rNow - rPrevious)
					val at = DoubleArray(3) { previous[it] + f * (x[it] - previous[it]) }
					val g = game(at) // map frame
					val gr = norm(g)
					val u = ((atan2(g[2], g[0]) / (2.0 * PI) + 0.5) * width - 0.5).toFloat()
					val v = (((g[1] / gr) * 0.5 + 0.5) * (height - 1)).toFloat()
					// Light that has wound round the hole a full turn or more (from the camera's side: the images round the
					// photon ring, the right way round again) isn't the primary image of anything.
					if (swept < WOUND) crossed(shell, counts[shell], u, v)
					counts[shell] += 1
				}
			}
			rPrevious = rNow
		}
	}

	/**
	 * Whether the fan cell (corner rays [r00] .. [r01], centre ray [centre]) maps onto [shell]'s [crossing] smoothly
	 * enough to interpolate: its centre lands near the corners' average (within [SMOOTH] of the cell's size, or a quarter
	 * texel), and the cell isn't spread over the axis.
	 */
	private fun smooth(shell: Int, crossing: Int, r00: Int, r10: Int, r11: Int, r01: Int, centre: Int, crossU: FloatArray, crossV: FloatArray): Boolean {
		val at = { ray: Int -> (ray * depth + shell) * 2 + crossing }
		val uc = crossU[at(centre)].toDouble()
		val vc = crossV[at(centre)].toDouble()
		if (uc.isNaN()) return false
		var su = 0.0
		var sv = 0.0
		var lo = Double.MAX_VALUE
		var hiU = -Double.MAX_VALUE
		var loV = Double.MAX_VALUE
		var hiV = -Double.MAX_VALUE
		for (ray in intArrayOf(r00, r10, r11, r01)) {
			var u = crossU[at(ray)].toDouble()
			if (u.isNaN()) return false
			u -= width * floor((u - uc) / width + 0.5)
			val v = crossV[at(ray)].toDouble()
			su += u
			sv += v
			lo = min(lo, u); hiU = max(hiU, u); loV = min(loV, v); hiV = max(hiV, v)
		}
		val size = max(hiU - lo, hiV - loV)
		if (hiU - lo > width / 4.0) return false
		val off = max(abs(su / 4 - uc), abs(sv / 4 - vc))
		return off <= max(SMOOTH * size, 0.25)
	}

	/** The triangle (rays [r0], [r1], [r2]) at [shell]'s [crossing], rasterized onto that shell's texels. */
	private fun triangle(
		map: FloatArray, best: FloatArray, shell: Int, crossing: Int, r0: Int, r1: Int, r2: Int,
		crossU: FloatArray, crossV: FloatArray, directions: Array<DoubleArray>, parity: Double, radii: DoubleArray,
	) {
		val i0 = (r0 * depth + shell) * 2 + crossing
		val i1 = (r1 * depth + shell) * 2 + crossing
		val i2 = (r2 * depth + shell) * 2 + crossing
		val u0 = crossU[i0].toDouble()
		var u1 = crossU[i1].toDouble()
		var u2 = crossU[i2].toDouble()
		if (u0.isNaN() || u1.isNaN() || u2.isNaN()) return
		// Azimuth wraps round: the vertices taken on the turn nearest the first.
		u1 -= width * floor((u1 - u0) / width + 0.5)
		u2 -= width * floor((u2 - u0) / width + 0.5)
		val v0 = crossV[i0].toDouble()
		val v1 = crossV[i1].toDouble()
		val v2 = crossV[i2].toDouble()
		val area = (u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0)
		// Turned over: an image mirrored past a fold. (A shell inside the camera's radius is crossed twice: going in, on the
		// side facing the camera, seen from outside it, the other way round from the outermost shell's crossing; then out,
		// as that one.)
		val expected = if (crossing == 0 && radii[shell] < cameraRadius) -parity else parity
		if (area * expected <= 0.0) return
		val radius = radii[shell]
		val spanU = max(u0, max(u1, u2)) - min(u0, min(u1, u2))
		val spanV = max(v0, max(v1, v2)) - min(v0, min(v1, v2))
		// (Across the axis: the map's poles.)
		if (spanU > width / 4.0) return
		for (vi in ceil(min(v0, min(v1, v2))).toInt()..floor(max(v0, max(v1, v2))).toInt()) {
			if (vi < 0 || vi >= height) continue
			for (ui in ceil(min(u0, min(u1, u2))).toInt()..floor(max(u0, max(u1, u2))).toInt()) {
				// Barycentric weights of the texel centre.
				val w1 = ((ui - u0) * (v2 - v0) - (u2 - u0) * (vi - v0)) / area
				val w2 = ((u1 - u0) * (vi - v0) - (ui - u0) * (v1 - v0)) / area
				val w0 = 1.0 - w1 - w2
				if (w0 < -1e-9 || w1 < -1e-9 || w2 < -1e-9) continue
				val column = Math.floorMod(ui, width)
				val d0 = directions[r0]
				val d1 = directions[r1]
				val d2 = directions[r2]
				val seen = normalize(DoubleArray(3) { w0 * d0[it] + w1 * d1[it] + w2 * d2[it] })
				// The texel's point and its straight line of sight.
				val azimuth = ((column + 0.5) / width - 0.5) * 2.0 * PI
				val cosine = vi / (height - 1.0) * 2.0 - 1.0
				val sine = sqrt(max(1.0 - cosine * cosine, 0.0))
				val game = doubleArrayOf(radius * sine * cos(azimuth) - camera[0], radius * cosine - camera[1], radius * sine * sin(azimuth) - camera[2])
				val span = norm(game)
				val straight = DoubleArray(3) { game[it] / span }
				val turn = acos((seen[0] * straight[0] + seen[1] * straight[1] + seen[2] * straight[2]).coerceIn(-1.0, 1.0))
				val texel = (shell * height + vi) * width + column
				if (turn >= best[texel]) continue
				best[texel] = turn.toFloat()
				for (c in 0..2) map[texel * 4 + c] = ((seen[c] - straight[c]) / span).toFloat()
				map[texel * 4 + 3] = 1f
			}
		}
	}

	/** The texel's point (map frame) and its straight line of sight's length. */
	private fun texelPoint(shell: Int, vi: Int, column: Int, radius: Double): DoubleArray {
		val azimuth = ((column + 0.5) / width - 0.5) * 2.0 * PI
		val cosine = vi / (height - 1.0) * 2.0 - 1.0
		val sine = sqrt(max(1.0 - cosine * cosine, 0.0))
		return doubleArrayOf(radius * sine * cos(azimuth), radius * cosine, radius * sine * sin(azimuth))
	}

	/**
	 * Solves for texel ([shell], [vi], [column])'s image by Newton's method from each of [guesses] (turns, as stored) in
	 * turn: the light seen along a direction is followed to the shell, and the direction corrected by where it lands
	 * (finite-difference Jacobian). Kept if it lands within [ACCEPT] of a pixel's angle of the point, as a primary image
	 * (not wound round the hole).
	 */
	private fun refine(map: FloatArray, shell: Int, vi: Int, column: Int, radius: Double, guesses: List<FloatArray>) {
		val point = texelPoint(shell, vi, column, radius)
		val game = DoubleArray(3) { point[it] - camera[it] }
		val span = norm(game)
		val straight = DoubleArray(3) { game[it] / span }
		val landed = DoubleArray(3)
		val probe = DoubleArray(3)
		for (guess in guesses) {
			var dir = normalize(DoubleArray(3) { straight[it] + guess[it] * span })
			for (iteration in 0 until 6) {
				if (!land(dir, radius, point, landed)) break
				val miss = DoubleArray(3) { landed[it] - point[it] }
				if (norm(miss) / span < ACCEPT * PIXEL) {
					val texel = (shell * height + vi) * width + column
					for (c in 0..2) map[texel * 4 + c] = ((dir[c] - straight[c]) / span).toFloat()
					map[texel * 4 + 3] = VERIFIED
					return
				}
				// Two tangent directions and the landing's response to each.
				val e1 = normalize(cross(dir, if (abs(dir[1]) < 0.9) doubleArrayOf(0.0, 1.0, 0.0) else doubleArrayOf(1.0, 0.0, 0.0)))
				val e2 = cross(dir, e1)
				val h = 1e-4
				val j1 = DoubleArray(3)
				val j2 = DoubleArray(3)
				if (!land(normalize(DoubleArray(3) { dir[it] + h * e1[it] }), radius, point, probe)) break
				for (c in 0..2) j1[c] = (probe[c] - landed[c]) / h
				if (!land(normalize(DoubleArray(3) { dir[it] + h * e2[it] }), radius, point, probe)) break
				for (c in 0..2) j2[c] = (probe[c] - landed[c]) / h
				// Least squares: [j1 j2] (x, y) = -miss.
				val a11 = j1[0] * j1[0] + j1[1] * j1[1] + j1[2] * j1[2]
				val a12 = j1[0] * j2[0] + j1[1] * j2[1] + j1[2] * j2[2]
				val a22 = j2[0] * j2[0] + j2[1] * j2[1] + j2[2] * j2[2]
				val b1 = -(j1[0] * miss[0] + j1[1] * miss[1] + j1[2] * miss[2])
				val b2 = -(j2[0] * miss[0] + j2[1] * miss[1] + j2[2] * miss[2])
				val det = a11 * a22 - a12 * a12
				if (abs(det) < 1e-18) break
				var x = (b1 * a22 - b2 * a12) / det
				var y = (a11 * b2 - a12 * b1) / det
				// Steps no longer than a few degrees: past a fold Newton runs off.
				val length = sqrt(x * x + y * y)
				if (length > 0.05) { x *= 0.05 / length; y *= 0.05 / length }
				dir = normalize(DoubleArray(3) { dir[it] + x * e1[it] + y * e2[it] })
			}
		}
	}

	/**
	 * Where the light seen along [dir] (map frame) crosses radius [radius] (into [out], map frame): of its first two
	 * crossings, the one nearer [near]. False if it never does, or only after winding round the hole.
	 */
	private fun land(dir: DoubleArray, radius: Double, near: DoubleArray, out: DoubleArray): Boolean {
		val x = local(camera)
		val d = local(dir)
		val p = DoubleArray(3)
		val ptOut = DoubleArray(1)
		nullMomentum(x, doubleArrayOf(-d[0], -d[1], -d[2]), p, ptOut)
		val pt = ptOut[0]
		val dx = DoubleArray(3); val dp = DoubleArray(3)
		val k2x = DoubleArray(3); val k2p = DoubleArray(3)
		val k3x = DoubleArray(3); val k3p = DoubleArray(3)
		val k4x = DoubleArray(3); val k4p = DoubleArray(3)
		val tx = DoubleArray(3); val tp = DoubleArray(3)
		val previous = DoubleArray(3)
		var rPrevious = norm(x)
		var swept = 0.0
		var found = 0
		var bestDistance = Double.MAX_VALUE
		val target = local(near)
		for (step in 0 until MAX_STEPS) {
			val r = backward(x, p, pt, dx, dp)
			if (r < 1.02 * horizon) break
			val here = norm(x)
			// Out past the shell (and everything) and leaving: no more crossings.
			if (here > radius && here > cameraRadius && x[0] * dx[0] + x[1] * dx[1] + x[2] * dx[2] > 0.0 && found > 0) break
			if (here > outer * 1.01 && x[0] * dx[0] + x[1] * dx[1] + x[2] * dx[2] > 0.0) break
			val dl = STEP * max(r, 0.5 * m) / max(norm(dx), 1e-9)
			for (i in 0..2) { tx[i] = x[i] + 0.5 * dl * dx[i]; tp[i] = p[i] + 0.5 * dl * dp[i] }
			backward(tx, tp, pt, k2x, k2p)
			for (i in 0..2) { tx[i] = x[i] + 0.5 * dl * k2x[i]; tp[i] = p[i] + 0.5 * dl * k2p[i] }
			backward(tx, tp, pt, k3x, k3p)
			for (i in 0..2) { tx[i] = x[i] + dl * k3x[i]; tp[i] = p[i] + dl * k3p[i] }
			backward(tx, tp, pt, k4x, k4p)
			for (i in 0..2) previous[i] = x[i]
			for (i in 0..2) {
				x[i] += dl / 6.0 * (dx[i] + 2.0 * k2x[i] + 2.0 * k3x[i] + k4x[i])
				p[i] += dl / 6.0 * (dp[i] + 2.0 * k2p[i] + 2.0 * k3p[i] + k4p[i])
			}
			val rNow = norm(x)
			swept += acos(((previous[0] * x[0] + previous[1] * x[1] + previous[2] * x[2]) / (rPrevious * rNow)).coerceIn(-1.0, 1.0))
			if (swept >= WOUND) break
			if ((rPrevious - radius) * (rNow - radius) <= 0.0 && rNow != rPrevious) {
				val f = (radius - rPrevious) / (rNow - rPrevious)
				val at = DoubleArray(3) { previous[it] + f * (x[it] - previous[it]) }
				val distance = sqrt((at[0] - target[0]).let { it * it } + (at[1] - target[1]).let { it * it } + (at[2] - target[2]).let { it * it })
				if (distance < bestDistance) {
					bestDistance = distance
					val g = game(at)
					for (c in 0..2) out[c] = g[c]
				}
				found += 1
				if (found >= 2) break
			}
			rPrevious = rNow
		}
		return found > 0
	}

	/**
	 * How close the light seen along [dir] (map frame) passes to [point] (map frame), followed back from the camera with
	 * fine steps (for tests).
	 */
	fun missDistance(dir: DoubleArray, point: DoubleArray): Double {
		val x = local(camera)
		val target = local(point)
		val p = DoubleArray(3)
		val ptOut = DoubleArray(1)
		val d = local(dir)
		nullMomentum(x, doubleArrayOf(-d[0], -d[1], -d[2]), p, ptOut)
		val dx = DoubleArray(3)
		val dp = DoubleArray(3)
		val k2x = DoubleArray(3); val k2p = DoubleArray(3)
		val k3x = DoubleArray(3); val k3p = DoubleArray(3)
		val k4x = DoubleArray(3); val k4p = DoubleArray(3)
		val tx = DoubleArray(3); val tp = DoubleArray(3)
		var best = Double.MAX_VALUE
		val previous = DoubleArray(3)
		for (step in 0 until 40000) {
			val r = backward(x, p, ptOut[0], dx, dp)
			if (r < 1.01 * horizon) break
			if (norm(x) > 2.0 * outer) break
			val toTarget = sqrt((x[0] - target[0]).let { it * it } + (x[1] - target[1]).let { it * it } + (x[2] - target[2]).let { it * it })
			val dl = 0.005 * max(min(r, toTarget), 0.05 * m) / max(norm(dx), 1e-9)
			for (i in 0..2) { tx[i] = x[i] + 0.5 * dl * dx[i]; tp[i] = p[i] + 0.5 * dl * dp[i] }
			backward(tx, tp, ptOut[0], k2x, k2p)
			for (i in 0..2) { tx[i] = x[i] + 0.5 * dl * k2x[i]; tp[i] = p[i] + 0.5 * dl * k2p[i] }
			backward(tx, tp, ptOut[0], k3x, k3p)
			for (i in 0..2) { tx[i] = x[i] + dl * k3x[i]; tp[i] = p[i] + dl * k3p[i] }
			backward(tx, tp, ptOut[0], k4x, k4p)
			for (i in 0..2) previous[i] = x[i]
			for (i in 0..2) {
				x[i] += dl / 6.0 * (dx[i] + 2.0 * k2x[i] + 2.0 * k3x[i] + k4x[i])
				p[i] += dl / 6.0 * (dp[i] + 2.0 * k2p[i] + 2.0 * k3p[i] + k4p[i])
			}
			// Closest distance from the target to this step's segment.
			val seg = DoubleArray(3) { x[it] - previous[it] }
			val rel = DoubleArray(3) { target[it] - previous[it] }
			val len2 = seg[0] * seg[0] + seg[1] * seg[1] + seg[2] * seg[2]
			val f = if (len2 > 0) ((rel[0] * seg[0] + rel[1] * seg[1] + rel[2] * seg[2]) / len2).coerceIn(0.0, 1.0) else 0.0
			val dist = sqrt(DoubleArray(3) { rel[it] - f * seg[it] }.let { it[0] * it[0] + it[1] * it[1] + it[2] * it[2] })
			if (dist < best) best = dist
		}
		return best
	}

	/** The sign a triangle's area has where light is barely bent: the majority over the outermost shell's. */
	private fun orientation(crossU: FloatArray, crossV: FloatArray): Double {
		var positive = 0
		var negative = 0
		val shell = depth - 1
		for (row in 0 until fanRows - 1) for (column in 0 until fanAzimuths - 1) {
			val i0 = ((row * fanAzimuths + column) * depth + shell) * 2
			val i1 = ((row * fanAzimuths + column + 1) * depth + shell) * 2
			val i2 = (((row + 1) * fanAzimuths + column + 1) * depth + shell) * 2
			val u0 = crossU[i0]; val u1 = crossU[i1]; val u2 = crossU[i2]
			if (u0.isNaN() || u1.isNaN() || u2.isNaN()) continue
			if (abs(u1 - u0) > width / 4 || abs(u2 - u0) > width / 4) continue
			val area = (u1 - u0) * (crossV[i2] - crossV[i0]) - (u2 - u0) * (crossV[i1] - crossV[i0])
			if (area > 0) positive += 1 else if (area < 0) negative += 1
		}
		return if (positive >= negative) 1.0 else -1.0
	}

	// ---- Kerr light (kerr.glsl's kerrDerivative and kerrNullMomentum, local axes: game z, x, y) ----

	private fun local(g: DoubleArray) = doubleArrayOf(g[2], g[0], g[1])
	private fun game(l: DoubleArray) = doubleArrayOf(l[1], l[2], l[0])

	/** Hamilton's equations followed back in time; returns the spheroidal radius. */
	private fun backward(x: DoubleArray, p: DoubleArray, pt: Double, dx: DoubleArray, dp: DoubleArray): Double {
		val a2 = a * a
		val w = x[0] * x[0] + x[1] * x[1] + x[2] * x[2] - a2
		val s = sqrt(0.25 * w * w + a2 * x[2] * x[2])
		val r2 = max(0.5 * w + s, 1e-12)
		val r = sqrt(r2)
		val sDen = 2.0 * r * max(s, 1e-12)
		val gradR = doubleArrayOf(r2 * x[0] / sDen, r2 * x[1] / sDen, (r2 + a2) * x[2] / sDen)
		val q = r2 * r2 + a2 * x[2] * x[2]
		val h = 2.0 * m * r * r2 / q
		val dhdr = 2.0 * m * r2 * (3.0 * q - 4.0 * r2 * r2) / (q * q)
		val gradH = doubleArrayOf(gradR[0] * dhdr, gradR[1] * dhdr, gradR[2] * dhdr - 4.0 * m * r * r2 * a2 * x[2] / (q * q))
		val d = r2 + a2
		val l = doubleArrayOf((r * x[0] + a * x[1]) / d, (r * x[1] - a * x[0]) / d, x[2] / r)
		val lp = l[0] * p[0] + l[1] * p[1] + l[2] * p[2] - pt
		val along = x[0] * p[0] + x[1] * p[1]
		val across = x[1] * p[0] - x[0] * p[1]
		val dfdr = (along * d - (r * along + a * across) * 2.0 * r) / (d * d) - x[2] * p[2] / r2
		val gradF = doubleArrayOf((r * p[0] - a * p[1]) / d + gradR[0] * dfdr, (r * p[1] + a * p[0]) / d + gradR[1] * dfdr, p[2] / r + gradR[2] * dfdr)
		for (i in 0..2) {
			dx[i] = -(p[i] - h * lp * l[i])
			dp[i] = -(0.5 * lp * lp * gradH[i] + h * lp * gradF[i])
		}
		return r
	}

	private fun nullMomentum(x: DoubleArray, dIn: DoubleArray, p: DoubleArray, pt: DoubleArray) {
		val dn = normalize(dIn)
		val a2 = a * a
		val w = x[0] * x[0] + x[1] * x[1] + x[2] * x[2] - a2
		val r2 = 0.5 * w + sqrt(0.25 * w * w + a2 * x[2] * x[2])
		val r = sqrt(max(r2, 1e-12))
		val h0 = 2.0 * m * r * r2 / (r2 * r2 + a2 * x[2] * x[2])
		val dd = r2 + a2
		val l0 = doubleArrayOf((r * x[0] + a * x[1]) / dd, (r * x[1] - a * x[0]) / dd, x[2] / r)
		val ld = l0[0] * dn[0] + l0[1] * dn[1] + l0[2] * dn[2]
		val qa = 1.0 + h0 * ld * ld
		val qb = 2.0 * h0 * ld
		val qc = h0 - 1.0
		val k = (-qb + sqrt(max(qb * qb - 4.0 * qa * qc, 0.0))) / (2.0 * qa)
		val lu = 1.0 + k * ld
		pt[0] = -1.0 + h0 * lu
		for (i in 0..2) p[i] = k * dn[i] + h0 * l0[i] * lu
	}

	companion object {
		/**
		 * The threads bakes run on (their parallel streams stay in it): a few, at low priority. More would bake faster but
		 * slow the game: on a laptop the CPU and an integrated GPU share one power budget.
		 */
		@JvmStatic
		var threads = maxOf(1, minOf(4, Runtime.getRuntime().availableProcessors() / 4))
			set(value) { field = value; pool = newPool() }
		private fun newPool() = java.util.concurrent.ForkJoinPool(threads, { p ->
			java.util.concurrent.ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(p).apply { isDaemon = true; priority = Thread.MIN_PRIORITY; name = "Relativity slot baker " + poolIndex }
		}, null, false)
		private var poolIndex = 0
		private var pool = newPool()

		/** The fan's knee (vertex_lens_core.glsl's RELATIVITY_TABLE_KNEE). */
		const val KNEE = 0.6
		/** Step: this fraction of the radius (measured: 0.04 bakes 1.5-2x slower for a median 0.03 px instead of 0.06). */
		var STEP = 0.08
		const val MAX_STEPS = 3000
		/** How far a cell's centre may land from its corners' average, over the cell's size (smooth()). */
		var SMOOTH = 0.1
		/** A solved image is kept if it lands within this many pixels' angle of its point ([PIXEL]: a 70 degree view
		 * 1504 pixels high). */
		const val ACCEPT = 0.3
		/** An interpolated image is kept if its light passes within this many pixels' angle of its point. */
		const val VERIFY = 1.0
		/** (Alpha marking a texel solved, so already checked, until the check.) */
		private const val VERIFIED = 2f
		const val PIXEL = 9.3e-4
		private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

		/** Light that has gone round the hole this far (radians) is past the primary images (which go at most a half turn). */
		const val WOUND = 1.5 * PI

		private fun norm(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
		private fun normalize(v: DoubleArray): DoubleArray { val n = norm(v); return doubleArrayOf(v[0] / n, v[1] / n, v[2] / n) }
		private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
	}
}
