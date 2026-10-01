package net.kernelpanicsoft.relativity.physics

/**
 * Physical constants in game units: lengths in blocks, time in seconds, mass in Sable mass units.
 *
 * @property c speed of light [blocks/s]
 * @property g gravitational constant [blocks³ / (mass · s²)]
 */
data class SpacetimeUnits(val c: Double = DEFAULT_C, val g: Double = DEFAULT_G) {
	init {
		require(c > 0.0) { "c must be positive, was $c" }
		require(g > 0.0) { "G must be positive, was $g" }
	}

	/** Schwarzschild radius `2GM/c²` of [mass], in blocks. */
	fun schwarzschildRadius(mass: Double): Double = 2.0 * g * mass / (c * c)

	/** Mass whose Schwarzschild radius is [radius] blocks. */
	fun massForRadius(radius: Double): Double = radius * c * c / (2.0 * g)

	companion object {
		const val DEFAULT_C = 64.0

		/** Mass with a one-block Schwarzschild radius: 10k default-mass Sable blocks (1.0 each). */
		const val DEFAULT_UNIT_RADIUS_MASS = 1.0e4

		const val DEFAULT_G = DEFAULT_C * DEFAULT_C / (2.0 * DEFAULT_UNIT_RADIUS_MASS)

		/** Units with speed of light [c] where a mass of [unitRadiusMass] has `r_s = 1` block. */
		fun withUnitRadiusMass(c: Double, unitRadiusMass: Double): SpacetimeUnits {
			require(unitRadiusMass > 0.0) { "unitRadiusMass must be positive, was $unitRadiusMass" }
			return SpacetimeUnits(c, c * c / (2.0 * unitRadiusMass))
		}
	}
}
