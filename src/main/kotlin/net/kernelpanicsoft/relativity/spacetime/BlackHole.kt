package net.kernelpanicsoft.relativity.spacetime

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.relativity.physics.orbit.KerrOrbits
import net.kernelpanicsoft.relativity.physics.spacetime.Kerr
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * A black hole in a level: centre in block coordinates, Schwarzschild radius `r_s = 2M` in blocks, and
 * dimensionless spin `χ = a/M` about world up. Size is set in blocks rather than mass, so it means the same
 * thing whatever the dimension's speed of light is; mass follows from the units.
 */
@Serializable
data class BlackHole(
	val id: String = UUID.randomUUID().toString(),
	val x: Double,
	val y: Double,
	val z: Double,
	val schwarzschildRadius: Double,
	val spin: Double = 0.0,
) {
	init {
		require(schwarzschildRadius > 0.0) { "Schwarzschild radius must be positive" }
		require(spin in 0.0..0.998) { "spin must be in [0, 0.998] (Thorne limit)" }
	}

	val center: Vec3 get() = Vec3(x, y, z)

	/** Geometric mass `M = r_s / 2`, in blocks. */
	val mass: Double get() = schwarzschildRadius / 2.0

	fun spacetime(): Kerr = Kerr(mass, spin * mass)

	/** Outer horizon, in Boyer–Lindquist radius (blocks). */
	val horizonRadius: Double get() = spacetime().horizonRadius

	fun photonOrbit(prograde: Boolean): Double = KerrOrbits.photonOrbit(mass, spin, prograde)

	fun isco(prograde: Boolean): Double = KerrOrbits.isco(mass, spin, prograde)

	/** Cylindrical radius in the equatorial plane of the Boyer–Lindquist circle [r] (Kerr–Schild: `√(r² + a²)`). */
	fun equatorialExtent(r: Double): Double = kotlin.math.sqrt(r * r + (spin * mass) * (spin * mass))
}
