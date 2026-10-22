package net.kernelpanicsoft.relativity.physics

import net.kernelpanicsoft.relativity.physics.geodesic.HamiltonianRay
import net.kernelpanicsoft.relativity.physics.lens.field.FieldLens
import net.kernelpanicsoft.relativity.physics.lens.field.LightLine
import net.kernelpanicsoft.relativity.physics.lens.field.TubeModule
import net.kernelpanicsoft.relativity.physics.lens.field.V3
import net.kernelpanicsoft.relativity.physics.spacetime.field.ComposedSpacetime
import net.kernelpanicsoft.relativity.physics.spacetime.field.FieldSample
import net.kernelpanicsoft.relativity.physics.spacetime.field.WarpTube
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The tube lens module ([TubeModule]: thin walls) against light followed exactly through its metric ([WarpTube] and
 * [net.kernelpanicsoft.relativity.physics.spacetime.field.KrasnikovTube], walls a tenth and a thirtieth of a block
 * thick): where light from cameras outside, beside and inside the tube ends up heading, through straight and bent
 * tubes, warp tubes slower and faster than light and Krasnikov tubes at 5 and 50 c.
 */
class TubeLensTest {
	/** A scene: a warp tube at [speed], or with [k] a Krasnikov tube (its cameras static observers, not riders). */
	private class Scene(val name: String, val spine: List<V3>, val speed: Double, val camera: V3, val look: V3, val cone: Double, val k: Double? = null) {
		fun module(radius: Double) = if (k != null) TubeModule(spine, TubeModule.Interior.Krasnikov(k), radius) else TubeModule(spine, speed, radius)

		fun metric(radius: Double): net.kernelpanicsoft.relativity.physics.spacetime.field.MetricSource {
			val flat = DoubleArray(spine.size * 3) { i -> spine[i / 3].let { if (i % 3 == 0) it.x else if (i % 3 == 1) it.y else it.z } }
			// (A Krasnikov wall refracts harder than a warp wall at these settings: thinner in the exact field, a thirtieth
			// of a block, to compare with the module's thin walls.)
			return if (k != null) net.kernelpanicsoft.relativity.physics.spacetime.field.KrasnikovTube(flat, k, radius, sigma = 30.0)
			else WarpTube(flat, speed, radius, sigma = 10.0)
		}
	}

	private val radius = 3.0
	private val straight = listOf(V3(-40.0, 0.0, 0.0), V3(40.0, 0.0, 0.0))
	private val bent = listOf(V3(-40.0, 0.0, 0.0), V3(0.0, 0.0, 0.0), V3(28.0, 0.0, 28.0))

	private val scenes = listOf(
		Scene("beside, slow", straight, 0.5, V3(-6.0, 8.0, 0.0), V3(0.3, -1.0, 0.0), 0.6),
		Scene("beside, near c", straight, 0.9, V3(-6.0, 8.0, 0.0), V3(0.3, -1.0, 0.0), 0.6),
		Scene("beside, warp 2", straight, 2.0, V3(-6.0, 8.0, 0.0), V3(0.3, -1.0, 0.0), 0.6),
		Scene("inside, slow", straight, 0.5, V3(0.0, 0.5, 0.0), V3(1.0, 0.2, 0.1), 1.2),
		Scene("inside, warp 2", straight, 2.0, V3(0.0, 0.5, 0.0), V3(-1.0, 0.2, 0.1), 1.2),
		Scene("bent, beside", bent, 0.7, V3(4.0, 8.0, -4.0), V3(0.0, -1.0, 0.3), 0.7),
		Scene("bent, inside", bent, 0.7, V3(-6.0, 0.3, 0.0), V3(1.0, 0.0, 0.2), 1.0),
		Scene("krasnikov 5c, beside", straight, 0.0, V3(-6.0, 8.0, 0.0), V3(0.3, -1.0, 0.0), 0.6, k = 0.2),
		Scene("krasnikov 5c, inside", straight, 0.0, V3(0.0, 0.5, 0.0), V3(1.0, 0.2, 0.1), 1.2, k = 0.2),
		Scene("krasnikov 50c, inside", straight, 0.0, V3(0.0, 0.5, 0.0), V3(-1.0, 0.3, 0.1), 1.2, k = 0.02),
		Scene("krasnikov, bent", bent, 0.0, V3(4.0, 8.0, -4.0), V3(0.0, -1.0, 0.3), 0.7, k = 0.2),
	)

	@Test
	fun againstExactLight() {
		val report = StringBuilder("Warp tube lens vs exact light (angle of the escape direction, degrees):\n")
		val failures = ArrayList<String>()
		for (scene in scenes) {
			val module = scene.module(radius)
			val spacetime = ComposedSpacetime(listOf(scene.metric(radius)))
			val random = java.util.Random(7)
			val errors = ArrayList<Double>()
			var captureMismatch = 0
			repeat(150) {
				val d = cone(scene.look.normalized(), scene.cone, random)
				val exact = trace(spacetime, scene.camera, d, static = scene.k != null)
					val lensed = module.pass(LightLine(scene.camera, d))
				if ((exact == null) != (lensed == null)) {
					captureMismatch++
					if (captureMismatch <= 2) report.append("    capture mismatch: d $d exact $exact lensed ${lensed?.direction}\n")
				} else if (exact != null && lensed != null) {
					val e = Math.toDegrees(acos((exact dot lensed.direction).coerceIn(-1.0, 1.0)))
					errors += e
					if (e > 5 && errors.count { it > 5 } <= 2) report.append("    off %.1f: d $d exact $exact lensed ${lensed.direction} from ${lensed.origin}\n".format(e))
				}
			}
			errors.sort()
			val median = errors.getOrElse(errors.size / 2) { 0.0 }
			val p90 = errors.getOrElse((errors.size * 0.9).toInt().coerceAtMost(errors.size - 1)) { 0.0 }
			val off = errors.count { it > 5.0 }.toDouble() / maxOf(errors.size, 1)
			report.append("  %-16s median %.3f, 90%% %.3f, max %.3f, past 5 degrees %.0f%%; capture mismatches %d of 150\n".format(scene.name, median, p90, errors.lastOrNull() ?: 0.0, off * 100, captureMismatch))
			// (The tail is the walls' thickness, thin here and a tenth of a block in the exact field: light grazing them (the
			// tube's silhouette), and light reflected inside off them near the critical angle, its turning point inside the
			// wall shifting where it meets the next.)
			if (median > 0.5 || off > 0.15 || captureMismatch > 8) failures += scene.name
		}
		println(report)
		assertTrue(failures.isEmpty(), "past their gates: $failures")
	}

	/**
	 * Light sent along [d] from [camera] (by the field's Eulerian observer, a rider: at rest for no field; or, [static],
	 * by a static observer, its frame the coordinate axes orthonormalised symmetrically): where it heads at last, or null
	 * if it never leaves.
	 */
	private fun trace(spacetime: ComposedSpacetime, camera: V3, d: V3, static: Boolean = false): V3? {
		val sample = FieldSample()
		val x = doubleArrayOf(0.0, camera.x, camera.y, camera.z)
		spacetime.inverse(x, sample)
		val g = DoubleArray(16)
		spacetime.metric(x, g)
		// Eulerian observer (lapse 1): n^μ = (1, −β), β^i = g^{ti}; light k = n + d.
		val k = if (static) staticLight(g, d) else doubleArrayOf(1.0, -sample.value[1] + d.x, -sample.value[2] + d.y, -sample.value[3] + d.z)
		val state = DoubleArray(8)
		for (mu in 0..3) {
			state[mu] = x[mu]
			state[4 + mu] = (0..3).sumOf { nu -> g[mu * 4 + nu] * k[nu] }
		}
		val ray = HamiltonianRay(spacetime, 1e-10, 1e-12)
		val dydt = DoubleArray(8)
		var steps = 0
		var lost = false
		try {
			ray.advance(state, Double.MAX_VALUE, stepLimit = { s ->
				ray.derivative(s, dydt)
				val speed = sqrt(dydt[1] * dydt[1] + dydt[2] * dydt[2] + dydt[3] * dydt[3]).coerceAtLeast(1e-9)
				0.02 / speed
			}) { s ->
				steps++
				ray.derivative(s, dydt)
				val far = sqrt(s[1] * s[1] + s[2] * s[2] + s[3] * s[3]) > 200.0
				if (dydt[0] <= 0 || steps > 400000) { lost = true; false } else !far
			}
		} catch (e: IllegalArgumentException) {
			return null
		}
		if (lost) return null
		ray.derivative(state, dydt)
		return V3(dydt[1], dydt[2], dydt[3]).normalized()
	}

	/** Light along [d] as a static observer sees it (u = ∂_t normalised; the axes projected and Löwdin-orthonormalised). */
	private fun staticLight(g: DoubleArray, d: V3): DoubleArray {
		fun dot(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (i in 0..3) for (j in 0..3) s += g[i * 4 + j] * a[i] * b[j]; return s }
		val u = doubleArrayOf(1.0 / sqrt(-g[0]), 0.0, 0.0, 0.0)
		val v = Array(3) { i -> DoubleArray(4).also { it[i + 1] = 1.0 }.let { e -> val c = dot(e, u); DoubleArray(4) { m -> e[m] + c * u[m] } } }
		val s = Array(3) { i -> DoubleArray(3) { j -> dot(v[i], v[j]) } }
		// S^(-1/2) by Jacobi.
		val a = Array(3) { s[it].copyOf() }
		val vecs = Array(3) { i -> DoubleArray(3) { j -> if (i == j) 1.0 else 0.0 } }
		repeat(50) {
			for (p in 0..1) for (q in p + 1..2) {
				if (kotlin.math.abs(a[p][q]) < 1e-15) continue
				val th = 0.5 * kotlin.math.atan2(2 * a[p][q], a[q][q] - a[p][p])
				val c = cos(th); val sn = sin(th)
				for (r in 0..2) { val ap = a[r][p]; val aq = a[r][q]; a[r][p] = c * ap - sn * aq; a[r][q] = sn * ap + c * aq }
				for (r in 0..2) { val ap = a[p][r]; val aq = a[q][r]; a[p][r] = c * ap - sn * aq; a[q][r] = sn * ap + c * aq }
				for (r in 0..2) { val vp = vecs[r][p]; val vq = vecs[r][q]; vecs[r][p] = c * vp - sn * vq; vecs[r][q] = sn * vp + c * vq }
			}
		}
		val w = Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { r -> vecs[i][r] * vecs[j][r] / sqrt(a[r][r]) } } }
		val frame = Array(3) { i -> DoubleArray(4) { m -> (0..2).sumOf { j -> w[i][j] * v[j][m] } } }
		val n = doubleArrayOf(d.x, d.y, d.z)
		return DoubleArray(4) { m -> u[m] + (0..2).sumOf { i -> n[i] * frame[i][m] } }
	}

	private fun cone(axis: V3, half: Double, random: java.util.Random): V3 {
		val (e1, e2) = FieldLens.tangents(axis)
		val t = half * sqrt(random.nextDouble())
		val a = 2 * Math.PI * random.nextDouble()
		return (axis * cos(t) + e1 * (sin(t) * cos(a)) + e2 * (sin(t) * sin(a))).normalized()
	}

	/** [TubeModule.quickAim] (the shaders' aim) against [TubeModule.aim]: how often it finds the image, and at what cost. */
	@Test
	fun quickAimFindsImages() {
		val random = java.util.Random(13)
		val report = StringBuilder("Quick tube aim against the full aim:\n")
		val failures = ArrayList<String>()
		for (scene in scenes) {
			val module = scene.module(radius)
			var both = 0
			var lost = 0
			var other = 0
			var spurious = 0
			var overCap = 0
			module.quickTraces = 0
			repeat(200) {
				val target = V3(random.nextDouble() * 60 - 30, random.nextDouble() * 24 - 12, random.nextDouble() * 24 - 12)
				val full = module.aim(scene.camera, target, false)
				val before = module.quickTraces
				val quick = module.quickAim(scene.camera, target)
				if (module.quickTraces - before > 10) overCap++
				when {
					full != null && quick != null -> { both++; if (acos((full dot quick).coerceIn(-1.0, 1.0)) > 1e-3) other++ }
					full != null -> { lost++; if (lost <= 3) report.append("    lost: target $target inside ${module.contains(target)}, image %.3f rad from straight\n".format(acos((full dot (target - scene.camera).normalized()).coerceIn(-1.0, 1.0)))) }
					quick != null -> spurious++
				}
			}
			report.append("  %-22s found %d (%d another image), lost %d, found where the full aim didn't %d; %.1f traces each, %d past 10\n".format(scene.name, both, other, lost, spurious, module.quickTraces / 200.0, overCap))
			if (lost > 10) failures += scene.name
		}
		println(report)
		assertTrue(failures.isEmpty(), "losing images: $failures")
	}

	/** [TubeModule.aim]'s directions, followed by the module itself, reach their targets: beside, behind and inside the tube. */
	@Test
	fun aimReachesTargets() {
		val random = java.util.Random(11)
		val report = StringBuilder("Warp tube aim (miss at the target, blocks):\n")
		val failures = ArrayList<String>()
		for (scene in scenes) {
			val module = scene.module(radius)
			var worst = 0.0
			var missed = 0
			var unreachable = 0
			repeat(100) {
				val target = V3(random.nextDouble() * 30 - 15, random.nextDouble() * 12 - 6, random.nextDouble() * 12 - 6)
				val d = module.aim(scene.camera, target, false)
				val miss = d?.let { module.missAt(scene.camera, it, target) } ?: V3(1e9, 0.0, 0.0)
				if (miss.length > 1e-3) {
					// Is there an image at all? A dense scan of directions round the target says.
					val straight = (target - scene.camera).normalized()
					val (e1, e2) = FieldLens.tangents(straight)
					var best = Double.MAX_VALUE
					var bestD = straight
					for (i in -60..60) for (j in -60..60) {
						val dd = (straight + e1 * (i / 60.0) + e2 * (j / 60.0)).normalized()
						val m = module.missAt(scene.camera, dd, target)?.length ?: continue
						if (m < best) { best = m; bestD = dd }
					}
					if (best < 0.05) missed++ else unreachable++
				}
				if (miss.length <= 1e-3) worst = maxOf(worst, miss.length)
			}
			report.append("  %-16s worst hit %.2e; missed %d of 100 that have an image, %d with none\n".format(scene.name, worst, missed, unreachable))
			if (missed > 5) failures += scene.name
		}
		println(report)
		assertTrue(failures.isEmpty(), "missing: $failures")
	}
}
