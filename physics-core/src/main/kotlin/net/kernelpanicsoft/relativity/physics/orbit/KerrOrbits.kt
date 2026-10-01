package net.kernelpanicsoft.relativity.physics.orbit

import kotlin.math.acos
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Closed-form equatorial orbit radii of a Kerr hole (Bardeen, Press & Teukolsky 1972), in Boyer–Lindquist
 * radius. [m] is the mass, [chi] the dimensionless spin `a/M` in `[0, 1]`; prograde orbits co-rotate.
 */
object KerrOrbits {
	/** Innermost stable circular orbit. 6M for Schwarzschild; → M (prograde) and 9M (retrograde) at `χ = 1`. */
	fun isco(m: Double, chi: Double, prograde: Boolean = true): Double {
		val z1 = 1.0 + cbrt(1.0 - chi * chi) * (cbrt(1.0 + chi) + cbrt(1.0 - chi))
		val z2 = sqrt(3.0 * chi * chi + z1 * z1)
		val root = sqrt((3.0 - z1) * (3.0 + z1 + 2.0 * z2))
		return m * (3.0 + z2 + if (prograde) -root else root)
	}

	/** Circular photon orbit. 3M for Schwarzschild; → M (prograde) and 4M (retrograde) at `χ = 1`. */
	fun photonOrbit(m: Double, chi: Double, prograde: Boolean = true): Double =
		2.0 * m * (1.0 + cos(2.0 / 3.0 * acos(if (prograde) -chi else chi)))

	/** Coordinate angular velocity `dφ/dt` of a circular equatorial orbit at radius [r] (signed: + is prograde). */
	fun angularVelocity(m: Double, chi: Double, r: Double, prograde: Boolean = true): Double {
		val a = chi * m
		val sqrtM = sqrt(m)
		return if (prograde) sqrtM / (r * sqrt(r) + a * sqrtM) else -sqrtM / (r * sqrt(r) - a * sqrtM)
	}
}
