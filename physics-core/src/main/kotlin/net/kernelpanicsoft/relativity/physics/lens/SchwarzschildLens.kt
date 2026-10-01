package net.kernelpanicsoft.relativity.physics.lens

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where light seen by a static camera near a Schwarzschild hole came from, in units of `r_s = 1`.
 *
 * Spherical symmetry keeps every ray in the plane through the hole, the camera and the view direction.
 * In that plane put the camera at polar angle 0, distance `r_c`, with the ray moving toward +φ and leaving
 * the camera at angle ψ from the inward radial. A ray that escapes leaves along polar angle φ∞ (its outgoing
 * direction is `cos φ∞ ê₁ + sin φ∞ ê₂`, with ê₁ pointing from the hole to the camera); without gravity
 * φ∞ = π − ψ. Integrates the null orbit equation `d²u/dφ² = −u + (3/2) u²` (u = r_s/r) with RK4 in φ.
 */
object SchwarzschildLens {
	/** Rays still circling after this much polar angle are counted as captured (they hug the photon sphere). */
	const val MAX_SWEEP = 6.0 * PI

	private const val STEP = 0.004

	/** Escape angle φ∞, or [Double.NaN] if the ray falls into the hole. [u0] is `r_s / r_c` in `[0, 1)`. */
	fun escapeAngle(u0: Double, psi: Double): Double {
		require(u0 in 0.0..<1.0) { "camera must be outside the horizon, u0=$u0" }
		val s = sin(psi)
		if (s < 1e-12) return if (cos(psi) > 0) Double.NaN else 0.0 // straight in, or straight out
		if (u0 == 0.0) return farCameraEscape(psi)
		// Static observer: impact parameter b = r_c sin ψ / √(1 − r_s/r_c), so 1/b² = u₀²(1 − u₀)/sin²ψ;
		// orbit energy (u')² = 1/b² − u² + u³.
		val invB2 = u0 * u0 * (1.0 - u0) / (s * s)
		var u = u0
		var du = sqrt(maxOf(invB2 - u0 * u0 + u0 * u0 * u0, 0.0)) * if (cos(psi) > 0) 1.0 else -1.0
		var phi = 0.0
		while (phi < MAX_SWEEP) {
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
			val k4u = d4
			val k4d = -u4 + 1.5 * u4 * u4
			val uNext = u + h / 6.0 * (k1u + 2 * k2u + 2 * k3u + k4u)
			val duNext = du + h / 6.0 * (k1d + 2 * k2d + 2 * k3d + k4d)
			if (uNext >= 1.0) return Double.NaN // through the horizon
			if (uNext <= 0.0) return phi + h * u / (u - uNext) // reached infinity: interpolate the crossing
			u = uNext
			du = duNext
			phi += h
		}
		return Double.NaN
	}

	/** Camera infinitely far away: only ψ = 0 rays approach; everything else is unbent. */
	private fun farCameraEscape(psi: Double): Double = PI - psi

	/** Lookup-table axis mappings, shared with the shader: x = √u₀, y = √(ψ/π). */
	fun u0At(x: Double) = x * x

	fun psiAt(y: Double) = PI * y * y

	/**
	 * Builds a [width]×[height] table, row-major from y = 0, two floats per texel: (φ∞, captured).
	 * Captured rays store 1 in the second channel and [MAX_SWEEP] in the first. Rows build in parallel.
	 */
	fun buildTable(width: Int, height: Int): FloatArray {
		val data = FloatArray(width * height * 2)
		java.util.stream.IntStream.range(0, height).parallel().forEach { j ->
			val psi = psiAt(j / (height - 1.0))
			for (i in 0 until width) {
				val u0 = minOf(u0At(i / (width - 1.0)), 0.999)
				val phi = escapeAngle(u0, psi)
				val idx = (j * width + i) * 2
				if (phi.isNaN()) {
					data[idx] = MAX_SWEEP.toFloat()
					data[idx + 1] = 1f
				} else {
					data[idx] = phi.toFloat()
					data[idx + 1] = 0f
				}
			}
		}
		return data
	}
}
