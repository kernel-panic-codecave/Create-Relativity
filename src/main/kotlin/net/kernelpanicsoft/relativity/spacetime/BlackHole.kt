package net.kernelpanicsoft.relativity.spacetime

import net.kernelpanicsoft.relativity.physics.orbit.KerrOrbits
import net.kernelpanicsoft.relativity.physics.spacetime.Kerr
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * A black hole in a level: centre in block coordinates, Schwarzschild radius `r_s = 2M` in blocks, and
 * dimensionless spin `χ = a/M` about world up (positive: counter-clockwise seen from above; negative: clockwise).
 * Size is set in blocks rather than mass, so it means the same thing whatever the dimension's speed of light is;
 * mass follows from the units.
 *
 * An immutable snapshot of a [net.kernelpanicsoft.relativity.entity.BlackHoleEntity]; [id] is the entity's UUID.
 *
 * [accretionRate] is the recent rate of swallowed mass (Sable mass units per second, smoothed); it drives the disk's
 * brightness and radiation pressure. [feedRate] is a steady artificial feed through the disk (`/relativity bh feed`).
 */
data class BlackHole(
	val id: String = UUID.randomUUID().toString(),
	val x: Double,
	val y: Double,
	val z: Double,
	val schwarzschildRadius: Double,
	val spin: Double = 0.0,
	val accretionRate: Double = 0.0,
	val feedRate: Double = 0.0,
) {
	init {
		require(schwarzschildRadius > 0.0) { "Schwarzschild radius must be positive" }
		require(spin in -MAX_SPIN..MAX_SPIN) { "spin must be within ±$MAX_SPIN (Thorne limit)" }
	}

	val center: Vec3 get() = Vec3(x, y, z)

	/** Geometric mass `M = r_s / 2`, in blocks. */
	val mass: Double get() = schwarzschildRadius / 2.0

	fun spacetime(): Kerr = Kerr(mass, spin * mass)

	/** Outer horizon, in Boyer–Lindquist radius (blocks). */
	val horizonRadius: Double get() = spacetime().horizonRadius

	/** [prograde] means co-rotating with the hole, whichever way it spins. */
	fun photonOrbit(prograde: Boolean): Double = KerrOrbits.photonOrbit(mass, kotlin.math.abs(spin), prograde)

	/** [prograde] means co-rotating with the hole, whichever way it spins. */
	fun isco(prograde: Boolean): Double = KerrOrbits.isco(mass, kotlin.math.abs(spin), prograde)

	/** Cylindrical radius in the equatorial plane of the Boyer–Lindquist circle [r] (Kerr–Schild: `√(r² + a²)`). */
	/**
	 * Outer edge of the ergosphere (spheroidal r) at polar angle with cosine [cosTheta] from the spin axis:
	 * M + √(M² − a² cos²θ). Inside it nothing can stay still; everything is dragged around with the spin.
	 */
	fun ergosphereRadius(cosTheta: Double): Double {
		val a = spin * mass
		return mass + kotlin.math.sqrt(mass * mass - a * a * cosTheta * cosTheta)
	}

	fun equatorialExtent(r: Double): Double = kotlin.math.sqrt(r * r + (spin * mass) * (spin * mass))

	companion object {
		/** Thorne's limit: photons from the disk swallowed with the wrong angular momentum stop spin-up here. */
		const val MAX_SPIN = 0.998
	}
}
