package net.kernelpanicsoft.relativity.physics.spacetime

import net.kernelpanicsoft.relativity.physics.math.Dual3
import net.kernelpanicsoft.relativity.physics.math.plus
import net.kernelpanicsoft.relativity.physics.math.times
import kotlin.math.sqrt

/**
 * Kerr black hole of mass [m] (geometric length, `M = r_s / 2`) and spin parameter [a] (length,
 * `|a| ≤ m`), spin along `+z`, in Cartesian Kerr–Schild coordinates. `a = 0` is Schwarzschild.
 *
 * The spheroidal radius `r` solves `r⁴ − (R² − a²) r² − a² z² = 0`; on the equator `x² + y² = r² + a²`.
 */
class Kerr(val m: Double, val a: Double = 0.0) : KerrSchildSpacetime() {
	init {
		require(m > 0.0) { "mass must be positive, was $m" }
		require(kotlin.math.abs(a) <= m) { "|a| must not exceed m (a=$a, m=$m)" }
	}

	/** Dimensionless spin `a / M`. */
	val chi: Double get() = a / m

	/** Outer horizon radius `r₊ = M + √(M² − a²)` (spheroidal r). */
	val horizonRadius: Double get() = m + sqrt(m * m - a * a)

	/** Spheroidal radius at a Cartesian point. */
	fun radius(x: Double, y: Double, z: Double): Double {
		val w = x * x + y * y + z * z - a * a
		return sqrt(0.5 * w + sqrt(0.25 * w * w + a * a * z * z))
	}

	override fun profile(x: Dual3, y: Dual3, z: Dual3): Profile {
		val a2 = a * a
		val w = x * x + y * y + z * z - a2
		val r2 = 0.5 * w + (0.25 * (w * w) + a2 * (z * z)).sqrt()
		val r = r2.sqrt()
		val denom = r2 + a2
		return Profile(
			h = 2.0 * m * (r * r2) / (r2 * r2 + a2 * (z * z)),
			// With these signs a > 0 drags frames counter-clockwise about +z (KerrGeodesicTest checks it).
			lx = (r * x + a * y) / denom,
			ly = (r * y - a * x) / denom,
			lz = z / r,
		)
	}
}
