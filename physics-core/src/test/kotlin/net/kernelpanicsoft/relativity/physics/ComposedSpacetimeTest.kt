package net.kernelpanicsoft.relativity.physics

import net.kernelpanicsoft.relativity.physics.geodesic.Geodesic
import net.kernelpanicsoft.relativity.physics.geodesic.HamiltonianRay
import net.kernelpanicsoft.relativity.physics.geodesic.NullRay
import net.kernelpanicsoft.relativity.physics.spacetime.Kerr
import net.kernelpanicsoft.relativity.physics.spacetime.field.ComposedSpacetime
import net.kernelpanicsoft.relativity.physics.spacetime.field.KerrSource
import net.kernelpanicsoft.relativity.physics.spacetime.field.MetricSource
import net.kernelpanicsoft.relativity.physics.spacetime.field.PointMass
import net.kernelpanicsoft.relativity.physics.spacetime.field.WarpEllipsoid
import net.kernelpanicsoft.relativity.physics.spacetime.field.SampledField
import net.kernelpanicsoft.relativity.physics.spacetime.field.FieldSample
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComposedSpacetimeTest {
	private val events = listOf(
		doubleArrayOf(0.0, 3.0, 1.0, -2.0),
		doubleArrayOf(0.0, -0.5, 2.5, 1.5),
		doubleArrayOf(0.0, 10.0, -7.0, 4.0),
		doubleArrayOf(0.0, 1.2, 0.3, 0.1), // inside the horizon (r₊ ≈ 1.44 for m 1, a 0.9)
	)

	@Test
	fun `one Kerr source is Kerr exactly, wherever it sits`() {
		val kerr = Kerr(1.0, 0.9)
		val offset = doubleArrayOf(5.0, -3.0, 2.0)
		val composed = ComposedSpacetime(listOf(KerrSource(offset[0], offset[1], offset[2], 1.0, 0.9)))
		val expected = DoubleArray(64)
		val actual = DoubleArray(64)
		for (event in events) {
			val shifted = doubleArrayOf(event[0], event[1] + offset[0], event[2] + offset[1], event[3] + offset[2])
			kerr.metric(event, expected)
			composed.metric(shifted, actual)
			for (i in 0..15) assertEquals(expected[i], actual[i], 1e-9, "g[$i] at ${event.contentToString()}")
			kerr.christoffel(event, expected)
			composed.christoffel(shifted, actual)
			for (i in 0..63) assertEquals(expected[i], actual[i], 1e-8 * (1 + abs(expected[i])), "Γ[$i] at ${event.contentToString()}")
		}
		assertTrue(composed.captures(doubleArrayOf(0.0, offset[0] + 1.2, offset[1] + 0.3, offset[2] + 0.1)))
		assertTrue(!composed.captures(doubleArrayOf(0.0, offset[0] + 3.0, offset[1], offset[2])))
	}

	@Test
	fun `light through the Hamiltonian follows the Christoffel geodesic round a spinning hole`() {
		val kerr = Kerr(1.0, 0.9)
		val composed = ComposedSpacetime(listOf(KerrSource(0.0, 0.0, 0.0, 1.0, 0.9)))
		val origin = doubleArrayOf(-30.0, 7.0, 1.5) // passes close, strongly bent but not captured
		val direction = doubleArrayOf(1.0, 0.0, 0.0)
		val path = NullRay.trace(kerr, origin, direction, maxLength = 60.0, captureRadius = 1.5, maxSegment = 0.05)
		val reference = path.points.last()
		// Follow the Hamiltonian ray until it has gone as far from the origin as the reference end point.
		val ray = HamiltonianRay(composed, rtol = 1e-10, atol = 1e-12)
		val state = HamiltonianRay.lightlike(composed, doubleArrayOf(0.0, origin[0], origin[1], origin[2]), direction)
		var length = 0.0
		var last = origin.copyOf()
		ray.advance(state, Double.MAX_VALUE, stepLimit = { 0.05 }) { s ->
			length += sqrt((s[1] - last[0]) * (s[1] - last[0]) + (s[2] - last[1]) * (s[2] - last[1]) + (s[3] - last[2]) * (s[3] - last[2]))
			last = doubleArrayOf(s[1], s[2], s[3])
			length < 60.0
		}
		assertEquals(NullRay.End.LENGTH, path.end, "the reference ray escapes")
		for (i in 0..2) assertEquals(reference[i], last[i], 0.05, "end point axis $i")
	}

	/** Deflection (radians, toward +y positive) of light sent along +x from far off at height [b] above the x axis. */
	private fun deflection(sources: List<MetricSource>, b: Double, distance: Double): Double {
		val composed = ComposedSpacetime(sources)
		val ray = HamiltonianRay(composed, rtol = 1e-10, atol = 1e-14)
		val state = HamiltonianRay.lightlike(composed, doubleArrayOf(0.0, -distance, b, 0.0), doubleArrayOf(1.0, 0.0, 0.0))
		ray.advance(state, Double.MAX_VALUE, stepLimit = { s -> maxOf(1.0, 0.05 * sqrt(s[1] * s[1] + s[2] * s[2])) }) { s -> s[1] < distance }
		val v = DoubleArray(3)
		ray.coordinateVelocity(state, v)
		return -atan2(v[1], v[0]) // toward the masses below (−y) counts positive
	}

	@Test
	fun `a weak-field mass bends light by 4m over b`() {
		val b = 1000.0
		assertClose(4.0 / b, deflection(listOf(PointMass(0.0, 0.0, 0.0, 1.0)), b, 1e6), 0.01, "deflection")
	}

	@Test
	fun `a negative mass bends light away`() {
		val b = 1000.0
		assertClose(-4.0 / b, deflection(listOf(PointMass(0.0, 0.0, 0.0, -1.0)), b, 1e6), 0.01, "deflection")
	}

	@Test
	fun `far-apart sources add their bending`() {
		val b = 1000.0
		val one = deflection(listOf(PointMass(0.0, 0.0, 0.0, 1.0)), b, 1e6)
		val two = deflection(listOf(PointMass(0.0, 0.0, 0.0, 1.0), PointMass(0.0, 2.0 * b, 0.0, -0.5)), b, 1e6)
		// The second, negative, mass sits as far above the path as the first is below: it pushes the light down too.
		assertClose(one * 1.5, two, 0.02, "two sources")
	}

	@Test
	fun `a warp bubble carries its centre along, faster than light, with no time dilation`() {
		val speed = 2.0
		val bubble = WarpEllipsoid(0.0, 0.0, 0.0, doubleArrayOf(10.0, 0.0, 0.0, 0.0, 10.0, 0.0, 0.0, 0.0, 10.0), doubleArrayOf(1.0, 0.0, 0.0), speed)
		val spacetime = ComposedSpacetime(listOf(bubble))
		val geodesic = Geodesic(spacetime, rtol = 1e-10, atol = 1e-12)
		val state = Geodesic.timelike(spacetime, doubleArrayOf(0.0, 0.0, 0.0, 0.0), doubleArrayOf(speed, 0.0, 0.0))
		assertClose(1.0, state[4], 1e-9, "dt/dτ at the centre")
		geodesic.advance(state, 50.0)
		assertClose(50.0, state[0], 1e-6, "coordinate time after 50 of proper time")
		assertClose(speed * 50.0, state[1], 1e-6, "rode along with the bubble")
		assertEquals(0.0, state[2], 1e-9)
	}

	@Test
	fun `inside a superluminal bubble light can't go backwards`() {
		val speed = 2.0
		val spacetime = ComposedSpacetime(listOf(WarpEllipsoid(0.0, 0.0, 0.0, doubleArrayOf(10.0, 0.0, 0.0, 0.0, 10.0, 0.0, 0.0, 0.0, 10.0), doubleArrayOf(1.0, 0.0, 0.0), speed)))
		val ray = HamiltonianRay(spacetime)
		val v = DoubleArray(3)
		ray.coordinateVelocity(HamiltonianRay.lightlike(spacetime, doubleArrayOf(0.0, 0.0, 0.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0)), v)
		assertClose(speed + 1.0, v[0], 1e-6, "forward")
		ray.coordinateVelocity(HamiltonianRay.lightlike(spacetime, doubleArrayOf(0.0, 0.0, 0.0, 0.0), doubleArrayOf(-1.0, 0.0, 0.0)), v)
		assertClose(speed - 1.0, v[0], 1e-6, "\"backward\" still moves forward")
	}

	@Test
	fun `traced as a polyline, it agrees with NullRay round a spinning hole, both ways in time`() {
		val kerr = Kerr(1.0, 0.9)
		val composed = ComposedSpacetime(listOf(KerrSource(0.0, 0.0, 0.0, 1.0, 0.9)))
		val origin = doubleArrayOf(-30.0, 7.0, 1.5)
		val direction = doubleArrayOf(1.0, 0.0, 0.0)
		for (arriving in listOf(false, true)) {
			val reference = NullRay.trace(kerr, origin, direction, 60.0, 1.5, maxSegment = 0.05, arriving = arriving).points.last()
			val path = HamiltonianRay(composed, rtol = 1e-10, atol = 1e-12).trace(origin, direction, 60.0, maxSegment = 0.05, arriving = arriving)
			assertEquals(NullRay.End.LENGTH, path.end)
			val end = path.points.last()
			for (i in 0..2) assertEquals(reference[i], end[i], 0.1, "arriving=$arriving axis $i")
		}
	}

	@Test
	fun `a sampled field matches its sources between the nodes`() {
		// Worst relative error over many points 2 to 7 blocks from a softened mass (core 1.5 blocks), at a quarter of its
		// width apart: the spacing the game picks for a source's own size.
		val mass = PointMass(0.3, -0.2, 0.1, 0.5, softening = 1.5)
		val grid = SampledField(-8.0, -8.0, -8.0, 0.375, 43, 43, 43, listOf(mass))
		val exact = FieldSample()
		val sampled = FieldSample()
		val random = java.util.Random(7)
		var worstValue = 0.0
		var worstGradient = 0.0
		repeat(400) {
			val point = DoubleArray(3) { random.nextDouble() * 14.0 - 7.0 }
			val r = sqrt(point.indices.sumOf { (point[it] - doubleArrayOf(0.3, -0.2, 0.1)[it]).let { d -> d * d } })
			if (r < 2.0) return@repeat
			exact.clear()
			sampled.clear()
			mass.contribute(0.0, point[0], point[1], point[2], exact)
			grid.contribute(0.0, point[0], point[1], point[2], sampled)
			val gExact = sqrt((1..3).sumOf { exact.d[it][0] * exact.d[it][0] })
			worstValue = maxOf(worstValue, abs(sampled.value[0] - exact.value[0]) / abs(exact.value[0]))
			worstGradient = maxOf(worstGradient, sqrt((1..3).sumOf { (sampled.d[it][0] - exact.d[it][0]).let { d -> d * d } }) / gExact)
		}
		println("sampled field: worst value error ${"%.4f".format(worstValue)}, worst gradient error ${"%.4f".format(worstGradient)}")
		assertTrue(worstValue < 0.01, "value error $worstValue")
		assertTrue(worstGradient < 0.02, "gradient error $worstGradient")
	}

	@Test
	fun `light through a sampled field bends as through its sources`() {
		// A mass whose near field is sampled on a grid round it, the far field analytic: the lattice region and the
		// rest of space split (inside, the grid; outside, the mass).
		val mass = PointMass(0.0, 0.0, 0.0, 1.0, softening = 4.0)
		val grid = SampledField(-32.0, -32.0, -32.0, 1.0, 65, 65, 65, listOf(mass))
		val outside = object : MetricSource {
			override fun contribute(t: Double, x: Double, y: Double, z: Double, out: FieldSample) {
				if (!grid.contains(x, y, z)) mass.contribute(t, x, y, z, out)
			}
		}
		val b = 10.0
		val analytic = deflection(listOf(mass), b, 1e5)
		val sampled = deflection(listOf(grid, outside), b, 1e5)
		assertClose(analytic, sampled, 0.01, "deflection through the sampled field")
	}
}
