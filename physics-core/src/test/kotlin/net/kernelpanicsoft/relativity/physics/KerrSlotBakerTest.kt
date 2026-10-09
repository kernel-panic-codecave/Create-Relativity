package net.kernelpanicsoft.relativity.physics

import net.kernelpanicsoft.relativity.physics.lens.KerrSlotBaker
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The CPU slot baker (KerrSlotBaker): each image it stores, followed back from the camera along its direction with fine
 * steps, must pass through its point (within a pixel's angle at the point's distance), and it must find images for
 * nearly every point in front of the hole.
 */
class KerrSlotBakerTest {
	private val m = 2.0
	private val a = 0.9 * m

	@Test
	fun imagesPassThroughTheirPoints() {
		for ((rc, theta) in listOf(10.0 to 1.2, 4.0 to 0.6, 25.0 to 1.55)) {
			val rs = 2.0 * m
			val camera = doubleArrayOf(rc * rs * sin(theta), rc * rs * cos(theta), 0.0)
			val inner = 1.05 * (m + sqrt(m * m - a * a))
			val outer = 80.0 * rs
			val baker = KerrSlotBaker(m, a, camera, inner, outer)
			val start = System.nanoTime()
			val map = baker.bake()
			val ms = (System.nanoTime() - start) / 1e6
			val width = baker.width
			val height = baker.height
			val depth = baker.depth
			var filled = 0
			for (t in 0 until width * height * depth) if (map[t * 4 + 3] > 0.5f) filled += 1
			// Check a sample against a fine forward trace.
			val random = Random(1)
			val errors = ArrayList<Double>()
			var checked = 0
			while (checked < 400) {
				val i = random.nextInt(width)
				val j = random.nextInt(height)
				val k = random.nextInt(depth)
				val t = (k * height + j) * width + i
				if (map[t * 4 + 3] < 0.5f) continue
				checked += 1
				val radius = inner * (outer / inner).pow(k / (depth - 1.0))
				val azimuth = ((i + 0.5) / width - 0.5) * 2.0 * PI
				val cosine = j / (height - 1.0) * 2.0 - 1.0
				val sine = sqrt(max(1.0 - cosine * cosine, 0.0))
				val point = doubleArrayOf(radius * sine * cos(azimuth), radius * cosine, radius * sine * sin(azimuth))
				val game = DoubleArray(3) { point[it] - camera[it] }
				val span = sqrt(game[0] * game[0] + game[1] * game[1] + game[2] * game[2])
				val seen = DoubleArray(3) { game[it] / span + map[t * 4 + it] * span }
				val n = sqrt(seen[0] * seen[0] + seen[1] * seen[1] + seen[2] * seen[2])
				for (c in 0..2) seen[c] /= n
				val miss = baker.missDistance(seen, point)
				errors += miss / span
			}
			errors.sort()
			val pixel = 2.0 * Math.tan(Math.toRadians(35.0)) / 1504.0
			val median = errors[errors.size / 2] / pixel
			val p95 = errors[errors.size * 95 / 100] / pixel
			val over3 = errors.count { it > 3 * pixel } * 100.0 / errors.size
			println("  phases: fan %.0f ms, raster %.0f ms, refine %.0f ms (%d texels)".format(baker.timings[0], baker.timings[1], baker.timings[2], baker.refined.get()))
			println("Slot r=%.0f rs theta=%.2f: bake %.0f ms, %.1f%% texels with an image; error median %.2f px, 95th %.2f px, over 3 px %.1f%%"
				.format(rc, theta, ms, filled * 100.0 / (width * height * depth), median, p95, over3))
			assertTrue(over3 < 1.0, "too many misplaced images")
			assertTrue(filled * 100.0 / (width * height * depth) > 85.0, "too few images")
		}
	}

	/** The secondary map's images pass through their points too; and round a hole without spin they're where the
	 * Schwarzschild secondary table (SchwarzschildImages, built independently) puts them. */
	@Test
	fun secondaryImages() {
		for (spin in listOf(0.0, 0.9)) {
			val m = 2.0
			val a = spin * m
			val rs = 2.0 * m
			val rc = 8.0
			val theta = 1.2
			val camera = doubleArrayOf(rc * rs * sin(theta), rc * rs * cos(theta), 0.0)
			val inner = 1.05 * (m + sqrt(m * m - a * a))
			val outer = 80.0 * rs
			val baker = KerrSlotBaker(m, a, camera, inner, outer)
			val start = System.nanoTime()
			val second = baker.bakeBoth()[1]
			val ms = (System.nanoTime() - start) / 1e6
			val (width, height, depth) = Triple(baker.width, baker.height, baker.depth)
			var filled = 0
			for (t in 0 until width * height * depth) if (second[t * 4 + 3] > 0.5f) filled += 1
			val table = if (spin == 0.0) net.kernelpanicsoft.relativity.physics.lens.SchwarzschildImages.buildSecondaryTable(64, 64, 128) else null
			val random = Random(3)
			val misses = ArrayList<Double>()
			val againstTable = ArrayList<Double>()
			var checked = 0
			var tries = 0
			while (checked < 600 && tries < 2_000_000) {
				tries += 1
				val i = random.nextInt(width)
				val j = random.nextInt(height)
				val k = random.nextInt(depth)
				val t = (k * height + j) * width + i
				if (second[t * 4 + 3] < 0.5f) continue
				// Within 24 r_s (what the vertex lens draws).
				val radius = inner * (outer / inner).pow(k / (depth - 1.0))
				if (radius > 24 * rs) continue
				checked += 1
				val azimuth = ((i + 0.5) / width - 0.5) * 2.0 * PI
				val cosine = j / (height - 1.0) * 2.0 - 1.0
				val sine = sqrt(max(1.0 - cosine * cosine, 0.0))
				val point = doubleArrayOf(radius * sine * cos(azimuth), radius * cosine, radius * sine * sin(azimuth))
				val game = DoubleArray(3) { point[it] - camera[it] }
				val span = sqrt(game[0] * game[0] + game[1] * game[1] + game[2] * game[2])
				val seen = DoubleArray(3) { game[it] / span + second[t * 4 + it] * span }
				val n = sqrt(seen[0] * seen[0] + seen[1] * seen[1] + seen[2] * seen[2])
				for (c in 0..2) seen[c] /= n
				misses += baker.missDistance(seen, point) / span
				if (table != null) {
					// The shader's construction (vertex_lens_core.glsl's secondary branch), in the map frame.
					val rcB = sqrt(camera[0] * camera[0] + camera[1] * camera[1] + camera[2] * camera[2])
					val e1 = DoubleArray(3) { camera[it] / rcB }
					val rp = radius
					val radial = DoubleArray(3) { point[it] / rp }
					val cosPhi = (radial[0] * e1[0] + radial[1] * e1[1] + radial[2] * e1[2]).coerceIn(-1.0, 1.0)
					val side = DoubleArray(3) { radial[it] - cosPhi * e1[it] }
					val sl = sqrt(side[0] * side[0] + side[1] * side[1] + side[2] * side[2])
					if (sl < 1e-6) continue
					val e2 = DoubleArray(3) { side[it] / sl }
					val u0 = rs / rcB
					val launch = net.kernelpanicsoft.relativity.physics.lens.SchwarzschildImages.sampleSecondary(table, 64, 64, 128, u0, rs / rp, kotlin.math.acos(cosPhi))
					if (launch.isNaN()) continue
					val psi = kotlin.math.atan2(sin(launch), cos(launch) * sqrt(1.0 - u0))
					val expected = DoubleArray(3) { -cos(psi) * e1[it] - sin(psi) * e2[it] }
					againstTable += kotlin.math.acos((expected[0] * seen[0] + expected[1] * seen[1] + expected[2] * seen[2]).coerceIn(-1.0, 1.0))
				}
			}
			val pixel = 2.0 * Math.tan(Math.toRadians(35.0)) / 1504.0
			misses.sort()
			againstTable.sort()
			println("  fan %.0f ms; primary %s; secondary %s".format(baker.timings[0], baker.kindTimings[0].joinToString("/") { "%.0f".format(it) }, baker.kindTimings[1].joinToString("/") { "%.0f".format(it) }))
			println("Secondary, spin %.1f: bake %.0f ms, %.1f%% texels with one; miss median %.2f px, 95th %.2f px, worst %.2f px%s".format(
				spin, ms, filled * 100.0 / (width * height * depth), misses[misses.size / 2] / pixel, misses[misses.size * 95 / 100] / pixel, misses.last() / pixel,
				if (againstTable.isEmpty()) "" else "; against the Schwarzschild table: median %.2f px, 95th %.2f px".format(againstTable[againstTable.size / 2] / pixel, againstTable[againstTable.size * 95 / 100] / pixel)))
			// (Misses at the point: on screen a secondary image's are smaller by its squeeze, so only the median's checked.)
			assertTrue(misses[misses.size / 2] < 1.0 * pixel, "secondary images off by a median %.1f px at their points".format(misses[misses.size / 2] / pixel))
			if (againstTable.isNotEmpty()) assertTrue(againstTable[againstTable.size * 95 / 100] < 2.0 * pixel, "secondary images disagree with the Schwarzschild table")
		}
	}
}
