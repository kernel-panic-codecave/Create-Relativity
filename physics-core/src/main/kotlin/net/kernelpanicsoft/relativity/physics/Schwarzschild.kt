package net.kernelpanicsoft.relativity.physics

import kotlin.math.sqrt

/**
 * Closed-form quantities of a Schwarzschild black hole of Schwarzschild radius [rs] (blocks) in [units].
 */
class Schwarzschild(val rs: Double, val units: SpacetimeUnits) {
	val gm: Double get() = rs * units.c * units.c / 2.0
	val photonSphere: Double get() = 1.5 * rs
	val isco: Double get() = 3.0 * rs
	val shadowRadius: Double get() = 1.5 * sqrt(3.0) * rs

	/** `dτ/dt` of a static observer at areal radius [r]; 0 at and inside the horizon. */
	fun staticRedshift(r: Double): Double = if (r <= rs) 0.0 else sqrt(1.0 - rs / r)

	/**
	 * Proper acceleration magnitude a static observer at [r] needs to hover, `GM / (r² √(1 − r_s/r))`.
	 * Diverges at the horizon.
	 */
	fun hoverAcceleration(r: Double): Double = if (r <= rs) Double.POSITIVE_INFINITY else gm / (r * r * staticRedshift(r))

	/** Radial tidal stretching `2GM/r³` per block of separation, in blocks/s². */
	fun radialTidal(r: Double): Double = 2.0 * gm / (r * r * r)
}
