package net.kernelpanicsoft.relativity.physics

import net.kernelpanicsoft.relativity.physics.geodesic.HamiltonianRay
import net.kernelpanicsoft.relativity.physics.lens.field.BubbleModule
import net.kernelpanicsoft.relativity.physics.lens.field.FieldLens
import net.kernelpanicsoft.relativity.physics.lens.field.LightLine
import net.kernelpanicsoft.relativity.physics.lens.field.TubeModule
import net.kernelpanicsoft.relativity.physics.lens.field.V3
import net.kernelpanicsoft.relativity.physics.spacetime.field.ComposedSpacetime
import net.kernelpanicsoft.relativity.physics.spacetime.field.FieldSample
import net.kernelpanicsoft.relativity.physics.spacetime.field.WarpEllipsoid
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The bubble lens module ([BubbleModule]: a thin ellipsoidal wall) against light followed exactly through a frozen warp
 * bubble ([WarpEllipsoid], not moving, its wall a twentieth of its size): where light from cameras inside (riders) and
 * beside it ends up heading, slower and faster than light.
 */
class BubbleLensTest {
	private class Scene(val name: String, val speed: Double, val camera: V3, val look: V3, val cone: Double)

	private val axes = listOf(V3(6.0, 0.0, 0.0), V3(0.0, 4.0, 0.0), V3(0.0, 0.0, 5.0))
	private val n = V3(1.0, 0.0, 0.0)

	private val scenes = listOf(
		Scene("inside, slow", 0.5, V3(0.5, 0.3, 0.0), V3(1.0, 0.2, 0.1), 1.2),
		Scene("inside, warp 2", 2.0, V3(0.5, 0.3, 0.0), V3(-1.0, 0.2, 0.1), 1.2),
		Scene("inside, forward 2", 2.0, V3(0.5, 0.3, 0.0), V3(1.0, 0.1, 0.2), 1.2),
		Scene("beside, slow", 0.5, V3(-3.0, 9.0, 1.0), V3(0.2, -1.0, 0.0), 0.6),
		Scene("beside, warp 2", 2.0, V3(-3.0, 9.0, 1.0), V3(0.2, -1.0, 0.0), 0.6),
	)

	@Test
	fun againstExactLight() {
		val report = StringBuilder("Warp bubble lens vs exact light (angle of the escape direction, degrees):\n")
		val failures = ArrayList<String>()
		for (scene in scenes) {
			val module = BubbleModule(V3.ZERO, axes, n, TubeModule.Interior.Warp(scene.speed))
			val metric = WarpEllipsoid(0.0, 0.0, 0.0, doubleArrayOf(6.0, 0.0, 0.0, 0.0, 4.0, 0.0, 0.0, 0.0, 5.0), doubleArrayOf(1.0, 0.0, 0.0), scene.speed, sigma = 60.0, moving = false)
			val spacetime = ComposedSpacetime(listOf(metric))
			val random = java.util.Random(7)
			val errors = ArrayList<Double>()
			var captureMismatch = 0
			val byEvents = java.util.TreeMap<Int, ArrayList<Double>>()
			repeat(150) {
				val d = cone(scene.look.normalized(), scene.cone, random)
				val exact = trace(spacetime, scene.camera, d)
				val lensed = module.pass(LightLine(scene.camera, d))
				if ((exact == null) != (lensed == null)) captureMismatch++
				else if (exact != null && lensed != null) {
					val e = Math.toDegrees(acos((exact dot lensed.direction).coerceIn(-1.0, 1.0)))
					val events = (module.path(scene.camera, d, 1.0)?.size ?: 2) - 2
					byEvents.getOrPut(minOf(events, 3)) { ArrayList() } += e
					errors += e
				}
			}
			report.append("    by wall events: " + byEvents.entries.joinToString { (k, v) -> v.sort(); "$k: ${v.size} rays, median %.2f".format(v[v.size / 2]) } + "\n")
			errors.sort()
			val median = errors.getOrElse(errors.size / 2) { 0.0 }
			val p90 = errors.getOrElse((errors.size * 0.9).toInt().coerceAtMost(errors.size - 1)) { 0.0 }
			val off = errors.count { it > 5.0 }.toDouble() / maxOf(errors.size, 1)
			report.append("  %-18s median %.3f, 90%% %.3f, max %.3f, past 5 degrees %.0f%%; capture mismatches %d of 150\n".format(scene.name, median, p90, errors.lastOrNull() ?: 0.0, off * 100, captureMismatch))
			if (median > 1.0 || off > 0.15 || captureMismatch > 8) failures += scene.name
		}
		println(report)
		assertTrue(failures.isEmpty(), "past their gates: $failures")
	}

	/** Light sent along [d] from [camera] by the field's Eulerian observer (a rider; at rest outside): where it heads at last. */
	private fun trace(spacetime: ComposedSpacetime, camera: V3, d: V3): V3? {
		val sample = FieldSample()
		val x = doubleArrayOf(0.0, camera.x, camera.y, camera.z)
		spacetime.inverse(x, sample)
		val g = DoubleArray(16)
		spacetime.metric(x, g)
		val k = doubleArrayOf(1.0, -sample.value[1] + d.x, -sample.value[2] + d.y, -sample.value[3] + d.z)
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
				val far = sqrt(s[1] * s[1] + s[2] * s[2] + s[3] * s[3]) > 100.0
				if (dydt[0] <= 0 || steps > 400000) { lost = true; false } else !far
			}
		} catch (e: IllegalArgumentException) {
			return null
		}
		if (lost) return null
		ray.derivative(state, dydt)
		return V3(dydt[1], dydt[2], dydt[3]).normalized()
	}

	private fun cone(axis: V3, half: Double, random: java.util.Random): V3 {
		val (e1, e2) = FieldLens.tangents(axis)
		val t = half * sqrt(random.nextDouble())
		val a = 2 * Math.PI * random.nextDouble()
		return (axis * cos(t) + e1 * (sin(t) * cos(a)) + e2 * (sin(t) * sin(a))).normalized()
	}
}
