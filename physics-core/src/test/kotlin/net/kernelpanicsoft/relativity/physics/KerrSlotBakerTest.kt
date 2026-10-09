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
}
