package net.kernelpanicsoft.relativity.spacetime

import org.joml.Vector3d
import org.joml.Vector3dc

/**
 * A tube's centre line as a chain of cubic Bézier pieces (world), each [a] to [d] with handles [b], [c]: the track's own
 * curves (Create's turns are cubic Béziers, its straights degenerate ones), so a tube follows a bend exactly however it's
 * later cut into segments: finely for the physics ([polyline]), by what the eye can tell for the lens.
 */
data class Cubic(val a: Vector3dc, val b: Vector3dc, val c: Vector3dc, val d: Vector3dc) {
	/** The point at parameter [t] (0 at [a], 1 at [d]). */
	fun at(t: Double): Vector3d {
		val s = 1.0 - t
		val w0 = s * s * s
		val w1 = 3 * s * s * t
		val w2 = 3 * s * t * t
		val w3 = t * t * t
		return Vector3d(
			w0 * a.x() + w1 * b.x() + w2 * c.x() + w3 * d.x(),
			w0 * a.y() + w1 * b.y() + w2 * c.y() + w3 * d.y(),
			w0 * a.z() + w1 * b.z() + w2 * c.z() + w3 * d.z(),
		)
	}

	/** The piece from parameter [t0] to [t1] (either way round: [t1] < [t0] runs it backwards). */
	fun part(t0: Double, t1: Double): Cubic {
		// (De Casteljau's handles at each end: the curve's points and tangents there, scaled to the span.)
		val span = t1 - t0
		val p0 = at(t0)
		val p3 = at(t1)
		val d0 = derivative(t0).mul(span / 3.0)
		val d1 = derivative(t1).mul(span / 3.0)
		return Cubic(p0, Vector3d(p0).add(d0), Vector3d(p3).sub(d1), p3)
	}

	/** The tangent (dP/dt) at [t]. */
	fun derivative(t: Double): Vector3d {
		val s = 1.0 - t
		val w0 = -3 * s * s
		val w1 = 3 * s * s - 6 * s * t
		val w2 = 6 * s * t - 3 * t * t
		val w3 = 3 * t * t
		return Vector3d(
			w0 * a.x() + w1 * b.x() + w2 * c.x() + w3 * d.x(),
			w0 * a.y() + w1 * b.y() + w2 * c.y() + w3 * d.y(),
			w0 * a.z() + w1 * b.z() + w2 * c.z() + w3 * d.z(),
		)
	}

	/** How far its handles stray from the chord [a]–[d] (blocks): an upper bound on the curve's own straying. */
	fun flatness(): Double {
		val chord = Vector3d(d).sub(a)
		val l2 = chord.lengthSquared()
		fun off(p: Vector3dc): Double {
			val rel = Vector3d(p).sub(a)
			val u = if (l2 > 0) (rel.dot(chord) / l2).coerceIn(0.0, 1.0) else 0.0
			return rel.sub(Vector3d(chord).mul(u)).length()
		}
		return maxOf(off(b), off(c))
	}

	/** Its length, about (the mean of the chord's and the control polygon's: close for track's gentle curves). */
	fun length(): Double = 0.5 * (a.distance(d) + a.distance(b) + b.distance(c) + c.distance(d))

	/** Moved by ([x], [y], [z]). */
	fun moved(x: Double, y: Double, z: Double) = Cubic(
		Vector3d(a).add(x, y, z), Vector3d(b).add(x, y, z), Vector3d(c).add(x, y, z), Vector3d(d).add(x, y, z),
	)

	companion object {
		/** The straight piece from [a] to [d]. */
		fun line(a: Vector3dc, d: Vector3dc) = Cubic(a, Vector3d(a).lerp(d, 1.0 / 3.0), Vector3d(a).lerp(d, 2.0 / 3.0), d)

		/** The cubic through four points at parameters 0, 1/3, 2/3, 1 (exact for one: Create's turns sampled so). */
		fun through(p0: Vector3dc, p1: Vector3dc, p2: Vector3dc, p3: Vector3dc): Cubic {
			// b = (-5 p0 + 18 p1 - 9 p2 + 2 p3) / 6, c = (2 p0 - 9 p1 + 18 p2 - 5 p3) / 6
			fun mix(w0: Double, w1: Double, w2: Double, w3: Double) = Vector3d(
				(w0 * p0.x() + w1 * p1.x() + w2 * p2.x() + w3 * p3.x()) / 6.0,
				(w0 * p0.y() + w1 * p1.y() + w2 * p2.y() + w3 * p3.y()) / 6.0,
				(w0 * p0.z() + w1 * p1.z() + w2 * p2.z() + w3 * p3.z()) / 6.0,
			)
			return Cubic(p0, mix(-5.0, 18.0, -9.0, 2.0), mix(2.0, -9.0, 18.0, -5.0), p3)
		}

		/** Straight pieces through [points] in turn. */
		fun polyline(points: List<Vector3dc>): List<Cubic> = points.zipWithNext { p, q -> line(p, q) }
	}
}

/**
 * [curve] as points, each piece halved until it strays less than [tolerance] blocks from straight there ([tolerance] of
 * a point: its own, so it may grow with distance from a viewer), at most [depth] halvings a piece.
 */
fun polyline(curve: List<Cubic>, tolerance: (Vector3dc) -> Double, depth: Int = 10): List<Vector3dc> =
	samples(curve, tolerance, depth).map { it.first }

/**
 * As [polyline], each point with where it is along [curve]: its piece's index plus its parameter there (so a value
 * given at each piece's ends can be read off it: [along]).
 */
fun samples(curve: List<Cubic>, tolerance: (Vector3dc) -> Double, depth: Int = 10): List<Pair<Vector3dc, Double>> {
	val out = ArrayList<Pair<Vector3dc, Double>>()
	fun add(piece: Cubic, index: Int, t0: Double, t1: Double, level: Int) {
		val part = if (t0 == 0.0 && t1 == 1.0) piece else piece.part(t0, t1)
		if (level < depth && part.flatness() > tolerance(part.at(0.5))) {
			val mid = 0.5 * (t0 + t1)
			add(piece, index, t0, mid, level + 1)
			add(piece, index, mid, t1, level + 1)
			return
		}
		out += part.d to index + t1
	}
	for ((i, piece) in curve.withIndex()) {
		if (i == 0 || out.last().first.distance(piece.a) > 1e-6) out += piece.a to i.toDouble()
		add(piece, i, 0.0, 1.0, 0)
	}
	return out
}

/** A value given at each of a curve's pieces' ends ([ends]: one more than its pieces) at [position] along it (as [samples] gives). */
fun along(ends: List<Double>, position: Double): Double {
	val i = position.toInt().coerceIn(0, ends.size - 2)
	val t = (position - i).coerceIn(0.0, 1.0)
	return ends[i] + (ends[i + 1] - ends[i]) * t
}
