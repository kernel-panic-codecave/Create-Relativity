package net.kernelpanicsoft.relativity.physics

import net.kernelpanicsoft.relativity.physics.lens.KerrSlotBaker
import kotlin.math.*
import kotlin.test.Test

class TmpCoverageTest {
	@Test
	fun probe() {
		val m = 1.0; val a = 0.9; val rs = 2.0
		val cam = doubleArrayOf(7.5 * rs, 0.8, 0.0)
		val inner = 1.05 * (m + sqrt(m * m - a * a))
		val outer = 112.0
		val baker = KerrSlotBaker(m, a, cam, inner, outer)
		val map = baker.bake()
		val (w, h, d) = Triple(baker.width, baker.height, baker.depth)
		// Coverage of the camera's side (azimuth within 45 degrees of it) by shell and polar band (cos from -1 to 1).
		println("cos bands: -1 .. 1 in 8")
		for (k in 0 until d step 3) {
			val r = inner * (outer / inner).pow(k / (d - 1.0))
			if (r > 24 * rs) break
			val sb = StringBuilder("r %5.1f rs: ".format(r / rs))
			for (band in 0 until 8) {
				var have = 0; var all = 0
				for (i in 0 until w) {
					val az = ((i + 0.5) / w - 0.5) * 2 * PI
					if (abs(az) > PI / 4) continue
					for (j in band * h / 8 until (band + 1) * h / 8) { all++; if (map[((k * h + j) * w + i) * 4 + 3] > 0.5f) have++ }
				}
				sb.append("%3d%% ".format(100 * have / max(all, 1)))
			}
			println(sb)
		}
	}
}
