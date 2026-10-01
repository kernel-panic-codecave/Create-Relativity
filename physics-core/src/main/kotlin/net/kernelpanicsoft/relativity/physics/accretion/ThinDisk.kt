package net.kernelpanicsoft.relativity.physics.accretion

import net.kernelpanicsoft.relativity.physics.orbit.KerrOrbits
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Prograde circular equatorial orbits of a Kerr hole and the Novikov–Thorne thin accretion disk they make up,
 * in units of the hole's mass (M = 1, so radii are r/M); [chi] is the dimensionless spin a/M in [0, 1).
 */
class ThinDisk(val chi: Double) {
	init {
		require(chi in 0.0..<1.0) { "spin must be in [0, 1), was $chi" }
	}

	/** Inner edge: matter plunges once it spirals past the ISCO. */
	val innerRadius: Double = KerrOrbits.isco(1.0, chi)

	// r^{3/4} √(r^{3/2} − 3r^{1/2} + 2χ)
	private fun denominator(r: Double) = sqrt(r) * sqrt(sqrt(r)) * sqrt(r * sqrt(r) - 3.0 * sqrt(r) + 2.0 * chi)

	/** Specific energy (per unit rest mass) of a circular orbit at radius [r] (Bardeen, Press & Teukolsky). */
	fun energy(r: Double): Double = (r * sqrt(r) - 2.0 * sqrt(r) + chi) / denominator(r)

	/** Specific angular momentum (per unit rest mass, in units of M) of a circular orbit at [r]. */
	fun angularMomentum(r: Double): Double = (r * r - 2.0 * chi * sqrt(r) + chi * chi) / denominator(r)

	/** Coordinate angular velocity dφ/dt of a circular orbit at [r]. */
	fun angularVelocity(r: Double): Double = 1.0 / (r * sqrt(r) + chi)

	/**
	 * Fraction of accreted rest mass radiated before reaching the hole: η = 1 − E_isco.
	 * 5.7% without spin, rising to 42% at maximal spin.
	 */
	val efficiency: Double get() = 1.0 - energy(innerRadius)

	/**
	 * Flux emitted from each face of the disk at radius [r] per unit accretion rate (Page & Thorne 1974):
	 * `F = −(Ω'/(4π√−g (E − ΩL)²)) ∫_{r_in}^{r} (E − ΩL) L' dr`, with √−g = r on the Kerr equator.
	 * Zero at the ISCO, peaking a little outside it.
	 */
	fun flux(r: Double, steps: Int = 2000): Double {
		if (r <= innerRadius) return 0.0
		// Trapezoid in ln r (dr = r d ln r): the integrand changes fastest just outside the ISCO.
		val h = kotlin.math.ln(r / innerRadius) / steps
		var integral = 0.0
		for (i in 0..steps) {
			val x = innerRadius * kotlin.math.exp(i * h)
			val weight = if (i == 0 || i == steps) 0.5 else 1.0
			integral += weight * x * (energy(x) - angularVelocity(x) * angularMomentum(x)) * derivative(::angularMomentum, x)
		}
		integral *= h
		val eOmegaL = energy(r) - angularVelocity(r) * angularMomentum(r)
		return -derivative(::angularVelocity, r) / (4.0 * PI * r * eOmegaL * eOmegaL) * integral
	}

	private fun derivative(f: (Double) -> Double, x: Double): Double {
		val h = 1e-5 * x
		return (f(x + h) - f(x - h)) / (2.0 * h)
	}
}

/** How feeding changes a hole: mass and spin after swallowing rest mass from the ISCO of a disk. */
object SpinUp {
	/**
	 * State after accreting rest mass [dm] (geometric units) from the ISCO onto a hole of mass [m] and spin [chi]:
	 * mass grows by E_isco·dm, angular momentum by L_isco·dm·M. Spin is capped just below 1.
	 * Returns (new mass, new spin).
	 */
	fun accreteFromIsco(m: Double, chi: Double, dm: Double): Pair<Double, Double> {
		val disk = ThinDisk(chi.coerceIn(0.0, 0.9999))
		val r = disk.innerRadius
		val newM = m + disk.energy(r) * dm
		val newJ = chi * m * m + disk.angularMomentum(r) * m * dm
		return newM to (newJ / (newM * newM)).coerceIn(0.0, 0.9999)
	}
}
