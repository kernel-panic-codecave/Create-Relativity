package net.kernelpanicsoft.relativity.physics

import net.kernelpanicsoft.relativity.physics.geodesic.Geodesic
import net.kernelpanicsoft.relativity.physics.geodesic.NullRay
import net.kernelpanicsoft.relativity.physics.spacetime.Kerr
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * KerrRay, the closed-form Kerr light path (and, through it, the exact lens): its path against NullRay's, its Jacobi
 * amplitudes against the elliptic integral, and images solved with it (Newton from the vertex lens's approximation)
 * against KerrSlotBaker's. (Originally: pinning conventions for a closed-form Kerr lens. The light a camera (Kerr-Schild Cartesian) sees along d: its
 * constants of motion (lambda = L/E, eta = Q/E^2) from the arriving photon's momentum, then the path followed back in
 * Boyer-Lindquist form (Mino time, second-order r and theta, phi from its first-order equation) to a radius, against
 * NullRay's crossing of that radius. Four sign conventions (Cartesian embedding, KS phi offset) tried.
 */
class KerrRayTest {
	private fun out(line: String) = println(line)

	/** Schwarzschild primary image (r_s = 1 units) from the forFiltering table, as the vertex lens computes it. */
	private fun schwarzschildSeen(table: FloatArray, h: DoubleArray, p: DoubleArray): DoubleArray? {
		fun norm(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
		val straight = DoubleArray(3) { p[it] / norm(p) }
		val rc = norm(h); val fromHole = DoubleArray(3) { p[it] - h[it] }; val rp = norm(fromHole)
		if (rc <= 1.0 || rp <= 1.0) return straight
		val u0 = kotlin.math.max(1.0 / rc, 1e-4); val uP = kotlin.math.max(1.0 / rp, 1e-4)
		if (u0 < 2e-3 && uP < 2e-3) return straight
		val e1 = DoubleArray(3) { -h[it] / rc }
		val radial = DoubleArray(3) { fromHole[it] / rp }
		val cosPhi = (radial[0] * e1[0] + radial[1] * e1[1] + radial[2] * e1[2]).coerceIn(-1.0, 1.0)
		val side = DoubleArray(3) { radial[it] - cosPhi * e1[it] }
		val sl = kotlin.math.max(norm(side), 1e-6); val e2 = DoubleArray(3) { side[it] / sl }
		val phi = kotlin.math.acos(cosPhi)
		val (n0, nP, nPhi) = Triple(64, 64, 128)
		val fx = (sqrt(u0) * (n0 - 1)).coerceIn(0.0, n0 - 1.0); val fy = (sqrt(uP) * (nP - 1)).coerceIn(0.0, nP - 1.0); val fz = (phi / Math.PI * (nPhi - 1)).coerceIn(0.0, nPhi - 1.0)
		val x0 = kotlin.math.min(kotlin.math.floor(fx).toInt(), n0 - 2); val y0 = kotlin.math.min(kotlin.math.floor(fy).toInt(), nP - 2); val z0 = kotlin.math.min(kotlin.math.floor(fz).toInt(), nPhi - 2)
		var corr = 0.0; var valid = 0.0
		for (dz in 0..1) for (dy in 0..1) for (dx in 0..1) {
			val w = (if (dx == 0) 1 - (fx - x0) else fx - x0) * (if (dy == 0) 1 - (fy - y0) else fy - y0) * (if (dz == 0) 1 - (fz - z0) else fz - z0)
			val at = ((z0 + dz) * nP + (y0 + dy)) * n0 + (x0 + dx)
			corr += w * table[2 * at]; valid += w * table[2 * at + 1]
		}
		if (valid < 0.5) return null
		val local = atan2(rp * sin(phi), rc - rp * cos(phi)) + corr
		val psi = atan2(sin(local), cos(local) * sqrt(1.0 - u0))
		return DoubleArray(3) { -cos(psi) * e1[it] + sin(psi) * e2[it] }
	}

	@Test
	fun inverse() {
		net.kernelpanicsoft.relativity.physics.lens.KerrSlotBaker.threads = kotlin.math.max(2, Runtime.getRuntime().availableProcessors() - 4)
		val table = net.kernelpanicsoft.relativity.physics.lens.SchwarzschildImages.let { it.forFiltering(it.buildTable(64, 64, 128), 64, 64, 128) }
		val m = 1.0; val a = 0.9; val rs = 2.0
		fun toPhys(g: DoubleArray) = doubleArrayOf(g[2], g[0], g[1])
		fun toGame(p: DoubleArray) = doubleArrayOf(p[1], p[2], p[0])
		fun norm(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
		fun dot(u: DoubleArray, v: DoubleArray) = u[0] * v[0] + u[1] * v[1] + u[2] * v[2]
		fun cross(u: DoubleArray, v: DoubleArray) = doubleArrayOf(u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
		for ((name, camGame) in listOf("side" to doubleArrayOf(-15.0, 1.62, 0.0), "above" to doubleArrayOf(-10.0, 9.62, 0.0))) {
			val camPhys = toPhys(camGame)
			val rc = norm(camGame)
			val eGame = DoubleArray(3) { -camGame[it] / rc }
			val inner = 1.05 * (m + sqrt(m * m - a * a))
			val baker = net.kernelpanicsoft.relativity.physics.lens.KerrSlotBaker(m, a, camGame, inner, 80.0 * rs)
			val map = baker.bake()
			val (w, h, d) = Triple(baker.width, baker.height, baker.depth)
			val u0 = rs / rc
			val crit = kotlin.math.asin(kotlin.math.min(2.59807621 * u0 * sqrt(1 - u0), 1.0)); val alphaC = atan2(sin(crit), cos(crit) * sqrt(1 - u0))
			val bins = listOf(1.0 to 1.1, 1.1 to 1.3, 1.3 to 1.6, 1.6 to 2.5, 2.5 to 100.0)
			val approxErr = bins.map { ArrayList<Double>() }; val exactErr = bins.map { ArrayList<Double>() }
			val iterations = ArrayList<Int>(); var failed = 0
			val failures = java.util.TreeMap<String, Int>()
			val shift = DoubleArray(3) { -a * cross(doubleArrayOf(0.0, 1.0, 0.0), eGame)[it] }
			for (k in 0 until d step 2) {
				val radius = inner * (80.0 * rs / inner).pow(k / (d - 1.0))
				if (radius > 24 * rs) continue
				for (j in 0 until h step 3) for (i in 0 until w step 3) {
					val t = ((k * h + j) * w + i) * 4
					if (map[t + 3] < 0.5f) continue
					val az = ((i + 0.5) / w - 0.5) * 2 * Math.PI; val c = j / (h - 1.0) * 2 - 1; val sn = sqrt(kotlin.math.max(1 - c * c, 0.0))
					val pointGame = doubleArrayOf(radius * sn * cos(az), radius * c, radius * sn * sin(az))
					val toPoint = DoubleArray(3) { pointGame[it] - camGame[it] }
					val span = norm(toPoint)
					val exRaw = DoubleArray(3) { toPoint[it] / span + map[t + it] * span }; val en = norm(exRaw); val exact = DoubleArray(3) { exRaw[it] / en }
					val alpha = kotlin.math.acos(dot(exact, eGame).coerceIn(-1.0, 1.0))
					val bin = bins.indexOfFirst { alpha / alphaC >= it.first && alpha / alphaC < it.second }
					if (bin < 0) continue
					// The approximation (displaced lens, twist), as the shader does it.
					var seed = schwarzschildSeen(table, DoubleArray(3) { (-camGame[it] + shift[it]) / rs }, DoubleArray(3) { toPoint[it] / rs }) ?: continue
					val untwisted = seed.copyOf()
					val plain = schwarzschildSeen(table, DoubleArray(3) { -camGame[it] / rs }, DoubleArray(3) { toPoint[it] / rs }) ?: seed
					val sa = kotlin.math.acos(dot(seed, eGame).coerceIn(-1.0, 1.0))
					val tau = -0.274 * a * (-eGame[1]) * (alphaC / kotlin.math.max(sa, 0.8 * alphaC)).pow(2.5)
					val cr = cross(eGame, seed); val ed = dot(eGame, seed)
					seed = DoubleArray(3) { seed[it] * cos(tau) + cr[it] * sin(tau) + eGame[it] * ed * (1 - cos(tau)) }
					val doubled = DoubleArray(3) { untwisted[it] * cos(2 * tau) + cr[it] * sin(2 * tau) + eGame[it] * ed * (1 - cos(2 * tau)) }
					approxErr[bin] += kotlin.math.acos(dot(seed, exact).coerceIn(-1.0, 1.0)) / 9.3e-4
					// Gauss-Newton on the closed form, both legs, from the approximation.
					val pointPhys = toPhys(pointGame)
					val rTarget = Kerr(m, a).radius(pointPhys[0], pointPhys[1], pointPhys[2])
					var dir = toPhys(seed)
					var best: DoubleArray? = null
					var iters = 0
					var lastMiss = Double.NaN
					var why = "no ray reaches the radius"
					val startRay = net.kernelpanicsoft.relativity.physics.lens.KerrRay(m, a, camPhys, dir)
					val firstLeg = listOf(false, true).minByOrNull { leg -> startRay.at(rTarget, leg)?.let { hit -> norm(DoubleArray(3) { k2 -> hit[k2] - pointPhys[k2] }) } ?: 1e30 }!!
					var vortical = false
					fun solve(outbound: Boolean, start: DoubleArray): DoubleArray? {
						var dir = start
						fun residual(dv: DoubleArray): DoubleArray? {
							val ray = net.kernelpanicsoft.relativity.physics.lens.KerrRay(m, a, camPhys, dv)
							if (ray.eta <= 0) vortical = true
							val hit = ray.at(rTarget, outbound) ?: ray.closest() ?: return null
							return DoubleArray(3) { hit[it] - pointPhys[it] }
						}
						var damping = 1e-3
						var r0v = residual(dir)
						for (iter in 0 until 16) {
							iters = iter
							val rv = r0v ?: return null
							lastMiss = norm(rv)
							why = "stalled"
							if (lastMiss < 1e-4 * kotlin.math.max(1.0, rTarget * 0.01)) return dir
							val e1 = cross(dir, if (abs(dir[2]) < 0.9) doubleArrayOf(0.0, 0.0, 1.0) else doubleArrayOf(1.0, 0.0, 0.0)).let { v -> DoubleArray(3) { v[it] / norm(v) } }
							val e2 = cross(dir, e1)
							val eps = 1e-6
							val ra = residual(DoubleArray(3) { dir[it] + eps * e1[it] }) ?: return null
							val rb = residual(DoubleArray(3) { dir[it] + eps * e2[it] }) ?: return null
							val ja = DoubleArray(3) { (ra[it] - rv[it]) / eps }; val jb = DoubleArray(3) { (rb[it] - rv[it]) / eps }
							val aa0 = dot(ja, ja); val ab = dot(ja, jb); val bb0 = dot(jb, jb); val ga = dot(ja, rv); val gb = dot(jb, rv)
							var accepted = false
							for (tries in 0 until 8) {
								val aa = aa0 * (1 + damping); val bb = bb0 * (1 + damping)
								val det = aa * bb - ab * ab
								if (abs(det) < 1e-300) break
								val da = -(bb * ga - ab * gb) / det; val db = -(aa * gb - ab * ga) / det
								val nd = DoubleArray(3) { dir[it] + da * e1[it] + db * e2[it] }; val nn = norm(nd)
								val trial = DoubleArray(3) { nd[it] / nn }
								val tr = residual(trial)
								if (tr != null && norm(tr) < lastMiss) { dir = trial; r0v = tr; damping = kotlin.math.max(damping / 4, 1e-6); accepted = true; break }
								damping *= 8
							}
							if (!accepted) return null
						}
						return null
					}
					best = solve(firstLeg, dir) ?: solve(!firstLeg, dir)
					// (Stalled: other starts, each leg.)
					for (alt in listOf(untwisted, plain, doubled)) {
						if (best != null) break
						val start = toPhys(alt).let { v -> val n = norm(v); DoubleArray(3) { v[it] / n } }
						best = solve(firstLeg, start) ?: solve(!firstLeg, start)
					}
					if (best == null && vortical) why = "vortical (eta <= 0) met"
					if (best == null) {
						// The closed form at the true direction: does it land on the point (a basin problem) or not (a bug)?
						val truth = net.kernelpanicsoft.relativity.physics.lens.KerrRay(m, a, camPhys, toPhys(exact))
						val atTruth = listOf(false, true).mapNotNull { truth.at(rTarget, it) }.minOfOrNull { hit -> norm(DoubleArray(3) { k2 -> hit[k2] - pointPhys[k2] }) }
						why += when {
							atTruth == null -> "; truth: no crossing (turning %.2f, eta %.2f)".format(truth.turning, truth.eta)
							atTruth < 0.01 -> "; truth lands (basin)"
							else -> "; truth misses %s".format(if (atTruth < 0.1) "<0.1" else if (atTruth < 1) "<1" else ">=1")
						}
					}
					if (best == null) {
						failed += 1
						val key = "band %.1f-%.1f, %s, miss %s, point r %s".format(bins[bin].first, bins[bin].second, why,
							if (lastMiss.isNaN()) "-" else if (lastMiss < 1e-2) "<0.01" else if (lastMiss < 0.1) "<0.1" else if (lastMiss < 1) "<1" else ">=1",
							if (rTarget < 3) "<3" else if (rTarget < 8) "3-8" else ">=8")
						failures.merge(key, 1, Int::plus)
						continue
					}
					iterations += iters
					exactErr[bin] += kotlin.math.acos(dot(toGame(best), exact).coerceIn(-1.0, 1.0)) / 9.3e-4
				}
			}
			out("$name: approximation vs exact closed-form inverse, error by band (median / 90th px):")
			bins.forEachIndexed { b, (lo, hi) ->
				fun q(l: List<Double>, f: Double) = l.sorted().let { if (it.isEmpty()) Double.NaN else it[(it.size * f).toInt().coerceAtMost(it.size - 1)] }
				out("    %.1f-%.1f shadow radii: approximation %.1f / %.1f px, closed form %.2f / %.2f px (%d)".format(lo, hi, q(approxErr[b], 0.5), q(approxErr[b], 0.9), q(exactErr[b], 0.5), q(exactErr[b], 0.9), exactErr[b].size))
			}
			iterations.sort()
			out("    Newton iterations median %d, 90th %d; not converged %d".format(iterations[iterations.size / 2], iterations[iterations.size * 9 / 10], failed))
			val ring = exactErr[0].sorted()
			assertTrue(ring[ring.size / 2] < 1.0, "images by the ring off by ${ring[ring.size / 2]} px")
			assertTrue(failed < 0.05 * (iterations.size + failed), "$failed of ${iterations.size + failed} images not found")
			for ((key, n) in failures.entries.sortedByDescending { it.value }.take(12)) out("      $n: $key")
		}
	}

	@Test
	fun amplitude() {
		for (m in listOf(-50.0, -5.0, -0.5, 0.0, 0.3, 0.9, 0.999)) {
			var worst = 0.0
			for (k in 0 until 400) {
				val x = -30.0 + 60.0 * k / 400
				val am = net.kernelpanicsoft.relativity.physics.lens.KerrRay.jacobiAm(x, m)
				worst = kotlin.math.max(worst, abs(net.kernelpanicsoft.relativity.physics.lens.KerrRay.ellipticF(am, m) - x))
			}
			out("jacobi am, m %.3f: worst |F(am x) - x| %.1e".format(m, worst))
			assertTrue(worst < 1e-9, "am(x | $m) off by $worst")
		}
	}

	@Test
	fun closedForm() {
		val m = 1.0
		for (a in listOf(0.9, 0.5, 0.01)) for (theta in listOf(1.1, 0.4)) {
			val kerr = Kerr(m, a)
			val rPlus = m + sqrt(m * m - a * a)
			val random = Random(7)
			val camera = doubleArrayOf(15.0 * sin(theta), 0.0, 15.0 * cos(theta))
			val toHole = DoubleArray(3) { -camera[it] / 15.0 }
			val misses = ArrayList<Double>()
			var none = 0; var traced = 0; var scattering = 0
			val closedVsQuad = ArrayList<Double>()
			while (traced < 60) {
				val d0 = DoubleArray(3) { toHole[it] + 0.5 * (random.nextDouble() * 2 - 1) }
				val dn = sqrt(d0.sumOf { it * it }); val d = DoubleArray(3) { d0[it] / dn }
				val rTarget = 2.5 + random.nextDouble() * 25.0
				val path = NullRay.trace(kerr, camera, d, 200.0, 1.02 * rPlus, maxSegment = 0.05, arriving = true)
				var exact: DoubleArray? = null
				var prev: DoubleArray? = null
				var crossedTurn = false
				var lastR = 15.0
				for (pt in path.points) {
					val q = prev; prev = pt
					if (q == null) continue
					val r0 = kerr.radius(q[0], q[1], q[2]); val r1 = kerr.radius(pt[0], pt[1], pt[2])
					if (r1 > r0 && lastR > r0) crossedTurn = true
					lastR = r0
					if ((r0 - rTarget) * (r1 - rTarget) <= 0 && r0 != r1) { val t = (rTarget - r0) / (r1 - r0); exact = DoubleArray(3) { q[it] + t * (pt[it] - q[it]) }; break }
				}
				if (exact == null) continue
				traced += 1
				val ray = net.kernelpanicsoft.relativity.physics.lens.KerrRay(m, a, camera, d)
				if (!ray.turning.isNaN()) scattering += 1
				val mine = ray.at(rTarget, crossedTurn || rTarget > 15.0)
				ray.radialQuadrature = true
				val quad = ray.at(rTarget, crossedTurn || rTarget > 15.0)
				ray.radialQuadrature = false
				if (mine != null && quad != null) closedVsQuad += sqrt((0..2).sumOf { (mine[it] - quad[it]).let { e -> e * e } })
				if (mine == null) { none += 1; continue }
				misses += sqrt((0..2).sumOf { (mine[it] - exact[it]).let { e -> e * e } })
			}
			misses.sort()
			assertTrue(misses[misses.size / 2] < 1e-4 && misses.last() < 2e-3 && none == 0, "closed form off the traced path")
			out("closed form, a %.2f, camera theta %.1f: %d rays (%d pass a turning point), miss median %.1e, 90th %.1e, max %.1e blocks; no answer %d; closed form vs quadrature max %.1e".format(a, theta, traced, scattering, misses[misses.size / 2], misses[misses.size * 9 / 10], misses.last(), none, closedVsQuad.maxOrNull() ?: 0.0))
		}
	}
}
