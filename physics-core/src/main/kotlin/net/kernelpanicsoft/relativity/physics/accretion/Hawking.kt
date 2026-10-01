package net.kernelpanicsoft.relativity.physics.accretion

import kotlin.math.PI

/**
 * Hawking evaporation in geometric units (c = G = 1, lengths in blocks) for a world whose Planck length is
 * [planckLength] blocks. That fixes ħ = l_P², so every Hawking quantity follows from the real formulas.
 */
class Hawking(val planckLength: Double) {
	init {
		require(planckLength > 0.0) { "Planck length must be positive, was $planckLength" }
	}

	private val hbar = planckLength * planckLength

	/** Mass lost per unit (geometric) time by a hole of mass [m]: dM/dt = −ħ/(15360π M²). */
	fun massLossRate(m: Double): Double = hbar / (15360.0 * PI * m * m)

	/** Time (geometric, divide by c for seconds) for a hole of mass [m] to evaporate completely: 5120π M³/ħ. */
	fun lifetime(m: Double): Double = 5120.0 * PI * m * m * m / hbar

	/** Hawking temperature kT = ħ/(8πM), in units of 1/length. Rises as the hole shrinks. */
	fun temperature(m: Double): Double = hbar / (8.0 * PI * m)

	/** Mass after evaporating for geometric time [dt]; exact, from M³ decreasing linearly. 0 once gone. */
	fun evaporate(m: Double, dt: Double): Double {
		val cube = m * m * m - 3.0 * hbar / (15360.0 * PI) * dt
		return if (cube <= 0.0) 0.0 else Math.cbrt(cube)
	}
}
