package net.kernelpanicsoft.relativity.physics

import net.kernelpanicsoft.relativity.physics.geodesic.HamiltonianRay
import net.kernelpanicsoft.relativity.physics.lens.CellLightMap
import net.kernelpanicsoft.relativity.physics.spacetime.field.ComposedSpacetime
import net.kernelpanicsoft.relativity.physics.spacetime.field.KerrSource
import kotlin.math.acos
import kotlin.math.sqrt
import kotlin.test.Test

class CellLightMapTest {
	/**
	 * Measurement, not a check (off by default: ~40 s; run it with -Dcell.maps=1). How well a cell's map reproduces exact crossings, by distance from a spinning hole (r_s 2) and resolution: the
	 * 99th-percentile and worst exit direction error (radians) and exit position error (blocks) over random entries.
	 */
	@Test
	@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "cell.maps", matches = "1")
	fun `accuracy by distance and resolution`() {
		val random = java.util.Random(3)
		for (distance in listOf(-1.0, 6.0, 12.0, 24.0, 48.0)) for ((positions, directions) in listOf(5 to 9, 9 to 17)) {
			// (-1: flat spacetime, straight lines.)
			val spacetime = ComposedSpacetime(if (distance < 0) emptyList() else listOf(KerrSource(0.0, 0.0, 0.0, 1.0, 0.9)))
			val start = System.nanoTime()
			// A 16-block cell whose nearest face is `distance` from the hole, beside it.
			val map = CellLightMap(spacetime, maxOf(distance, 0.0), -8.0, -8.0, 16.0, positions, directions)
			val built = (System.nanoTime() - start) / 1e9
			val ray = HamiltonianRay(spacetime, rtol = 1e-9, atol = 1e-11)
			val dirErrors = ArrayList<Double>()
			val bents = ArrayList<Double>()
			val posErrors = ArrayList<Double>()
			var unknown = 0
			repeat(1200) {
				val face = random.nextInt(6)
				val u = random.nextDouble()
				val v = random.nextDouble()
				val dir = map.faceDirection(face, random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1)
				val exact = map.trace(ray, map.facePoint(face, u, v), dir)
				val mapped = map.cross(face, u, v, dir)
				if (exact == null || mapped == null) { unknown += 1; return@repeat }
				val entry = map.facePoint(face, u, v)
				val length = sqrt((0..2).sumOf { (exact[it] - entry[it]).let { d -> d * d } })
				val dirError = acos((exact[3] * mapped[3] + exact[4] * mapped[4] + exact[5] * mapped[5]).coerceIn(-1.0, 1.0))
				val bent = acos((exact[3] * dir[0] + exact[4] * dir[1] + exact[5] * dir[2]).coerceIn(-1.0, 1.0))
				if (length < 12.0) return@repeat
				bents += bent
				dirErrors += dirError
				posErrors += sqrt((0..2).sumOf { (exact[it] - mapped[it]).let { d -> d * d } })
			}
			dirErrors.sort()
			bents.sort()
			println("  long crossings: median bend %.5f, median error %.5f".format(bents[bents.size / 2], dirErrors[dirErrors.size / 2]))
			posErrors.sort()
			fun p99(list: List<Double>) = list[(list.size * 0.99).toInt().coerceAtMost(list.size - 1)]
			println("cell at %4.0f: %dx%d positions, %dx%d directions (%.0f kB): direction 99%% %.5f max %.5f rad; position 99%% %.4f max %.4f blocks; %d unknown; built in %.1f s"
				.format(distance, positions, positions, directions, directions, 6.0 * positions * positions * directions * directions * 6 * 2 / 1024,
					p99(dirErrors), dirErrors.last(), p99(posErrors), posErrors.last(), unknown, built))
		}
	}
}
