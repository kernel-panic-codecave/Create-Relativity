package net.kernelpanicsoft.relativity.physics

import net.kernelpanicsoft.relativity.physics.geodesic.Dopri5
import net.kernelpanicsoft.relativity.physics.spacetime.Tidal
import net.kernelpanicsoft.relativity.physics.special.SpecialRelativity
import net.kernelpanicsoft.relativity.physics.weakfield.PostNewtonian
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class WeakFieldAndKinematicsTest {
	@Test
	fun `1PN fast path reproduces the perihelion advance`() {
		val m = 1.0
		val p = 400.0
		val e = 0.2
		val rp = p / (1 + e)
		val y = doubleArrayOf(rp, 0.0, 0.0, 0.0, sqrt(m * (1 + e) / rp), 0.0)
		val acc = DoubleArray(3)
		val ode = Dopri5(6, rtol = 1e-12, atol = 1e-14) { s, d ->
			PostNewtonian.acceleration(m, doubleArrayOf(s[0], s[1], s[2]), doubleArrayOf(s[3], s[4], s[5]), acc)
			d[0] = s[3]; d[1] = s[4]; d[2] = s[5]
			d[3] = acc[0]; d[4] = acc[1]; d[5] = acc[2]
		}
		val peri = mutableListOf<Double>()
		var lastR = rp
		var falling = false
		var unwrapped = 0.0
		var lastAngle = 0.0
		ode.integrate(y, 1e7, stepLimit = { 0.02 * sqrt(it[0] * it[0] + it[1] * it[1]) }) {
			val r = sqrt(it[0] * it[0] + it[1] * it[1])
			val angle = atan2(it[1], it[0])
			var d = angle - lastAngle
			if (d < -PI) d += 2 * PI
			if (d > PI) d -= 2 * PI
			unwrapped += d
			lastAngle = angle
			if (falling && r > lastR) peri += unwrapped
			falling = r < lastR
			lastR = r
			peri.size < 3
		}
		assertClose(6 * PI * m / p, (peri[2] - peri[0]) / 2 - 2 * PI, 0.02, "1PN precession")
	}

	@Test
	fun `tidal tensor stretches radially, squeezes sideways, and is traceless`() {
		val t = DoubleArray(9)
		Tidal.schwarzschild(1.0, doubleArrayOf(10.0, 0.0, 0.0), t)
		assertClose(2e-3, t[0], 1e-12, "radial")
		assertClose(-1e-3, t[4], 1e-12, "transverse")
		assertTrue(abs(t[0] + t[4] + t[8]) < 1e-15)
	}

	@Test
	fun `velocity addition never reaches c`() {
		val w = SpecialRelativity.add(doubleArrayOf(0.9, 0.0, 0.0), doubleArrayOf(0.9, 0.0, 0.0))
		assertClose(1.8 / 1.81, w[0], 1e-12, "collinear")
		val perp = SpecialRelativity.add(doubleArrayOf(0.8, 0.0, 0.0), doubleArrayOf(0.0, 0.99, 0.0))
		val speed = sqrt(perp.sumOf { it * it })
		assertTrue(speed < 1.0, "perpendicular addition stays subluminal, got $speed")
		assertClose(0.99 * 0.6, perp[1], 1e-12, "perpendicular component is divided by γ")
	}

	@Test
	fun `aberration and Doppler match closed forms`() {
		assertClose(0.5, SpecialRelativity.aberrate(0.0, 0.5), 1e-12)
		val d = SpecialRelativity.doppler(doubleArrayOf(0.6, 0.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0))
		assertClose(sqrt(1.6 / 0.4), d, 1e-12, "approaching source is blueshifted by √((1+β)/(1−β))")
	}

	@Test
	fun `twin paradox`() {
		val tripTime = 10.0
		val beta = 0.8
		// Out and back at constant speed; proper time accumulates as dt/γ.
		val travellerAge = tripTime / SpecialRelativity.gamma(beta)
		assertClose(6.0, travellerAge, 1e-12)
		assertClose(2 * SpecialRelativity.rapidity(0.5), SpecialRelativity.rapidity(SpecialRelativity.add(
			doubleArrayOf(0.5, 0.0, 0.0), doubleArrayOf(0.5, 0.0, 0.0))[0]), 1e-12, "rapidities add")
	}
}
