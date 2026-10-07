package net.kernelpanicsoft.relativity.physics

import net.kernelpanicsoft.relativity.physics.lens.SchwarzschildImages
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The vertex lens's Kerr images, mirrored on the CPU: the path table pass (kerr_paths.fsh) and the per-vertex solve
 * (vertex_lens_core.glsl's relativityKerrImage), for points on a floor below a spinning hole, checked by marching the
 * light seen along each found direction (it must pass through the point) and, where the solve fails, by searching
 * for an image directly (a failure where one exists leaves a hole in the world). Everything is in the hole's local
 * frame (kerr.glsl's: spin about +z), hole at the origin.
 */
class KerrVertexLensTest {
	private class V(val x: Double, val y: Double, val z: Double) {
		operator fun plus(o: V) = V(x + o.x, y + o.y, z + o.z)
		operator fun minus(o: V) = V(x - o.x, y - o.y, z - o.z)
		operator fun times(k: Double) = V(x * k, y * k, z * k)
		operator fun unaryMinus() = V(-x, -y, -z)
		fun dot(o: V) = x * o.x + y * o.y + z * o.z
		fun cross(o: V) = V(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
		fun length() = sqrt(dot(this))
		fun normalized() = this * (1.0 / length())
	}

	// ---- kerr.glsl ----

	private class Profile(val r: Double, val h: Double, val l: V)

	private fun profile(x: V, m: Double, a: Double): Profile {
		val a2 = a * a
		val w = x.dot(x) - a2
		val r2 = 0.5 * w + sqrt(0.25 * w * w + a2 * x.z * x.z)
		val r = sqrt(maxOf(r2, 1e-12))
		val h = 2.0 * m * r * r2 / (r2 * r2 + a2 * x.z * x.z)
		val d = r2 + a2
		return Profile(r, h, V((r * x.x + a * x.y) / d, (r * x.y - a * x.x) / d, x.z / r))
	}

	/** kerrBackward: the derivative, negated (light followed back in time). Returns r. */
	private fun backward(x: V, p: V, pt: Double, m: Double, a: Double, out: Array<V>): Double {
		val a2 = a * a
		val w = x.dot(x) - a2
		val s = sqrt(0.25 * w * w + a2 * x.z * x.z)
		val r2 = maxOf(0.5 * w + s, 1e-12)
		val r = sqrt(r2)
		val gradR = V(r2 * x.x, r2 * x.y, (r2 + a2) * x.z) * (1.0 / (2.0 * r * maxOf(s, 1e-12)))
		val q = r2 * r2 + a2 * x.z * x.z
		val h = 2.0 * m * r * r2 / q
		val dhdr = 2.0 * m * r2 * (3.0 * q - 4.0 * r2 * r2) / (q * q)
		val gradH = gradR * dhdr + V(0.0, 0.0, -4.0 * m * r * r2 * a2 * x.z / (q * q))
		val d = r2 + a2
		val l = V((r * x.x + a * x.y) / d, (r * x.y - a * x.x) / d, x.z / r)
		val lp = l.dot(p) - pt
		val along = x.x * p.x + x.y * p.y
		val across = x.y * p.x - x.x * p.y
		val dfdr = (along * d - (r * along + a * across) * 2.0 * r) / (d * d) - x.z * p.z / r2
		val gradF = V((r * p.x - a * p.y) / d, (r * p.y + a * p.x) / d, p.z / r) + gradR * dfdr
		out[0] = -(p - l * (h * lp))
		out[1] = -(gradH * (0.5 * lp * lp) + gradF * (h * lp))
		return r
	}

	/** kerrNullMomentum: (p, p_t) of a ray leaving x along coordinate direction d. */
	private fun nullMomentum(x: V, d: V, m: Double, a: Double): Pair<V, Double> {
		val pr = profile(x, m, a)
		val ld = pr.l.dot(d)
		val qa = 1.0 + pr.h * ld * ld
		val qb = 2.0 * pr.h * ld
		val qc = pr.h - 1.0
		val k = (-qb + sqrt(maxOf(qb * qb - 4.0 * qa * qc, 0.0))) / (2.0 * qa)
		val lu = 1.0 + k * ld
		return (d * k + pr.l * (pr.h * lu)) to (-1.0 + pr.h * lu)
	}

	// ---- kerr_paths.fsh ----

	private val KERR_STEPS = 140
	private val KERR_STEP = 0.1

	private fun closestApproach(x: V, p: V, pt: Double, m: Double, a: Double, r0: Double, horizon: Double): Double {
		val e = -pt
		val l = x.x * p.y - x.y * p.x
		val c = x.z / r0
		val s = sqrt(maxOf(1.0 - c * c, 1e-8))
		val pTheta = (x.x * p.x + x.y * p.y) * c / s - r0 * s * p.z
		val q = pTheta * pTheta + c * c * (l * l / (s * s) - a * a * e * e)
		val rest = q + (l - a * e) * (l - a * e)
		fun radial(r: Double): Double {
			val k = e * (r * r + a * a) - a * l
			return k * k - (r * r - 2.0 * m * r + a * a) * rest
		}
		var hi = r0
		val ratio = (horizon * 1.02 / r0).pow(1.0 / 64.0)
		repeat(64) {
			val lo = hi * ratio
			if (radial(lo) < 0.0) {
				var below = lo
				var above = hi
				repeat(24) {
					val mid = 0.5 * (below + above)
					if (radial(mid) < 0.0) below = mid else above = mid
				}
				return 0.5 * (below + above)
			}
			hi = lo
		}
		return -1.0
	}

	/** One texel's path: r_min, status, and samples -7..7 (angle in the ray's plane, offset out of it). */
	private class PathEntry(val rMin: Double, val status: Int, val samples: Array<DoubleArray>)

	private class Scene(
		val camera: V, val rs: Double, val spin: Double, val pathRadius: Double,
		val forward: V, val right: V, val up: V, val tanX: Double, val tanY: Double, val width: Int, val height: Int,
	) {
		val cache = HashMap<Long, PathEntry>()
		/** Entries from elsewhere (the GPU's table) instead of traced here. */
		var source: ((Int, Int) -> PathEntry)? = null
		/** View direction (local frame) through table position (texel units, centres at +0.5). */
		fun direction(sx: Double, sy: Double): V {
			val nx = sx / width * 2.0 - 1.0
			val ny = sy / height * 2.0 - 1.0
			return (forward + right * (nx * tanX) + up * (ny * tanY)).normalized()
		}
	}

	private fun pathEntry(scene: Scene, i: Int, j: Int): PathEntry =
		scene.cache.getOrPut(i.toLong() * 100000 + j) { scene.source?.invoke(i, j) ?: tracePath(scene, i, j) }

	private fun tracePath(scene: Scene, i: Int, j: Int): PathEntry {
		val none = PathEntry(0.0, 0, Array(15) { DoubleArray(2) })
		val dir = scene.direction(i + 0.5, j + 0.5)
		val hole = -scene.camera // hole relative to the camera
		val e1 = scene.camera.normalized()
		var normal = e1.cross(dir)
		if (normal.dot(normal) < 1e-12) return none
		normal = normal.normalized()
		val side = normal.cross(e1)
		var turn = 0.0
		fun angles(x: V): DoubleArray {
			val n = x.normalized()
			var angle = atan2(n.dot(side), n.dot(e1))
			angle += 2 * PI * floor((turn - angle) / (2 * PI) + 0.5)
			turn = angle
			return doubleArrayOf(angle, n.dot(normal))
		}
		val rs = scene.rs
		val m = 0.5 * rs
		val a = scene.spin * m
		val horizon = m + sqrt(maxOf(m * m - a * a, 0.0))
		val rc = hole.length()
		val along = hole.dot(dir)
		val startRadius = scene.pathRadius
		var t0 = 0.0
		if (rc > startRadius) t0 = along - sqrt(maxOf(along * along - (rc * rc - startRadius * startRadius), 0.0))
		var x = dir * t0 - hole
		var (p, pt) = nullMomentum(x, -dir, m, a)
		val k1 = arrayOf(V(0.0, 0.0, 0.0), V(0.0, 0.0, 0.0))
		var r = backward(x, p, pt, m, a, k1)
		val r0 = r
		val inbound = x.dot(k1[0]) < 0.0
		var rMin = r0
		var status = 1
		if (inbound) {
			rMin = closestApproach(x, p, pt, m, a, r0, horizon)
			if (rMin < 0.0) {
				status = 2
				rMin = 1.05 * horizon
			}
		}
		val rOut = startRadius
		val qIn = Array(8) { angles(x) }
		turn = 0.0
		var q = angles(x)
		for (k in 0 until 8) qIn[k] = q.copyOf()
		val qOut = Array(8) { q.copyOf() }
		var nextIn = 6
		var nextOut = 1
		var goingOut = !inbound
		var rPrevious = r
		var qPrevious = q
		var done = false
		val k2 = arrayOf(V(0.0, 0.0, 0.0), V(0.0, 0.0, 0.0))
		val k3 = arrayOf(V(0.0, 0.0, 0.0), V(0.0, 0.0, 0.0))
		val k4 = arrayOf(V(0.0, 0.0, 0.0), V(0.0, 0.0, 0.0))
		fun mix(a: DoubleArray, b: DoubleArray, f: Double) = doubleArrayOf(a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f)
		for (s in 0 until 3 * KERR_STEPS) {
			if (done) break
			if (r < horizon * 1.02) {
				if (status == 1) status = 3
				break
			}
			val dl = 0.5 * KERR_STEP * maxOf(r, 0.5 * m) / maxOf(k1[0].length(), 1e-6)
			backward(x + k1[0] * (0.5 * dl), p + k1[1] * (0.5 * dl), pt, m, a, k2)
			backward(x + k2[0] * (0.5 * dl), p + k2[1] * (0.5 * dl), pt, m, a, k3)
			backward(x + k3[0] * dl, p + k3[1] * dl, pt, m, a, k4)
			x = x + (k1[0] + k2[0] * 2.0 + k3[0] * 2.0 + k4[0]) * (dl / 6.0)
			p = p + (k1[1] + k2[1] * 2.0 + k3[1] * 2.0 + k4[1]) * (dl / 6.0)
			r = backward(x, p, pt, m, a, k1)
			q = angles(x)
			if (!goingOut) {
				while (nextIn >= 1) {
					val target = rMin + (r0 - rMin) * (nextIn / 7.0).pow(4)
					if (r > target) break
					qIn[nextIn] = mix(qPrevious, q, (rPrevious - target) / maxOf(rPrevious - r, 1e-9))
					nextIn -= 1
				}
				if (r > rPrevious && status == 1) {
					goingOut = true
					val closest = if (rPrevious < r) qPrevious else q
					qIn[0] = closest
					qOut[0] = closest
					for (k in nextIn downTo 1) qIn[k] = closest
					nextIn = 0
				}
			}
			if (goingOut) {
				while (nextOut <= 7) {
					val target = rMin + (rOut - rMin) * (nextOut / 7.0).pow(4)
					if (r < target) break
					qOut[nextOut] = mix(qPrevious, q, (target - rPrevious) / maxOf(r - rPrevious, 1e-9))
					nextOut += 1
				}
				if (nextOut > 7) done = true
			}
			rPrevious = r
			qPrevious = q
		}
		if (status == 1 && !done) status = 3
		if (!inbound) {
			qIn[0] = qIn[7]
			qOut[0] = qIn[7]
		}
		if (status == 2) {
			qIn[0] = qIn[1]
			for (k in 0 until 8) qOut[k] = qIn[1]
		}
		// samples -7..7 → index 0..14
		val samples = Array(15) { idx ->
			val jj = idx - 7
			when {
				jj == 0 -> qIn[0]
				jj < 0 -> qIn[-jj]
				else -> qOut[jj]
			}
		}
		return PathEntry(rMin, status, samples)
	}

	// ---- vertex_lens_core.glsl ----

	private class Solve(val scene: Scene, val rStart: Double, val rOut: Double) {
		var unmarched = false
	}

	/**
	 * Texel (i, j)'s path at position v (-7 .. 7 along its samples: coming in, the closest approach at 0, going out):
	 * the angle round the hole in its ray's plane, the offset out of it (Catmull-Rom), and the radius there,
	 * r_min + (r_top - r_min) (|v| / 7)^4 (smooth through 0). Null where the texel has no usable path there.
	 */
	private fun pathAt(sv: Solve, i: Int, j: Int, v: Double): DoubleArray? {
		val e = pathEntry(sv.scene, i, j)
		if (e.status == 0) return null
		if (e.status == 3 || (e.status == 2 && v > 0.0)) return null
		val base = floor(v).coerceIn(-7.0, 6.0)
		val f = v - base
		val jj = base.toInt()
		fun sample(k: Int) = e.samples[k.coerceIn(-7, 7) + 7]
		val p0 = sample(maxOf(jj - 1, -7))
		val p1 = sample(jj)
		val p2 = sample(jj + 1)
		val p3 = sample(minOf(jj + 2, 7))
		val f2 = f * f
		val f3 = f2 * f
		val rTop = if (v < 0.0) sv.rStart else sv.rOut
		val radius = e.rMin + (rTop - e.rMin) * (abs(v) / 7.0).pow(4)
		return DoubleArray(3) { c ->
			if (c == 2) radius
			else 0.5 * (2.0 * p1[c] + (p2[c] - p0[c]) * f + (2.0 * p0[c] - 5.0 * p1[c] + 4.0 * p2[c] - p3[c]) * f2 + (3.0 * p1[c] - p0[c] - 3.0 * p2[c] + p3[c]) * f3)
		}
	}

	/** How far texel (i, j)'s path at v misses point p (direction n, radius rP): angle, offset, radius / rP. */
	private fun pathMiss(sv: Solve, i: Int, j: Int, n: V, rP: Double, v: Double): DoubleArray? {
		val path = pathAt(sv, i, j, v) ?: return null
		val dir = sv.scene.direction(i + 0.5, j + 0.5)
		val e1 = sv.scene.camera.normalized()
		var normal = e1.cross(dir)
		if (normal.dot(normal) < 1e-12) return null
		normal = normal.normalized()
		val side = normal.cross(e1)
		var angle = atan2(n.dot(side), n.dot(e1))
		angle += 2 * PI * floor((path[0] - angle) / (2 * PI) + 0.5)
		return doubleArrayOf(path[0] - angle, path[1] - n.dot(normal), (path[2] - rP) / rP)
	}

	private fun pathImage(sv: Solve, sx: Double, sy: Double, v: Double, n: V, rP: Double): DoubleArray? {
		val px = sx - 0.5
		val py = sy - 0.5
		val bx = floor(px).toInt()
		val by = floor(py).toInt()
		if (bx < 0 || by < 0 || bx + 1 >= sv.scene.width || by + 1 >= sv.scene.height) return null
		val fx = px - bx
		val fy = py - by
		val a = pathMiss(sv, bx, by, n, rP, v)
		val c = pathMiss(sv, bx + 1, by, n, rP, v)
		val d = pathMiss(sv, bx, by + 1, n, rP, v)
		val e = pathMiss(sv, bx + 1, by + 1, n, rP, v)
		if (a == null || c == null || d == null || e == null) return null
		return DoubleArray(3) { k -> (a[k] * (1 - fx) + c[k] * fx) * (1 - fy) + (d[k] * (1 - fx) + e[k] * fx) * fy }
	}

	/** Newton's method on (table position, path position v) from start; returns (sx, sy, v) or null. */
	private fun kerrSolve(sv: Solve, start: DoubleArray, n: V, rP: Double, v0: Double, iterations: Int = 10): DoubleArray? {
		var x = doubleArrayOf(start[0], start[1], v0)
		val steps = doubleArrayOf(0.5, 0.5, 0.02)
		repeat(iterations) {
			val miss = pathImage(sv, x[0], x[1], x[2], n, rP) ?: return null
			if (miss.sumOf { it * it } < 1e-8) return x
			val jac = Array(3) { DoubleArray(3) } // jac[row][column]
			for (c in 0..2) {
				val shifted = x.copyOf().also { it[c] += steps[c] }
				val m = pathImage(sv, shifted[0], shifted[1], shifted[2], n, rP) ?: return null
				for (r in 0..2) jac[r][c] = (m[r] - miss[r]) / steps[c]
			}
			val step = solve3(jac, miss) ?: return null
			val len2 = step[0] * step[0] + step[1] * step[1]
			if (len2 > 64.0) { val k = 8.0 / sqrt(len2); step[0] *= k; step[1] *= k }
			step[2] = step[2].coerceIn(-2.0, 2.0)
			// Backtracking: halve a step that makes the miss worse.
			val current = miss.sumOf { it * it }
			var scale = 1.0
			var next = x
			for (k in 0 until 5) {
				next = doubleArrayOf(x[0] - step[0] * scale, x[1] - step[1] * scale, (x[2] - step[2] * scale).coerceIn(-7.0, 7.0))
				val m = pathImage(sv, next[0], next[1], next[2], n, rP)
				if (m != null && m.sumOf { it * it } < current) break
				scale *= 0.5
			}
			x = next
		}
		val miss = pathImage(sv, x[0], x[1], x[2], n, rP) ?: return null
		return if (miss.sumOf { it * it } < 1e-6) x else null
	}

	private fun solve3(m: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
		fun det(a: Array<DoubleArray>) = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
		val d = det(m)
		if (abs(d) < 1e-18) return null
		return DoubleArray(3) { c -> det(Array(3) { r -> DoubleArray(3) { k -> if (k == c) b[r] else m[r][k] } }) / d }
	}

	/** The starting path position for p on texel position start: coming in if it can (p nearer than the camera), else going out. */
	private fun startPositions(sv: Solve, start: DoubleArray, rP: Double): List<Double> {
		val e = pathEntry(sv.scene, start[0].toInt().coerceIn(0, sv.scene.width - 1), start[1].toInt().coerceIn(0, sv.scene.height - 1))
		val inbound = 7.0 * sqrt(sqrt(((rP - e.rMin) / maxOf(sv.rStart - e.rMin, 1e-6)).coerceIn(0.0, 1.0)))
		val outbound = 7.0 * sqrt(sqrt(((rP - e.rMin) / maxOf(sv.rOut - e.rMin, 1e-6)).coerceIn(0.0, 1.0)))
		return if (rP <= sv.rStart) listOf(-inbound, outbound, 0.0) else listOf(outbound, 0.0)
	}

	private val images by lazy { SchwarzschildImages.buildTable(64, 64, 128) }

	/** The Schwarzschild image direction of p (local frame, hole at origin), as the vertex lens's guess; null: hidden. */
	private fun schwarzschildGuess(scene: Scene, p: V): V? {
		val rs = scene.rs
		val rc = scene.camera.length()
		val fromHole = p
		val rp = fromHole.length()
		val u0 = rs / rc
		val uP = rs / rp
		val e1 = scene.camera.normalized()
		val radial = fromHole.normalized()
		val cosPhi = radial.dot(e1).coerceIn(-1.0, 1.0)
		val sideV = radial - e1 * cosPhi
		if (sideV.length() < 1e-9) return null
		val e2 = sideV.normalized()
		val phi = kotlin.math.acos(cosPhi)
		val local = SchwarzschildImages.sample(images, 64, 64, 128, u0, uP, phi)
		if (local.isNaN()) return null
		val psi = atan2(sin(local), cos(local) * sqrt(1.0 - u0))
		return -e1 * cos(psi) + e2 * sin(psi)
	}

	/** Table position of view direction d (local frame); null behind the camera. */
	private fun tablePosition(scene: Scene, d: V): DoubleArray? {
		val z = d.dot(scene.forward)
		if (z < 1e-3) return null
		val tx = d.dot(scene.right) / z
		val ty = d.dot(scene.up) / z
		return doubleArrayOf((tx / scene.tanX * 0.5 + 0.5) * scene.width, (ty / scene.tanY * 0.5 + 0.5) * scene.height)
	}

	/** How close the light seen along direction d (from the camera) passes to p: the vertex lens's error there. */
	private fun miss(scene: Scene, d: V, p: V): Double {
		val m = 0.5 * scene.rs
		val a = scene.spin * m
		val horizon = m + sqrt(maxOf(m * m - a * a, 0.0))
		var x = scene.camera
		var (mom, pt) = nullMomentum(x, -d, m, a)
		val k1 = arrayOf(V(0.0, 0.0, 0.0), V(0.0, 0.0, 0.0))
		val k2 = arrayOf(V(0.0, 0.0, 0.0), V(0.0, 0.0, 0.0))
		val k3 = arrayOf(V(0.0, 0.0, 0.0), V(0.0, 0.0, 0.0))
		val k4 = arrayOf(V(0.0, 0.0, 0.0), V(0.0, 0.0, 0.0))
		var best = (x - p).length()
		var travelled = 0.0
		val limit = 3.0 * (p - scene.camera).length() + 40.0
		while (travelled < limit) {
			val r = backward(x, mom, pt, m, a, k1)
			if (r < horizon * 1.02) break
			val dl = 0.02 * maxOf(r, 0.5 * m) / maxOf(k1[0].length(), 1e-6)
			backward(x + k1[0] * (0.5 * dl), mom + k1[1] * (0.5 * dl), pt, m, a, k2)
			backward(x + k2[0] * (0.5 * dl), mom + k2[1] * (0.5 * dl), pt, m, a, k3)
			backward(x + k3[0] * dl, mom + k3[1] * dl, pt, m, a, k4)
			val next = x + (k1[0] + k2[0] * 2.0 + k3[0] * 2.0 + k4[0]) * (dl / 6.0)
			mom = mom + (k1[1] + k2[1] * 2.0 + k3[1] * 2.0 + k4[1]) * (dl / 6.0)
			// Closest approach to p on this segment.
			val seg = next - x
			val t = ((p - x).dot(seg) / maxOf(seg.dot(seg), 1e-12)).coerceIn(0.0, 1.0)
			best = minOf(best, (x + seg * t - p).length())
			travelled += seg.length()
			x = next
		}
		return best
	}

	@Test
	fun `floor below a spinning hole is placed where its light arrives from, without holes`() {
		// Game scene kerrVertex: hole r_s 2 at 1.6 below eye level, camera 15 blocks off; the floor 7 below the hole.
		val rs = 2.0
		val spin = 0.9
		val camera = V(0.0, -15.0, 1.6) // local (game z, x, y)
		val forward = (V(0.0, 1.0, 0.0) + V(0.0, 0.0, -0.18)).normalized()
		val right = forward.cross(V(0.0, 0.0, 1.0)).normalized()
		val up = right.cross(forward)
		val tanY = 1.3 * tan(Math.toRadians(35.0))
		val scene = Scene(camera, rs, spin, maxOf(80.0 * rs, 15.0 + 1.5 * 96.0), forward, right, up, 1.5 * tanY, tanY, 489, 326)
		val sv = Solve(scene, minOf(profile(camera, 0.5 * rs, spin * 0.5 * rs).r, scene.pathRadius), scene.pathRadius)
		var solved = 0
		var outside = 0
		var hiddenByS = 0
		var failed = 0
		var failedWithImage = 0
		var wrongSides = 0
		var worst = 0.0
		val rows = ArrayList<String>()
		for (gy in 60 downTo -60 step 4) {
			val row = StringBuilder()
			for (gx in -30..90 step 3) {
				val p = V(gy.toDouble(), gx.toDouble(), -7.0) // local x = game z, local y = game x
				if (p.length() < 2.5 * rs) {
					row.append(' ')
					continue
				}
				val guess = schwarzschildGuess(scene, p)
				val n = p.normalized()
				val rP = profile(p, 0.5 * rs, spin * 0.5 * rs).r
				val startPos = guess?.let { tablePosition(scene, it) } ?: tablePosition(scene, (p - camera).normalized())
				if (startPos == null) {
					row.append('.')
					outside++
					continue
				}
				// Starting near the table's edge (off screen): if the solve fails, the table can't say (Schwarzschild).
				val edge = 32.0
				val inside = startPos[0] >= 1.0 && startPos[1] >= 1.0 && startPos[0] <= scene.width - 1.0 && startPos[1] <= scene.height - 1.0
				sv.unmarched = startPos[0] < edge || startPos[1] < edge || startPos[0] > scene.width - edge || startPos[1] > scene.height - edge
				var found: DoubleArray? = null
				// The image on the same side of the hole (on screen) as the start: the primary, not the other.
				val holeAt = tablePosition(scene, -camera.normalized())!!
				fun sameSide(x: DoubleArray) = (startPos[0] - holeAt[0]) * (x[0] - holeAt[0]) + (startPos[1] - holeAt[1]) * (x[1] - holeAt[1]) >= 0.0
				if (inside) for (v0 in startPositions(sv, startPos, rP)) {
					found = kerrSolve(sv, startPos, n, rP, v0)?.takeIf(::sameSide)
					if (found != null) break
				}
				if (found == null && sv.unmarched && guess != null) {
					// The table can't say: the Schwarzschild image, as the shader does.
					val error = miss(scene, guess, p)
					solved++
					row.append(if (error > 0.5) 'X' else 's')
					if (error > 0.5 && gy == 0) println("X at (${gx}, ${gy}): start ${startPos.toList().map { "%.1f".format(it) }} of ${scene.width}x${scene.height}, miss ${"%.2f".format(error)}")
				} else if (found != null) {
					val d = scene.direction(found[0], found[1])
					val error = miss(scene, d, p)
					worst = maxOf(worst, error / maxOf(1.0, (p - camera).length() / 15.0))
					solved++
					// The same side of the hole (on screen) as the Schwarzschild image: the primary image, not the other.
					val wrongSide = guess != null && run {
						val g = tablePosition(scene, guess)!!
						(g[0] - holeAt[0]) * (found[0] - holeAt[0]) + (g[1] - holeAt[1]) * (found[1] - holeAt[1]) < 0.0
					}
					if (wrongSide) wrongSides++
					row.append(if (wrongSide) 'W' else if (error > 0.2) 'x' else 'o')
				} else if (guess == null) {
					hiddenByS++
					row.append('-')
				} else {
					failed++
					// Does an image exist near the guess? (A failed solve there leaves a hole.)
					var best = Double.MAX_VALUE
					for (i in -6..6) for (j in -6..6) {
						val d = scene.direction(startPos[0] + i * 2.0, startPos[1] + j * 2.0)
						best = minOf(best, miss(scene, d, p))
					}
					if (best < 0.5) {
						failedWithImage++
						row.append('#')
					} else {
						row.append('?')
						// Near the line behind the hole its images crowd the Einstein ring, left (like the Schwarzschild
						// lens's near-axis cut) to the post pass's ring.
					}
				}
			}
			rows += row.toString()
		}
		val summary = "solved $solved (worst miss ${"%.3f".format(worst)}, $wrongSides on the wrong side), outside $outside, hidden (no Schwarzschild image) $hiddenByS, " +
			"failed $failed ($failedWithImage with an image nearby)\n" + rows.joinToString("\n")
		println(summary)
		assertTrue(failedWithImage == 0 && wrongSides == 0, summary)
	}

	/**
	 * With run/kerr_paths_dump.bin (LensClientGameTest.kerrVertex writes it): the GPU's path table against this mirror's
	 * (texel by texel), and the floor solved on the GPU's table, to tell the pass's precision from the solve's.
	 */
	@Test
	fun `the GPU's path table matches the mirror`() {
		val file = java.io.File("../run/kerr_paths_dump.bin")
		if (!file.exists()) return
		val input = java.io.DataInputStream(java.io.BufferedInputStream(file.inputStream()))
		val width = input.readInt()
		val height = input.readInt()
		val draw = DoubleArray(8) { input.readFloat().toDouble() }
		val m = Array(3) { DoubleArray(3) } // m[column][row]
		for (c in 0..2) for (r in 0..2) m[c][r] = input.readFloat().toDouble()
		val data = FloatArray(width * height * 8 * 4) { input.readFloat() }
		input.close()
		fun local(gx: Double, gy: Double, gz: Double) = V(gz, gx, gy)
		fun viewToLocal(vx: Double, vy: Double, vz: Double): V {
			val g = DoubleArray(3) { r -> m[0][r] * vx + m[1][r] * vy + m[2][r] * vz }
			return local(g[0], g[1], g[2])
		}
		val holeGame = local(draw[0], draw[1], draw[2])
		val rs = draw[3]
		val spin = draw[4]
		val camera = -holeGame
		val scene = Scene(camera, rs, spin, draw[5], viewToLocal(0.0, 0.0, -1.0), viewToLocal(1.0, 0.0, 0.0), viewToLocal(0.0, 1.0, 0.0), draw[6], draw[7], width, height)
		fun texel(layer: Int, i: Int, j: Int, c: Int) = data[((layer * height + j) * width + i) * 4 + c].toDouble()
		fun gpuEntry(i: Int, j: Int): PathEntry {
			val samples = Array(15) { DoubleArray(2) }
			samples[7] = doubleArrayOf(texel(0, i, j, 2), texel(0, i, j, 3))
			for (k in 1..7) {
				for ((jj, f) in listOf(-k to k - 1, k to k + 6)) {
					val layer = 1 + f / 2
					val c = (f % 2) * 2
					samples[jj + 7] = doubleArrayOf(texel(layer, i, j, c), texel(layer, i, j, c + 1))
				}
			}
			return PathEntry(texel(0, i, j, 0), (texel(0, i, j, 1) + 0.5).toInt(), samples)
		}
		// Texel by texel, a sample.
		var worstAngle = 0.0
		var worstR = 0.0
		var statusDiffers = 0
		var compared = 0
		for (j in 0 until height step 13) for (i in 0 until width step 17) {
			val cpu = tracePath(scene, i, j)
			val gpu = gpuEntry(i, j)
			compared++
			if (cpu.status != gpu.status) {
				statusDiffers++
				continue
			}
			if (cpu.status != 1) continue
			worstR = maxOf(worstR, abs(cpu.rMin - gpu.rMin) / cpu.rMin)
			for (k in 0 until 15) worstAngle = maxOf(worstAngle, abs(cpu.samples[k][0] - gpu.samples[k][0]), abs(cpu.samples[k][1] - gpu.samples[k][1]))
		}
		println("GPU vs mirror table: $compared texels, status differs in $statusDiffers, worst r_min ${"%.2e".format(worstR)}, worst sample angle ${"%.2e".format(worstAngle)}")
		// The floor (game y -60, the hole at -54: 6 below it) solved on the GPU's table and on the mirror's.
		val gpuScene = Scene(camera, rs, spin, draw[5], scene.forward, scene.right, scene.up, draw[6], draw[7], width, height).also { sc -> sc.source = ::gpuEntry }
		val rStart = minOf(profile(camera, 0.5 * rs, spin * 0.5 * rs).r, draw[5])
		val rows = ArrayList<String>()
		var gpuOnlyFailures = 0
		for (gy in 96 downTo -96 step 6) {
			val row = StringBuilder()
			for (gx in -14..96 step 4) {
				val p = local(gx.toDouble() - 0.5, -6.0, gy.toDouble() - 0.5) // world (x, z) relative to the hole at x, z = 0.5
				val guess = schwarzschildGuess(scene, p)
				val start = guess?.let { tablePosition(scene, it) } ?: tablePosition(scene, (p - camera).normalized())
				if (p.length() < 2.5 * rs || start == null) { row.append(' '); continue }
				val rP = profile(p, 0.5 * rs, spin * 0.5 * rs).r
				fun solve(sc: Scene): Int {
					val sv = Solve(sc, rStart, draw[5])
					val inside = start[0] >= 1.0 && start[1] >= 1.0 && start[0] <= sc.width - 1.0 && start[1] <= sc.height - 1.0
					sv.unmarched = start[0] < 32.0 || start[1] < 32.0 || start[0] > sc.width - 32.0 || start[1] > sc.height - 32.0
					var found: DoubleArray? = null
					if (inside) for (v0 in startPositions(sv, start, rP)) {
						found = kerrSolve(sv, start, p.normalized(), rP, v0)
						if (found != null) break
					}
					return if (found != null) 1 else if (sv.unmarched) 0 else -1
				}
				val g = solve(gpuScene)
				val c = solve(scene)
				if (g < 0 && c >= 0) gpuOnlyFailures++
				if (g < 0 && abs(gy) > 30) println("fails at block corner (${gx}, ${gy}) relative to the hole: rP ${"%.2f".format(rP)}, start ${start.toList().map { "%.1f".format(it) }}, guess ${guess != null}")
				row.append(when {
					g == c -> if (g == 1) 'o' else if (g == 0) 's' else '?'
					g < 0 -> '#' // fails on the GPU's table only
					else -> '+'
				})
			}
			rows += row.toString()
		}
		println("floor on the GPU's table (# fails only there):\n" + rows.joinToString("\n"))
		assertTrue(gpuOnlyFailures == 0, "$gpuOnlyFailures floor points fail only on the GPU's table")
	}
}
