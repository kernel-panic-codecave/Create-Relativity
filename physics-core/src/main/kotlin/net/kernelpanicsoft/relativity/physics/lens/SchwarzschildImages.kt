package net.kernelpanicsoft.relativity.physics.lens

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Where a camera near a Schwarzschild hole sees a point at finite distance (units of r_s = 1): the inverse of tracing.
 *
 * By symmetry the light from a point P to the camera stays in the plane through the hole, the camera and P. In it, put
 * the camera at polar angle 0 and distance r_c, and P at distance r_P and polar angle Φ ∈ [0, π]. The light P sends to
 * the camera's **primary image** leaves the camera (traced backwards) at angle ψ from the inward radial, toward P's
 * side; [launchAngle] finds that ψ. Secondary images (the long way round) aren't covered; the lens pass draws those.
 *
 * Rays from the camera are followed with the orbit equation u'' = −u + (3/2)u² (u = r_s/r) until they've swept π.
 * A target radius farther out than the camera (u_P ≤ u₀) is crossed once, on the way out; one closer in is crossed
 * on the way in and, by rays that turn around before reaching the hole, again on the way out. Along that sequence of
 * crossings the swept angle grows monotonically, so it can be inverted.
 */
object SchwarzschildImages {
	private const val STEP = 0.004

	/** Nearest the table goes to infinity: 10⁴ r_s, where light is effectively straight. At u = 0 nothing is ever crossed. */
	const val MIN_U = 1e-4

	/** Table axes, shared with the shader: x = √u₀, y = √u_P, z = Φ/π, each sampled at texel centres over [0, 1]. */
	fun u0At(x: Double) = (x * x).coerceIn(MIN_U, 0.999)

	fun uPAt(y: Double) = (y * y).coerceIn(MIN_U, 0.999)

	fun phiAt(z: Double) = PI * z

	/**
	 * Swept angle at each crossing of each target u in [targets] (ascending), by the ray leaving u₀ at angle [psi],
	 * up to angle π + margin. Result[i] holds the crossings of targets[i] in order (at most two).
	 */
	fun crossings(u0: Double, psi: Double, targets: DoubleArray): Array<DoubleArray> {
		val found = Array(targets.size) { DoubleArray(2) { Double.NaN } }
		val counts = IntArray(targets.size)
		val s = kotlin.math.sin(psi)
		if (s < 1e-9) {
			// Straight in (or out): every target on the way is reached at angle 0.
			for (i in targets.indices) {
				val reached = if (cos(psi) > 0.0) targets[i] >= u0 && targets[i] < 1.0 else targets[i] <= u0
				if (reached) found[i][0] = 0.0
			}
			return found
		}
		val invB2 = u0 * u0 * (1.0 - u0) / (s * s)
		var u = u0
		var du = sqrt(maxOf(invB2 - u0 * u0 + u0 * u0 * u0, 0.0)) * if (cos(psi) > 0) 1.0 else -1.0
		var phi = 0.0
		val limit = PI + 0.05
		while (phi < limit) {
			val h = STEP
			val k1u = du
			val k1d = -u + 1.5 * u * u
			val u2 = u + 0.5 * h * k1u
			val d2 = du + 0.5 * h * k1d
			val k2u = d2
			val k2d = -u2 + 1.5 * u2 * u2
			val u3 = u + 0.5 * h * k2u
			val d3 = du + 0.5 * h * k2d
			val k3u = d3
			val k3d = -u3 + 1.5 * u3 * u3
			val u4 = u + h * k3u
			val d4 = du + h * k3d
			val k4d = -u4 + 1.5 * u4 * u4
			val uNext = u + h / 6.0 * (k1u + 2 * k2u + 2 * k3u + d4)
			val duNext = du + h / 6.0 * (k1d + 2 * k2d + 2 * k3d + k4d)
			for (i in targets.indices) {
				val t = targets[i]
				// Passing through t during this step; starting exactly on it (the camera's own radius) isn't a crossing.
				val before = u - t
				val after = uNext - t
				if (counts[i] < 2 && before != 0.0 && (after == 0.0 || (before > 0.0) != (after > 0.0))) {
					found[i][counts[i]] = phi + h * (t - u) / (uNext - u)
					counts[i]++
				}
			}
			if (uNext >= 1.0 || uNext <= 0.0) break // fell in, or escaped
			u = uNext
			du = duNext
			phi += h
		}
		return found
	}

	/** Table value meaning "no primary image" (inside the horizon, or hidden behind the hole). */
	const val NONE = -10f

	/**
	 * Launch angle without gravity: the direction from the camera at (r_c, 0) to P at (r_P cos Φ, r_P sin Φ), measured
	 * from the inward radial toward P's side.
	 */
	fun flatAngle(u0: Double, uP: Double, phi: Double): Double {
		val rc = 1.0 / u0
		val rp = 1.0 / uP
		return kotlin.math.atan2(rp * kotlin.math.sin(phi), rc - rp * cos(phi))
	}

	/**
	 * Builds the table: [n0] × [nP] × [nPhi] floats, x fastest, then y, then z. Each holds the primary image's launch
	 * angle minus [flatAngle] (the bending correction, small and smooth wherever the field is weak, so it interpolates
	 * well where the raw angle doesn't), or [NONE].
	 */
	fun buildTable(n0: Int, nP: Int, nPhi: Int, rays: Int = 1024): FloatArray {
		val data = FloatArray(n0 * nP * nPhi) { NONE }
		val targets = DoubleArray(nP) { uPAt(it / (nP - 1.0)) }
		java.util.stream.IntStream.range(0, n0).parallel().forEach { i ->
			val u0 = u0At(i / (n0 - 1.0))
			val psis = DoubleArray(rays) { PI * (it + 0.5) / rays }
			val hits = Array(rays) { crossings(u0, psis[it], targets) }
			for (j in 0 until nP) {
				val curve = curve(u0, targets[j], psis, hits, j)
				for (k in 0 until nPhi) {
					val phi = phiAt(k / (nPhi - 1.0))
					val psi = invert(curve, phi)
					if (!psi.isNaN()) data[(k * nP + j) * n0 + i] = (psi - flatAngle(u0, targets[j], phi)).toFloat()
				}
			}
		}
		return data
	}

	/** The crossings of target j as (swept angle, ψ) points in the order along which the angle grows. */
	private fun curve(u0: Double, uP: Double, psis: DoubleArray, hits: Array<Array<DoubleArray>>, j: Int): List<DoubleArray> {
		val points = ArrayList<DoubleArray>()
		if (uP <= u0) {
			// Farther than the camera: one crossing; straight out (ψ = π) first, then ever more inward launches.
			for (n in psis.indices.reversed()) {
				val phi = hits[n][j][0]
				if (!phi.isNaN()) points += doubleArrayOf(phi, psis[n])
			}
		} else {
			// Closer in: first crossings going in, from straight in outward; then second crossings, back the other way.
			for (n in psis.indices) {
				val phi = hits[n][j][0]
				if (!phi.isNaN()) points += doubleArrayOf(phi, psis[n])
			}
			for (n in psis.indices.reversed()) {
				val phi = hits[n][j][1]
				if (!phi.isNaN()) points += doubleArrayOf(phi, psis[n])
			}
		}
		return points
	}

	/** ψ where the curve first reaches swept angle [phi], interpolated; NaN if it never does. */
	private fun invert(curve: List<DoubleArray>, phi: Double): Double {
		if (curve.isEmpty()) return Double.NaN
		if (phi <= curve[0][0]) return if (curve[0][0] - phi < 1e-3) curve[0][1] else Double.NaN
		for (n in 1 until curve.size) {
			val a = curve[n - 1]
			val b = curve[n]
			if (phi >= a[0] && phi <= b[0]) {
				val t = if (b[0] > a[0]) (phi - a[0]) / (b[0] - a[0]) else 0.0
				return a[1] + t * (b[1] - a[1])
			}
		}
		return Double.NaN
	}

	/**
	 * [table] as interleaved (correction, valid) pairs for a GPU texture with linear filtering: [NONE] cells take the
	 * nearest valid correction along Φ (so filtering never blends in the sentinel) and valid 0, which interpolates to
	 * the edge of the region with no image.
	 */
	fun forFiltering(table: FloatArray, n0: Int, nP: Int, nPhi: Int): FloatArray {
		val out = FloatArray(table.size * 2)
		for (j in 0 until nP) for (i in 0 until n0) {
			fun at(k: Int) = (k * nP + j) * n0 + i
			val first = (0 until nPhi).firstOrNull { table[at(it)] > NONE }
			var last = if (first != null) table[at(first)] else 0f
			for (k in 0 until nPhi) {
				val v = table[at(k)]
				val valid = v > NONE
				if (valid) last = v
				out[2 * at(k)] = last
				out[2 * at(k) + 1] = if (valid) 1f else 0f
			}
		}
		return out
	}

	/** Launch angle from a [buildTable] table by trilinear lookup of the correction, as the shader does; NaN where any corner has no image. */
	fun sample(table: FloatArray, n0: Int, nP: Int, nPhi: Int, u0: Double, uP: Double, phi: Double): Double {
		if (uP >= 1.0 || u0 >= 1.0) return Double.NaN // inside the horizon
		val fx = (sqrt(u0) * (n0 - 1)).coerceIn(0.0, n0 - 1.0)
		val fy = (sqrt(uP) * (nP - 1)).coerceIn(0.0, nP - 1.0)
		val fz = (phi / PI * (nPhi - 1)).coerceIn(0.0, nPhi - 1.0)
		val x0 = floor(fx).toInt().coerceAtMost(n0 - 2)
		val y0 = floor(fy).toInt().coerceAtMost(nP - 2)
		val z0 = floor(fz).toInt().coerceAtMost(nPhi - 2)
		val u0c = u0.coerceAtLeast(MIN_U)
		val uPc = uP.coerceAtLeast(MIN_U)
		var sum = 0.0
		for (dz in 0..1) for (dy in 0..1) for (dx in 0..1) {
			val v = table[((z0 + dz) * nP + (y0 + dy)) * n0 + (x0 + dx)]
			if (v <= NONE) return Double.NaN
			val w = (if (dx == 0) 1 - (fx - x0) else fx - x0) * (if (dy == 0) 1 - (fy - y0) else fy - y0) * (if (dz == 0) 1 - (fz - z0) else fz - z0)
			sum += w * v
		}
		return flatAngle(u0c, uPc, phi) + sum
	}
}
