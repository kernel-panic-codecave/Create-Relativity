package net.kernelpanicsoft.relativity.physics.lens

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Where a camera near a Schwarzschild hole sees a point at finite distance (units of r_s = 1): the inverse of tracing.
 *
 * By symmetry the light from a point P to the camera stays in the plane through the hole, the camera and P. In it, put
 * the camera at polar angle 0 and distance r_c, and P at distance r_P and polar angle Φ ∈ [0, π]. The light P sends to
 * the camera's **primary image** leaves the camera (traced backwards) at angle ψ from the inward radial, toward P's
 * side, sweeping Φ round the hole; [buildTable]. Its **secondary image** goes the long way round, the other side: it
 * sweeps 2π − Φ, leaving toward the side away from P, just outside the critical angle; [buildSecondaryTable].
 *
 * Rays from the camera are followed with the orbit equation u'' = −u + (3/2)u² (u = r_s/r) until they've swept 2π.
 * A target radius farther out than the camera (u_P ≤ u₀) is crossed once, on the way out; one closer in is crossed
 * on the way in and, by rays that turn around before reaching the hole, again on the way out. Along that sequence of
 * crossings the swept angle grows monotonically, so it can be inverted.
 */
object SchwarzschildImages {
	private const val STEP = 0.004

	/** Nearest the table goes to infinity: 10⁴ r_s, where light is effectively straight. At u = 0 nothing is ever crossed. */
	const val MIN_U = 1e-4

	/** How far rays are followed round the hole: a full turn (the secondary images sweep up to it). */
	const val SWEEP = 2.0 * PI

	/** Table axes, shared with the shader: x = √u₀, y = √u_P, z = Φ/π, each sampled at texel centres over [0, 1]. */
	fun u0At(x: Double) = (x * x).coerceIn(MIN_U, 0.999)

	fun uPAt(y: Double) = (y * y).coerceIn(MIN_U, 0.999)

	fun phiAt(z: Double) = PI * z

	/**
	 * Swept angle at each crossing of each target u in [targets] (ascending), by the ray leaving u₀ at angle [psi],
	 * up to angle [SWEEP] + margin. Result[i] holds the crossings of targets[i] in order (at most two).
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
		val limit = SWEEP + 0.05
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
			val psis = launchAngles(u0, rays)
			val hits = Array(psis.size) { crossings(u0, psis[it], targets) }
			for (j in 0 until nP) {
				val curve = curve(u0, targets[j], psis, hits, j)
				// On the hole-camera line the light runs radially (straight in or straight out): no correction. The
				// sweep's first ray is only near it, and a nonzero value there pinches the image round that point.
				data[j * n0 + i] = 0f
				for (k in 1 until nPhi) {
					val phi = phiAt(k / (nPhi - 1.0))
					val psi = invert(curve, phi)
					if (!psi.isNaN()) data[(k * nP + j) * n0 + i] = (psi - flatAngle(u0, targets[j], phi)).toFloat()
				}
			}
		}
		return data
	}

	/** The critical launch angle at camera u₀ (from the inward radial: light launched closer to it falls in), or NaN
	 * inside the photon sphere. sin ψc = (3√3/2) u₀ √(1 − u₀): the critical impact parameter (3√3/2) r_s. */
	fun criticalAngle(u0: Double): Double {
		if (u0 >= 2.0 / 3.0) return Double.NaN
		return asin((1.5 * sqrt(3.0) * u0 * sqrt(1.0 - u0)).coerceAtMost(1.0))
	}

	/**
	 * Builds the secondary-image table, laid out as [buildTable]'s (x √u₀, y √u_P, z Φ/π: the point's own angle): each
	 * entry ln(ψ − ψc), the secondary image's launch angle (sweeping 2π − Φ, toward the side away from P) past the
	 * critical angle [criticalAngle], which shrinks by about a constant factor per angle swept, so its log interpolates
	 * almost linearly; or [NONE] (the camera inside the photon sphere, P inside it, or no such image).
	 */
	fun buildSecondaryTable(n0: Int, nP: Int, nPhi: Int, rays: Int = 1024): FloatArray {
		val data = FloatArray(n0 * nP * nPhi) { NONE }
		val targets = DoubleArray(nP) { uPAt(it / (nP - 1.0)) }
		java.util.stream.IntStream.range(0, n0).parallel().forEach { i ->
			val u0 = u0At(i / (n0 - 1.0))
			val critical = criticalAngle(u0)
			if (critical.isNaN()) return@forEach
			val psis = launchAngles(u0, rays)
			val hits = Array(psis.size) { crossings(u0, psis[it], targets) }
			for (j in 0 until nP) {
				val curve = curve(u0, targets[j], psis, hits, j)
				for (k in 0 until nPhi) {
					val psi = invert(curve, 2.0 * PI - phiAt(k / (nPhi - 1.0)))
					if (!psi.isNaN() && psi > critical) data[(k * nP + j) * n0 + i] = ln(psi - critical).toFloat()
				}
			}
		}
		return data
	}

	/** The secondary image's launch angle from a [buildSecondaryTable] table, as the shader reads it; NaN where none. */
	fun sampleSecondary(table: FloatArray, n0: Int, nP: Int, nPhi: Int, u0: Double, uP: Double, phi: Double): Double {
		val critical = criticalAngle(u0)
		if (critical.isNaN()) return Double.NaN
		val logOffset = trilinear(table, n0, nP, nPhi, u0, uP, phi) ?: return Double.NaN
		return critical + kotlin.math.exp(logOffset)
	}

	/**
	 * The launch angles the table's rays leave at: [rays] evenly over 0 .. π, and as many again packed round the critical
	 * angle (the photon sphere's: light leaving just outside it winds round the hole, so the swept angle grows without
	 * bound there and the secondary images all come from a sliver of launch angles), geometrically closer to it on both
	 * sides. Ascending.
	 */
	fun launchAngles(u0: Double, rays: Int): DoubleArray {
		val even = DoubleArray(rays) { PI * (it + 0.5) / rays }
		// Critical impact parameter (3√3/2) r_s: sin ψc = (3√3/2) u₀ √(1 − u₀), from the inward radial.
		val sinCritical = 1.5 * sqrt(3.0) * u0 * sqrt(1.0 - u0)
		if (sinCritical >= 1.0) return even
		val critical = asin(sinCritical)
		val half = rays / 2
		val packed = DoubleArray(2 * half) { n ->
			val k = n % half
			val offset = 1e-7 * Math.pow(0.3 / 1e-7, k / (half - 1.0))
			if (n < half) critical - offset else critical + offset
		}
		return (even + packed).filter { it > 0.0 && it < PI }.sorted().toDoubleArray()
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
		val sum = trilinear(table, n0, nP, nPhi, u0, uP, phi) ?: return Double.NaN
		return flatAngle(u0.coerceAtLeast(MIN_U), uP.coerceAtLeast(MIN_U), phi) + sum
	}

	/** [table] (either kind) at (u₀, u_P, Φ) by trilinear interpolation, as the shader does; null where any corner is [NONE]. */
	private fun trilinear(table: FloatArray, n0: Int, nP: Int, nPhi: Int, u0: Double, uP: Double, phi: Double): Double? {
		if (uP >= 1.0 || u0 >= 1.0) return null // inside the horizon
		val fx = (sqrt(u0) * (n0 - 1)).coerceIn(0.0, n0 - 1.0)
		val fy = (sqrt(uP) * (nP - 1)).coerceIn(0.0, nP - 1.0)
		val fz = (phi / PI * (nPhi - 1)).coerceIn(0.0, nPhi - 1.0)
		val x0 = floor(fx).toInt().coerceAtMost(n0 - 2)
		val y0 = floor(fy).toInt().coerceAtMost(nP - 2)
		val z0 = floor(fz).toInt().coerceAtMost(nPhi - 2)
		var sum = 0.0
		for (dz in 0..1) for (dy in 0..1) for (dx in 0..1) {
			val v = table[((z0 + dz) * nP + (y0 + dy)) * n0 + (x0 + dx)]
			if (v <= NONE) return null
			val w = (if (dx == 0) 1 - (fx - x0) else fx - x0) * (if (dy == 0) 1 - (fy - y0) else fy - y0) * (if (dz == 0) 1 - (fz - z0) else fz - z0)
			sum += w * v
		}
		return sum
	}
}
