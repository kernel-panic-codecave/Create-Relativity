package net.kernelpanicsoft.relativity.gametest

import net.kernelpanicsoft.relativity.physics.spacetime.field.ComposedSpacetime
import net.kernelpanicsoft.relativity.physics.geodesic.HamiltonianRay
import net.kernelpanicsoft.relativity.spacetime.FieldSource
import net.kernelpanicsoft.relativity.spacetime.GameFrame
import org.joml.Quaternionfc
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.Vector3f
import kotlin.math.sqrt

/**
 * The lens's ground truth for tests: where the camera sees a point, by physics-core's own tracer (HamiltonianRay
 * through the sources' metrics, light followed arriving), not the shaders. Gauss-Newton on where light seen along a
 * direction lands at the point's distance, from each seed given; every distinct image it converges to. Slow (a few
 * thousand traces a point): for a test's sample of points, in parallel.
 */
class CpuLensReference(sources: List<FieldSource>, private val eye: Vector3dc, private val viewToWorld: Quaternionfc) {
	private val metrics = sources.map { it.toPhysics(eye) }

	// (One spacetime and tracer a thread: ComposedSpacetime keeps scratch state.)
	private val rays = ThreadLocal.withInitial { HamiltonianRay(ComposedSpacetime(metrics), rtol = 1e-8, atol = 1e-10) }

	/** Where light seen along physics direction [d] (unit) first gets [r] from the camera; null if it doesn't. */
	private fun reach(d: DoubleArray, r: Double): DoubleArray? {
		val path = rays.get().trace(doubleArrayOf(0.0, 0.0, 0.0), d, 4.0 * r + 20.0, maxSegment = 0.05, arriving = true)
		val points = path.points
		for (i in 1 until points.size) {
			val b = points[i]
			if (b[0] * b[0] + b[1] * b[1] + b[2] * b[2] < r * r) continue
			val a = points[i - 1]
			val u = DoubleArray(3) { b[it] - a[it] }
			val len = sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]).coerceAtLeast(1e-12)
			for (k in 0..2) u[k] /= len
			val bb = a[0] * u[0] + a[1] * u[1] + a[2] * u[2]
			val t = -bb + sqrt((bb * bb - (a[0] * a[0] + a[1] * a[1] + a[2] * a[2] - r * r)).coerceAtLeast(0.0))
			return DoubleArray(3) { a[it] + u[it] * t }
		}
		return null
	}

	private fun normalize(v: DoubleArray): DoubleArray {
		val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
		return DoubleArray(3) { v[it] / l }
	}

	private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

	/** From physics seed direction [seed]: the direction whose light lands within [tolerance] of [p] (physics), or null. */
	private fun solve(p: DoubleArray, seed: DoubleArray, tolerance: Double): DoubleArray? {
		val r = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
		var d = normalize(seed)
		val h = 1e-5
		for (iteration in 0 until 12) {
			val e1 = normalize(cross(d, if (kotlin.math.abs(d[2]) < 0.9) doubleArrayOf(0.0, 0.0, 1.0) else doubleArrayOf(1.0, 0.0, 0.0)))
			val e2 = cross(d, e1)
			val at = reach(d, r) ?: return null
			val res = DoubleArray(3) { at[it] - p[it] }
			if (sqrt(res[0] * res[0] + res[1] * res[1] + res[2] * res[2]) < tolerance) return d
			val ax = reach(normalize(DoubleArray(3) { d[it] + e1[it] * h }), r) ?: return null
			val ay = reach(normalize(DoubleArray(3) { d[it] + e2[it] * h }), r) ?: return null
			val ja = DoubleArray(3) { (ax[it] - at[it]) / h }
			val jb = DoubleArray(3) { (ay[it] - at[it]) / h }
			fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
			val aa = dot(ja, ja)
			val ab = dot(ja, jb)
			val bb = dot(jb, jb)
			val det = aa * bb - ab * ab
			if (kotlin.math.abs(det) < 1e-24) return null
			val ra = -dot(ja, res)
			val rb = -dot(jb, res)
			val da = ((bb * ra - ab * rb) / det).coerceIn(-0.1, 0.1)
			val db = ((aa * rb - ab * ra) / det).coerceIn(-0.1, 0.1)
			d = normalize(DoubleArray(3) { d[it] + e1[it] * da + e2[it] * db })
		}
		return null
	}

	private fun viewToPhysics(v: FloatArray): DoubleArray {
		val w = viewToWorld.transform(Vector3f(v[0], v[1], v[2]))
		val out = DoubleArray(3)
		GameFrame.toPhysics(Vector3d(w.x.toDouble(), w.y.toDouble(), w.z.toDouble()), out)
		return out
	}

	private fun physicsToView(d: DoubleArray): FloatArray {
		val w = GameFrame.toGame(d, 0, Vector3d())
		val v = viewToWorld.transformInverse(Vector3f(w.x.toFloat(), w.y.toFloat(), w.z.toFloat()))
		return floatArrayOf(v.x, v.y, v.z)
	}

	/**
	 * Every image of view-space point [point] (camera-relative) reached from [seeds] (view directions; the straight
	 * one is always tried): unit view directions, those closer than [distinct] radians to one already found dropped.
	 * [pixelAngle] sets the tolerance (a tenth of a pixel at the point's distance).
	 */
	fun images(point: FloatArray, seeds: List<FloatArray>, pixelAngle: Double, distinct: Double = 1e-3): List<FloatArray> {
		val p = viewToPhysics(point)
		val r = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
		val found = ArrayList<DoubleArray>()
		for (seed in listOf(point) + seeds) {
			val d = solve(p, viewToPhysics(seed), 0.1 * pixelAngle * r) ?: continue
			if (found.none { f -> kotlin.math.acos((f[0] * d[0] + f[1] * d[1] + f[2] * d[2]).coerceIn(-1.0, 1.0)) < distinct }) found += d
		}
		return found.map { physicsToView(it) }
	}
}
