package net.kernelpanicsoft.relativity.physics

import net.kernelpanicsoft.relativity.physics.geodesic.HamiltonianRay
import net.kernelpanicsoft.relativity.physics.lens.field.FieldLens
import net.kernelpanicsoft.relativity.physics.lens.field.HoleModule
import net.kernelpanicsoft.relativity.physics.lens.field.LensModule
import net.kernelpanicsoft.relativity.physics.lens.field.MassModule
import net.kernelpanicsoft.relativity.physics.lens.field.OrbitField
import net.kernelpanicsoft.relativity.physics.lens.field.V3
import net.kernelpanicsoft.relativity.physics.spacetime.field.ComposedSpacetime
import net.kernelpanicsoft.relativity.physics.spacetime.field.KerrSource
import net.kernelpanicsoft.relativity.physics.spacetime.field.MetricSource
import net.kernelpanicsoft.relativity.physics.spacetime.field.PointMass
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [FieldLens] (sources composed along the light) against the lens's own model followed finely ([OrbitField]: each
 * hole's photon orbit field and each mass's weak field, superposed), from the same camera direction: where the sky seen
 * in each direction lies, and where points are seen. Errors in pixels of a 2256-px-wide screen at 70 degrees.
 */
class FieldLensTest {
	private val pixel = Math.toRadians(70.0) / 2256.0

	/**
	 * A scene of [modules] (and the same [sources] for the exact light) seen from [camera] toward [look]; [gate]: the
	 * 95th-percentile sky and image errors (px) it must stay within, for scenes with sources well apart (strongly coupled
	 * ones only need to look plausible: reported, not gated).
	 */
	private class Scene(val name: String, val camera: V3, val look: V3, val modules: List<LensModule>, val sources: List<MetricSource>, val gate: Pair<Double, Double>?)

	private fun hole(c: V3, rs: Double) = HoleModule(c, rs) to KerrSource(c.x, c.y, c.z, rs / 2.0, 0.0)
	private fun mass(c: V3, m: Double, s: Double) = MassModule(c, m, s) to PointMass(c.x, c.y, c.z, m, s)

	private fun scene(name: String, camera: V3, look: V3, vararg parts: Pair<LensModule, MetricSource>, gate: Pair<Double, Double>? = null) =
		Scene(name, camera, look, parts.map { it.first }, parts.map { it.second }, gate)

	private val scenes = listOf(
		scene("one hole", V3(-15.0, 1.0, 0.5), V3(0.0, 0.0, 0.0), hole(V3.ZERO, 2.0), gate = 1.0 to 15.0),
		scene("holes 40 apart", V3(-30.0, 3.0, 2.0), V3(20.0, 2.0, 0.0), hole(V3.ZERO, 2.0), hole(V3(40.0, 4.0, 0.0), 2.0)),
		scene("hole + weak mass", V3(-18.0, 2.0, 1.0), V3(4.0, -3.0, 0.0), hole(V3.ZERO, 2.0), mass(V3(10.0, -8.0, 0.0), 0.05, 2.0)),
		scene("mass alone", V3(-18.0, 2.0, 1.0), V3(8.0, -6.0, 0.0), mass(V3(8.0, -6.0, 0.0), 0.2, 2.0), gate = 30.0 to 15.0),
		scene("hole, then small hole", V3(-20.0, 3.0, 2.0), V3(6.0, 2.0, 0.0), hole(V3.ZERO, 2.0), hole(V3(12.0, 4.0, 0.0), 0.01), gate = 15.0 to 10.0),
		scene("small hole, then hole", V3(-20.0, 3.0, 2.0), V3(6.0, 2.0, 0.0), hole(V3(-6.0, -4.0, 0.0), 0.01), hole(V3(12.0, 4.0, 0.0), 2.0)),
		scene("two holes apart", V3(-20.0, 3.0, 2.0), V3(6.0, 2.0, 0.0), hole(V3.ZERO, 2.0), hole(V3(12.0, 4.0, 0.0), 2.0)),
		scene("hole + mass", V3(-18.0, 2.0, 1.0), V3(4.0, -3.0, 0.0), hole(V3.ZERO, 2.0), mass(V3(8.0, -6.0, 0.0), 0.2, 2.0)),
		scene("binary, 6 r_s apart", V3(-25.0, 2.0, 3.0), V3(3.0, 0.0, 0.0), hole(V3.ZERO, 2.0), hole(V3(6.0, 0.0, 0.0), 2.0)),
		scene("camera between holes", V3(0.0, 0.0, 0.0), V3(1.0, 0.0, 0.0), hole(V3(-12.0, 0.0, 0.0), 2.0), hole(V3(14.0, 3.0, 0.0), 2.0)),
		// The client test twoHoles' scene (r_s 2 at the origin, 1.5 at (16, 0, -7)) from its three poses.
		scene("twoHoles side", V3(-15.0, 0.0, 0.0), V3(0.0, -2.6, 0.0), hole(V3.ZERO, 2.0), hole(V3(16.0, 0.0, -7.0), 1.5)),
		scene("twoHoles above", V3(-10.0, 8.0, 0.0), V3(0.0, -2.0, 0.0), hole(V3.ZERO, 2.0), hole(V3(16.0, 0.0, -7.0), 1.5)),
		scene("twoHoles between", V3(8.0, 0.0, 6.0), V3(13.0, -0.9, -2.7), hole(V3.ZERO, 2.0), hole(V3(16.0, 0.0, -7.0), 1.5)),
	)

	@Test
	fun skyAndImagesAgainstExactLight() {
		val report = StringBuilder()
		val failures = ArrayList<String>()
		for (scene in scenes) {
			val lens = FieldLens(scene.modules)
			val model = OrbitField(scene.modules)
			val random = java.util.Random(7)
			val skyErrors = ArrayList<Double>()
			var captureMismatch = 0
			val imageErrors = ArrayList<Double>()
			val solvedErrors = ArrayList<Double>()
			var solvedMissing = 0
			var imageMissing = 0
			var otherImages = 0
			val forward = (scene.look - scene.camera).normalized()
			for (n in 0 until 240) {
				val d = coneDirection(forward, Math.toRadians(35.0), random)
				val exact = model(lens, model, scene.camera, d)
				val composed = lens.sky(scene.camera, d)
				if ((exact.escape == null) != (composed == null)) { captureMismatch++; continue }
				if (exact.escape != null) skyErrors += angle(exact.escape, composed!!.direction) / pixel
				// Points along the exact light, 4 .. 60 blocks along: where are they seen?
				if (n % 3 == 0) {
					val candidates = exact.points.filter { it.second in 4.0..60.0 }
					if (candidates.isEmpty()) continue
					val p = candidates[random.nextInt(candidates.size)].first
					val seen = listOfNotNull(lens.image(scene.camera, p, false), lens.image(scene.camera, p, true))
					// The composition solved from the chain's guesses (or the straight line).
					val solved = (seen.ifEmpty { listOf((p - scene.camera).normalized()) }).mapNotNull { lens.solve(scene.camera, p, it) }
					if (solved.isEmpty()) solvedMissing++ else {
						val best = solved.minBy { angle(it, d) }
						var error = angle(best, d) / pixel
						if (error > 5.0) {
							val other = model(lens, model, scene.camera, best).points.minOfOrNull { (it.first - p).length } ?: Double.MAX_VALUE
							error = minOf(error, other / (p - scene.camera).length / pixel)
						}
						solvedErrors += error
					}
					if (seen.isEmpty()) imageMissing++ else {
						val best = seen.minBy { angle(it, d) }
						var error = angle(best, d) / pixel
						// A point can have several images: one the composer found that isn't the traced ray's is still
						// right if light really leaves that way to the point. Measured by how close the exact light
						// from it passes the point (as an angle from the camera).
						if (error > 5.0) {
							val other = model(lens, model, scene.camera, best).points.minOfOrNull { (it.first - p).length } ?: Double.MAX_VALUE
							val asAngle = other / (p - scene.camera).length / pixel
							if (asAngle < error) { otherImages++; error = asAngle }
						}
						imageErrors += error
					}
				}
			}
			scene.gate?.let { (sky, images) ->
				if (percentile(skyErrors, 0.95) > sky || percentile(imageErrors, 0.95) > images) failures += scene.name
			}
			report.append(String.format(
				"%-22s sky px: median %.2f, 95%% %.2f, max %.1f; capture mismatches %d | images px: median %.2f, 95%% %.2f, max %.1f; missing %d of %d; other images %d | solved px: median %.2f, 95%% %.2f, max %.1f; missing %d%n",
				scene.name, percentile(skyErrors, 0.5), percentile(skyErrors, 0.95), skyErrors.maxOrNull() ?: 0.0, captureMismatch,
				percentile(imageErrors, 0.5), percentile(imageErrors, 0.95), imageErrors.maxOrNull() ?: 0.0, imageMissing, imageErrors.size + imageMissing, otherImages,
				percentile(solvedErrors, 0.5), percentile(solvedErrors, 0.95), solvedErrors.maxOrNull() ?: 0.0, solvedMissing,
			))
		}
		println(report)
		assertTrue(failures.isEmpty(), "past their gates: $failures")
	}

	/** The march's accuracy against the model followed finely, and its cost (field evaluations per ray). */
	@Test
	fun marchCost() {
		for (scene in scenes.filter { it.name.startsWith("twoHoles") || it.name.startsWith("binary") }) {
			val lens = FieldLens(scene.modules)
			val model = OrbitField(scene.modules)
			val random = java.util.Random(7)
			val errors = ArrayList<Double>()
			FieldLens.marchEvaluations = 0
			repeat(150) {
				val d = coneDirection((scene.look - scene.camera).normalized(), Math.toRadians(35.0), random)
				val exact = model(lens, model, scene.camera, d)
				val composed = lens.sky(scene.camera, d)
				if (exact.escape != null && composed != null) errors += angle(exact.escape, composed.direction) / pixel
			}
			println(String.format("march, %s: sky px median %.2f, 95%% %.1f; %.0f field evaluations per ray", scene.name, percentile(errors, 0.5), percentile(errors, 0.95), FieldLens.marchEvaluations / 150.0))
			assertTrue(percentile(errors, 0.95) < 15.0, "${scene.name}: the march's sky is off")
		}
	}

	/** Light from [camera] along camera direction [d], followed finely through [model] (from the lens's own coordinate direction). */
	private fun model(lens: FieldLens, model: OrbitField, camera: V3, d: V3): Traced {
		val traced = model.trace(camera, lens.coordinate(camera, d), record = true)
		var length = 0.0
		var last = camera
		val points = traced.points.map { p -> length += (p - last).length; last = p; p to length }
		return Traced(traced.escape, points)
	}

	private class Traced(val escape: V3?, val points: List<Pair<V3, Double>>)

	/** Light leaving a static observer at [camera] along [d] (their frame), followed until captured or far out. */
	private fun trace(spacetime: ComposedSpacetime, camera: V3, d: V3): Traced {
		val ray = HamiltonianRay(spacetime, 1e-10, 1e-12)
		val state = launch(spacetime, camera, d)
		val points = ArrayList<Pair<V3, Double>>()
		var length = 0.0
		var last = camera
		var captured = false
		val dydt = DoubleArray(8)
		val centres = scenesCentres(spacetime)
		try {
			ray.advance(state, Double.MAX_VALUE, stepLimit = { s ->
				ray.derivative(s, dydt)
				val speed = sqrt(dydt[1] * dydt[1] + dydt[2] * dydt[2] + dydt[3] * dydt[3]).coerceAtLeast(1e-9)
				val near = centres.minOf { (V3(s[1], s[2], s[3]) - it).length }
				(if (length < 70.0) 0.2 else 0.05 * near) / speed
			}) { s ->
				val p = V3(s[1], s[2], s[3])
				length += (p - last).length
				last = p
				points += p to length
				when {
					spacetime.captures(s) -> { captured = true; false }
					centres.minOf { (p - it).length } > 1e6 -> false
					else -> true
				}
			}
		} catch (e: IllegalArgumentException) {
			captured = true
		}
		if (captured) return Traced(null, points)
		val v = DoubleArray(3)
		ray.coordinateVelocity(state, v)
		return Traced(V3(v[0], v[1], v[2]).normalized(), points)
	}

	private fun scenesCentres(spacetime: ComposedSpacetime): List<V3> = spacetime.sources.map {
		when (it) {
			is KerrSource -> V3(it.cx, it.cy, it.cz)
			is PointMass -> V3(it.cx, it.cy, it.cz)
			else -> V3.ZERO
		}
	}

	/**
	 * The null state of light a static observer at [camera] sends along [n] in their own frame: the frame from the
	 * coordinate axes by symmetric (Löwdin) orthonormalisation, which keeps radial directions radial.
	 */
	private fun launch(spacetime: ComposedSpacetime, camera: V3, n: V3): DoubleArray {
		val x = doubleArrayOf(0.0, camera.x, camera.y, camera.z)
		val g = DoubleArray(16)
		spacetime.metric(x, g)
		fun dot(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (i in 0..3) for (j in 0..3) s += g[i * 4 + j] * a[i] * b[j]; return s }
		val u = doubleArrayOf(1.0 / sqrt(-g[0]), 0.0, 0.0, 0.0)
		val v = Array(3) { i -> DoubleArray(4).also { it[i + 1] = 1.0 }.let { e -> val k = dot(e, u); DoubleArray(4) { m -> e[m] + k * u[m] } } }
		val s = Array(3) { i -> DoubleArray(3) { j -> dot(v[i], v[j]) } }
		val w = inverseSqrt(s)
		val frame = Array(3) { i -> DoubleArray(4) { m -> (0..2).sumOf { j -> w[i][j] * v[j][m] } } }
		val nn = doubleArrayOf(n.x, n.y, n.z)
		val k = DoubleArray(4) { m -> u[m] + (0..2).sumOf { i -> nn[i] * frame[i][m] } }
		val state = DoubleArray(8)
		for (mu in 0..3) {
			state[mu] = x[mu]
			state[4 + mu] = (0..3).sumOf { nu -> g[mu * 4 + nu] * k[nu] }
		}
		return state
	}

	/** S^(-1/2) of a symmetric positive 3x3 matrix (Jacobi eigen-decomposition). */
	private fun inverseSqrt(s: Array<DoubleArray>): Array<DoubleArray> {
		val a = Array(3) { s[it].copyOf() }
		val vecs = Array(3) { i -> DoubleArray(3) { j -> if (i == j) 1.0 else 0.0 } }
		repeat(50) {
			for (p in 0..1) for (q in p + 1..2) {
				if (kotlin.math.abs(a[p][q]) < 1e-15) continue
				val theta = 0.5 * kotlin.math.atan2(2 * a[p][q], a[q][q] - a[p][p])
				val c = cos(theta); val sn = sin(theta)
				for (k in 0..2) { val akp = a[k][p]; val akq = a[k][q]; a[k][p] = c * akp - sn * akq; a[k][q] = sn * akp + c * akq }
				for (k in 0..2) { val apk = a[p][k]; val aqk = a[q][k]; a[p][k] = c * apk - sn * aqk; a[q][k] = sn * apk + c * aqk }
				for (k in 0..2) { val vkp = vecs[k][p]; val vkq = vecs[k][q]; vecs[k][p] = c * vkp - sn * vkq; vecs[k][q] = sn * vkp + c * vkq }
			}
		}
		return Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { k -> vecs[i][k] * vecs[j][k] / sqrt(a[k][k]) } } }
	}

	private fun coneDirection(axis: V3, half: Double, random: java.util.Random): V3 {
		val (e1, e2) = FieldLens.tangents(axis)
		val t = half * sqrt(random.nextDouble())
		val a = 2 * Math.PI * random.nextDouble()
		return (axis * cos(t) + e1 * (sin(t) * cos(a)) + e2 * (sin(t) * sin(a))).normalized()
	}

	private fun angle(a: V3, b: V3) = acos((a.normalized() dot b.normalized()).coerceIn(-1.0, 1.0))

	private fun percentile(values: List<Double>, q: Double): Double {
		if (values.isEmpty()) return 0.0
		val sorted = values.sorted()
		return sorted[((sorted.size - 1) * q).toInt()]
	}
}
