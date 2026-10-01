package net.kernelpanicsoft.relativity.physics

import net.kernelpanicsoft.relativity.physics.accretion.Eddington
import net.kernelpanicsoft.relativity.physics.accretion.Hawking
import net.kernelpanicsoft.relativity.physics.accretion.SpinUp
import net.kernelpanicsoft.relativity.physics.accretion.ThinDisk
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class AccretionTest {
	@Test
	fun `ISCO orbit constants and efficiency match known values`() {
		val schwarzschild = ThinDisk(0.0)
		assertClose(sqrt(8.0 / 9.0), schwarzschild.energy(6.0), 1e-12, "E_isco")
		assertClose(2 * sqrt(3.0), schwarzschild.angularMomentum(6.0), 1e-12, "L_isco")
		assertClose(1 - sqrt(8.0 / 9.0), schwarzschild.efficiency, 1e-12, "η = 5.7%")
		assertClose(0.321, ThinDisk(0.998).efficiency, 0.01, "Thorne-limit efficiency")
	}

	@Test
	fun `feeding from the ISCO follows Bardeen's spin-up curve`() {
		var m = 1.0
		var chi = 0.0
		while (chi < 0.999) {
			val (nm, nc) = SpinUp.accreteFromIsco(m, chi, 1e-4 * m)
			m = nm
			chi = nc
		}
		// Bardeen (1970): χ = √(2/3) y (4 − √(18y² − 2)) with y = M₀/M, reaching 1 at M = √6 M₀; at χ = 0.999
		// that's M = 2.2533 M₀.
		assertClose(2.253254, m, 1e-3, "Bardeen 1970")
		val y = 1 / sqrt(6.0)
		assertClose(1.0, sqrt(2.0 / 3.0) * y * (4 - sqrt(18 * y * y - 2)), 1e-12, "maximal spin at √6")
	}

	@Test
	fun `disk radiates exactly the binding energy`() {
		val disk = ThinDisk(0.5)
		// Energy reaching infinity from both faces: L∞ = ∫ 2 · F · E · 2π r dr (√−g = r on the equator; each
		// emitter's specific energy E redshifts what it sends out). It must equal η per unit accretion rate.
		var luminosity = 0.0
		var r = disk.innerRadius
		val end = 4000.0
		while (r < end) {
			val dr = 0.002 * r
			val mid = r + dr / 2
			luminosity += 2 * disk.flux(mid, steps = 400) * disk.energy(mid) * 2 * PI * mid * dr
			r += dr
		}
		luminosity += 1.5 / end // Newtonian tail beyond r_end: 4π ∫ 3/(8π r²) dr
		assertClose(disk.efficiency, luminosity, 0.01, "luminosity vs η")
	}

	@Test
	fun `far out the disk is Newtonian`() {
		// Relativity shifts the inner-edge correction term (~1/√r), so compare where that's negligible.
		val disk = ThinDisk(0.0)
		val r = 1e6
		val newtonian = 3.0 / (8 * PI * r * r * r) * (1 - sqrt(disk.innerRadius / r))
		assertClose(newtonian, disk.flux(r), 0.005, "Shakura–Sunyaev limit")
		assertTrue(disk.flux(disk.innerRadius) == 0.0)
	}

	@Test
	fun `Hawking evaporation integrates to the lifetime`() {
		val hawking = Hawking(1.0 / 64)
		val m0 = 0.05
		val t = hawking.lifetime(m0)
		assertTrue(hawking.evaporate(m0, t * 0.999) > 0.0)
		assertTrue(hawking.evaporate(m0, t * 1.001) == 0.0)
		// Stepping with dM/dt reproduces the closed form well before the end.
		var m = m0
		val dt = t / 200000
		repeat(100000) { m -= hawking.massLossRate(m) * dt }
		assertClose(hawking.evaporate(m0, t / 2), m, 1e-4, "half-life mass")
		assertTrue(hawking.temperature(0.01) > hawking.temperature(0.1), "smaller holes are hotter")
	}
}

class EddingtonTest {
	@Test
	fun `at the Eddington rate loose matter is in balance`() {
		val lambda = Eddington.ratio(accretionRate = 8.0, eddingtonRate = 8.0)
		assertClose(1.0, Eddington.pushOverGravity(lambda, coupling = 1.0), 1e-12)
		assertTrue(Eddington.pushOverGravity(Eddington.ratio(16.0, 8.0), 1.0) > 1.0, "overfed: blown away")
		assertTrue(Eddington.pushOverGravity(lambda, coupling = 0.2) < 1.0, "dense things still fall in")
	}
}
