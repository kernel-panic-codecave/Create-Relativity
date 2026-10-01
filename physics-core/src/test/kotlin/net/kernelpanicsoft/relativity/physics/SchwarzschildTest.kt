package net.kernelpanicsoft.relativity.physics

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchwarzschildTest {
	private val units = SpacetimeUnits()

	@Test
	fun `mass and radius round trip`() {
		val m = units.massForRadius(2.0)
		assertEquals(2.0, units.schwarzschildRadius(m), 1e-12)
	}

	@Test
	fun `default G gives one block for ten thousand default blocks`() {
		assertEquals(1.0, units.schwarzschildRadius(1.0e4), 1e-9)
	}

	@Test
	fun `unit radius mass gives one block radius for any c`() {
		val u = SpacetimeUnits.withUnitRadiusMass(c = 20.0, unitRadiusMass = 500.0)
		assertEquals(1.0, u.schwarzschildRadius(500.0), 1e-12)
	}

	@Test
	fun `characteristic radii`() {
		val bh = Schwarzschild(2.0, units)
		assertEquals(3.0, bh.photonSphere, 1e-12)
		assertEquals(6.0, bh.isco, 1e-12)
		assertEquals(3.0 * sqrt(3.0), bh.shadowRadius, 1e-12)
	}

	@Test
	fun `redshift vanishes at horizon and tends to one far away`() {
		val bh = Schwarzschild(2.0, units)
		assertEquals(0.0, bh.staticRedshift(2.0))
		assertTrue(abs(1.0 - bh.staticRedshift(1e9)) < 1e-8)
	}

	@Test
	fun `hover acceleration exceeds Newton by r_s over 2r far away`() {
		val bh = Schwarzschild(2.0, units)
		val r = 1e6
		val newton = bh.gm / (r * r)
		assertEquals(bh.rs / (2.0 * r), bh.hoverAcceleration(r) / newton - 1.0, 1e-11)
	}
}
