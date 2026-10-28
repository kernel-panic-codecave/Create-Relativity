package net.kernelpanicsoft.relativity.physics.lens.field

import kotlin.math.abs
import kotlin.math.sqrt

/** A small immutable 3-vector for the lens's geometry. */
data class V3(val x: Double, val y: Double, val z: Double) {
	operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
	operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
	operator fun times(s: Double) = V3(x * s, y * s, z * s)
	operator fun unaryMinus() = V3(-x, -y, -z)
	infix fun dot(o: V3) = x * o.x + y * o.y + z * o.z
	infix fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
	val length: Double get() = sqrt(this dot this)
	fun normalized(): V3 = this * (1.0 / length)

	companion object {
		val ZERO = V3(0.0, 0.0, 0.0)
	}
}

/** A straight stretch of light: from [origin] along unit [direction], the coordinate tangent of its path there. */
data class LightLine(val origin: V3, val direction: V3) {
	/** How far along the line the point nearest [p] is. */
	fun along(p: V3): Double = (p - origin) dot direction

	fun at(t: Double): V3 = origin + direction * t
}

/**
 * One source's effect on light, for [FieldLens]: [pass] follows light past it exactly, from where the light is out to
 * where it no longer bends it (a hole: its orbit, from the specific metric, close in or far out); [field] is its weak
 * field on a straight stretch, for the light's way elsewhere. How exact [pass] is, is the module's own business.
 * Directions are coordinate tangents of the light's path (the same in every coordinate system sharing the spatial
 * coordinates: Schwarzschild's, Kerr-Schild's); [toCoordinate] and [toObserved] convert a static observer's.
 */
interface LensModule {
	val centre: V3

	/** The source's size (blocks): a point's distance from [centre] in units of it says how strongly it bends there. */
	val scale: Double

	/**
	 * [line] after passing this source, from [line]'s origin on (where the light is): the straight line it leaves
	 * along (its origin where the light turned: where the incoming and outgoing lines meet), or null if the source
	 * captures it.
	 */
	fun pass(line: LightLine): LightLine?

	/**
	 * The direction light must leave [from] in to reach [target], through this source's field alone: its primary
	 * image, or with [secondary] its secondary one (the long way round); null if there's no such light (or this source
	 * doesn't aim: weak ones leave it to [FieldLens]).
	 */
	fun aim(from: V3, target: V3, secondary: Boolean): V3?

	/** This source's weak field on light running straight from [a] to [b]: how much it turns it, and moves it at [b]. */
	fun field(a: V3, b: V3): Pair<V3, V3>

	/** A static observer's direction [d] at [at] as a coordinate tangent, through this source's field. */
	fun toCoordinate(at: V3, d: V3): V3 = d

	/** The inverse of [toCoordinate]. */
	fun toObserved(at: V3, d: V3): V3 = d
}

/**
 * Lensing through any number of sources by composing their [LensModule]s along the light (multi-plane lensing): no
 * marching, no tables built per frame. [sky] follows light from the camera out (the post pass); [image] finds where
 * a point is seen (the vertex lens), each source's exact single-source image chained back toward the camera, nothing
 * solved. The camera is a static observer; its directions are its own.
 *
 * [sky]: sources are passed in the order the light meets them. Each acts exactly from the stretch where the light reaches it
 * ([LensModule.pass], out to infinity along that line), and weakly ([LensModule.field]) on every stretch before, as the
 * light goes: so a source farther on still sees, and a strong one magnifies, what a weak one did first. When a later
 * source turns the light, each source already passed has its weak tail moved from the old line to the new one.
 */
class FieldLens(val modules: List<LensModule>) {
	/**
	 * Light leaving [camera] along unit [direction] (as the camera sees it): the line it ends on once past every source,
	 * or null if a source captures it.
	 */
	fun sky(camera: V3, direction: V3): LightLine? = propagate(LightLine(camera, coordinate(camera, direction)))

	/**
	 * Light leaving [camera] along unit [direction] (as the camera sees it), followed past every source: each piece of
	 * its path to [pieces] in turn (its line, and from and to along it; the last to [Double.MAX_VALUE]). False if a source
	 * captures it.
	 */
	fun pieces(camera: V3, direction: V3, pieces: (LightLine, Double, Double) -> Unit): Boolean =
		propagate(LightLine(camera, coordinate(camera, direction)), pieces) != null

	/** As [sky], from a coordinate tangent [coordinateDirection] (no observer's direction converted). */
	fun skyCoordinate(camera: V3, coordinateDirection: V3): LightLine? = propagate(LightLine(camera, coordinateDirection.normalized()))

	/**
	 * The direction from [camera] in which [target] is seen (primary image, or [secondary]), or null if a hole hides it:
	 * each source's own exact image, chained back toward the camera. The source the light
	 * meets last (farthest along the line of sight to [target]) images the target; the point as that makes it appear
	 * (at the same distance, along its image) is imaged by the next source back; and so on to the camera. Exact for one
	 * source; for several, as if each saw the light come straight from the camera (against light followed exactly, it's
	 * as close as solving the composition by Newton's method, or closer, at a fraction of the cost: FieldLensTest).
	 */
	fun image(camera: V3, target: V3, secondary: Boolean = false): V3? {
		val line = (target - camera).normalized()
		val order = modules.sortedByDescending { (it.centre - camera) dot line }
		// (The secondary image is the target's own nearest hole's; the rest image it as a primary.)
		val terminal = modules.minByOrNull { (target - it.centre).length / it.scale }
		var point = target
		var direction = (target - camera).normalized()
		val distance = (target - camera).length
		for (m in order) {
			val aimed = m.aim(camera, point, secondary && m === terminal)
			direction = when {
				aimed != null -> aimed
				m is MassModule -> {
					// A source that doesn't aim (weak): the light it bends toward itself reaches the point from a
					// direction as far the other way as it moves the light there.
					val move = m.field(camera, point).second
					(direction - move * (1.0 / distance)).normalized()
				}
				else -> return null
			}
			point = camera + direction * distance
		}
		return observed(camera, direction)
	}

	/**
	 * How far light leaving [camera] along unit [direction] passes from [target]: the target less the nearest point of
	 * its path (its straight pieces, each from one source's closest approach to the next's, the last on without end; up
	 * to where it's captured, if it is).
	 */
	fun miss(camera: V3, direction: V3, target: V3): V3 {
		var best = Double.MAX_VALUE
		var miss = V3(1e9, 1e9, 1e9)
		propagate(LightLine(camera, coordinate(camera, direction))) { line, from, to ->
			if (to > from) {
				val near = line.at(line.along(target).coerceIn(from, to))
				val m = target - near
				if (m.length < best) { best = m.length; miss = m }
			}
		}
		return miss
	}

	/**
	 * The direction from [camera] whose light passes through [target], solved on the composition itself from [guess]
	 * (Gauss-Newton on [miss], finite differences across the direction); null if it doesn't get within [tolerance] of
	 * the target's distance in [steps] steps.
	 */
	fun solve(camera: V3, target: V3, guess: V3, steps: Int = 8, tolerance: Double = 2e-4, step: Double = 1e-6): V3? {
		var d = guess.normalized()
		val limit = tolerance * maxOf((target - camera).length, 1.0)
		repeat(steps) {
			val r = miss(camera, d, target)
			if (r.length < limit) return d
			val (e1, e2) = tangents(d)
			val j1 = (miss(camera, (d + e1 * step).normalized(), target) - r) * (1.0 / step)
			val j2 = (miss(camera, (d + e2 * step).normalized(), target) - r) * (1.0 / step)
			val a = j1 dot j1
			val b = j1 dot j2
			val c = j2 dot j2
			val det = a * c - b * b
			if (abs(det) < 1e-30) return null
			val g1 = -(j1 dot r)
			val g2 = -(j2 dot r)
			var s1 = (c * g1 - b * g2) / det
			var s2 = (a * g2 - b * g1) / det
			val size = sqrt(s1 * s1 + s2 * s2)
			if (size > 0.1) { s1 *= 0.1 / size; s2 *= 0.1 / size }
			d = (d + e1 * s1 + e2 * s2).normalized()
		}
		return if (miss(camera, d, target).length < limit) d else null
	}

	/** A camera direction as a coordinate tangent: each source's conversion in turn. */
	fun coordinate(camera: V3, d: V3): V3 = modules.fold(d) { v, m -> m.toCoordinate(camera, v) }

	private fun observed(camera: V3, d: V3): V3 = modules.asReversed().fold(d) { v, m -> m.toObserved(camera, v) }

	/**
	 * Light along [start] past every source in turn: the line it ends on, or null if a source captures it. [pieces], if
	 * given, gets each straight piece of the path (its line, and from and to along it).
	 */
	private fun propagate(start: LightLine, pieces: ((LightLine, Double, Double) -> Unit)? = null): LightLine? {
		var pieceStart = start.origin
		var line = start
		val left = modules.toMutableList()
		val passed = ArrayList<LensModule>()
		// The weak fields of [sources] (each outside its own near sphere, which its exact pass covers) on the line up to
		// [until] along: the line the light is on there, turned, and through where they've moved it to (so it arrives
		// where it should, heading as it should).
		fun weakly(until: Double, sources: List<LensModule>): LightLine {
			if (until <= 0.0) return line
			val end = line.at(until)
			var heading = line.direction
			var arrival = end
			for (m in sources) {
				val (turn, move) = fieldOutside(m, line.origin, end)
				heading += turn
				arrival += move
			}
			val h = heading.normalized()
			return LightLine(arrival - h * until, h)
		}
		while (left.isNotEmpty()) {
			val next = left.minBy { line.along(it.centre) }
			// A hole whose near sphere overlaps another's: light there is bent hard by both at once, so the pair (or
			// more) is marched together through their superposed orbit field, from where the light first
			// enters any of their near spheres to where it has left them all.
			val group = coupled(next, left)
			if (group.size > 1 || (forceMarch && next is HoleModule)) {
				val entries = group.map { m ->
					val tc = line.along(m.centre)
					val impact = (line.at(tc) - m.centre).length
					val radius = near(m)
					if (impact < radius) maxOf(tc - sqrt(radius * radius - impact * impact), line.along(start.origin)) else Double.MAX_VALUE
				}
				val entry = entries.min()
				if (entry < Double.MAX_VALUE) {
					val arriving = if (entry > 0.0) weakly(entry, left) else LightLine(line.at(entry), line.direction)
					val from = if (entry > 0.0) arriving.at(entry) else arriving.origin
					pieces?.invoke(arriving, arriving.along(pieceStart), arriving.along(from))
					left.removeAll(group)
					val out = march(group.map { it as HoleModule }, from, arriving.direction, pieces) ?: return null
					// The rest of each one's bend from where the march left off, exactly (heading away from them all,
					// each bends it there much as it would alone); and the tails of those passed before, moved from the
					// old line to the new one.
					var heading = out.direction
					for (m in group.sortedBy { (it.centre - out.origin).length }) {
						heading = (m.pass(LightLine(out.origin, heading.normalized())) ?: return null).direction
					}
					for (m in passed) {
						heading += m.field(out.origin, out.at(FAR)).first
						heading -= m.field(out.origin, out.origin + arriving.direction * FAR).first
					}
					passed += group
					line = LightLine(out.origin, heading.normalized())
					pieceStart = out.origin
					continue
				}
			}
			// This one exactly from where the light enters its near sphere (behind where it is now, if that's inside:
			// started level with an earlier source beside it, its orbit began by its photon sphere, and the weak field
			// standing in before that is no good there: the order the two came in showed, a straight cut through a
			// shadow), and every source's weak field up to there.
			val tc = line.along(next.centre)
			val impact = (line.at(tc) - next.centre).length
			val radius = near(next)
			// (Never back past where the light set out: level with the camera.)
			val entry = if (impact < radius) maxOf(tc - sqrt(radius * radius - impact * impact), line.along(start.origin)) else 0.0
			val arriving = if (entry > 0.0) weakly(entry, left) else LightLine(line.at(entry), line.direction)
			left.remove(next)
			pieces?.invoke(arriving, arriving.along(pieceStart), arriving.along(next.centre))
			val out = next.pass(arriving) ?: return null
			// The sources already passed counted their weak tails along the old line, out to infinity: from the turn on
			// the light runs along the new one instead.
			var heading = out.direction
			for (m in passed) {
				heading += m.field(out.origin, out.at(FAR)).first
				heading -= m.field(out.origin, out.origin + arriving.direction * FAR).first
			}
			passed += next
			line = LightLine(out.origin, heading.normalized())
			pieceStart = line.at(line.along(next.centre))
		}
		pieces?.invoke(line, line.along(pieceStart), Double.MAX_VALUE)
		return line
	}

	/** [first] and every hole in [left] whose near sphere overlaps its, or one that does, and so on (if [first] is a hole). */
	private fun coupled(first: LensModule, left: List<LensModule>): List<LensModule> {
		if (first !is HoleModule) return listOf(first)
		if (forceMarch) return left.filterIsInstance<HoleModule>()
		val group = arrayListOf(first)
		var grew = true
		while (grew) {
			grew = false
			for (m in left) {
				if (m !is HoleModule || m in group) continue
				if (group.any { (it.centre - m.centre).length < near(it) + near(m) }) { group += m; grew = true }
			}
		}
		return group
	}

	/**
	 * Light from [from] heading [direction] (a coordinate tangent) marched through the superposed field of [holes] (each
	 * its photon orbit's, in Cartesian form: -3/2 r_s h^2 x / r^5, h = |x × v|; [OrbitField]'s), RK4, steps [MARCH_STEP] of
	 * the distance to the nearest centre: until it has left every hole's near sphere heading away from each (the line it's on
	 * then), or null if it falls into one (inside its photon sphere heading in). [pieces] gets each step as a piece of the
 * path.
	 */
	private fun march(holes: List<HoleModule>, from: V3, direction: V3, pieces: ((LightLine, Double, Double) -> Unit)?): LightLine? {
		var x = from
		var v = direction.normalized()
		val field = OrbitField(holes, emptyList())
		fun acceleration(x: V3, v: V3) = field.acceleration(x, v)
		repeat(MARCH_LIMIT) {
			var nearest = Double.MAX_VALUE
			var outside = true
			for (h in holes) {
				val d = x - h.centre
				val r = d.length
				// (Inside the photon sphere heading in: falling in, for a hole alone; near another, as good as.)
				if (r <= 1.5 * h.rs && (d dot v) < 0.0) return null
				nearest = minOf(nearest, r)
				if (r < near(h) || (d dot v) <= 0.0) outside = false
			}
			if (outside) return LightLine(x, v.normalized())
			val step = MARCH_STEP * nearest
			// RK4: four field evaluations a step, but fourth order (for a given accuracy far fewer evaluations than
			// velocity-Verlet's second order: FieldLensTest.marchCost).
			val a1 = acceleration(x, v)
			val x2 = x + v * (0.5 * step); val v2 = v + a1 * (0.5 * step)
			val a2 = acceleration(x2, v2)
			val x3 = x + v2 * (0.5 * step); val v3 = v + a2 * (0.5 * step)
			val a3 = acceleration(x3, v3)
			val x4 = x + v3 * step; val v4 = v + a3 * step
			val a4 = acceleration(x4, v4)
			val next = x + (v + v2 * 2.0 + v3 * 2.0 + v4) * (step / 6.0)
			pieces?.invoke(LightLine(x, v.normalized()), 0.0, (next - x).length)
			v += (a1 + a2 * 2.0 + a3 * 2.0 + a4) * (step / 6.0)
			x = next
			marchEvaluations += 4
		}
		return null
	}

	/** How far round [m] its field isn't weak: [NEAR] of its sizes for a hole (its exact pass starts there), none else. */
	private fun near(m: LensModule): Double = if (m is HoleModule) NEAR * m.scale else 0.0

	/**
	 * [m]'s weak field on light running straight from [a] to [b], leaving out the part inside its near sphere: the turn,
	 * and the move at [b] (a turn before the sphere carries the light on round it).
	 */
	private fun fieldOutside(m: LensModule, a: V3, b: V3): Pair<V3, V3> {
		val radius = near(m)
		val span = b - a
		val length = span.length
		if (radius <= 0.0 || length < 1e-12) return m.field(a, b)
		val d = span * (1.0 / length)
		val tc = (m.centre - a) dot d
		val impact = (a + d * tc - m.centre).length
		if (impact >= radius) return m.field(a, b)
		val h = sqrt(radius * radius - impact * impact)
		var turn = V3.ZERO
		var move = V3.ZERO
		if (tc - h > 0.0) {
			val e = minOf(tc - h, length)
			val (t, mv) = m.field(a, a + d * e)
			turn += t
			move += mv + t * (length - e)
		}
		if (tc + h < length) {
			val (t, mv) = m.field(a + d * maxOf(tc + h, 0.0), b)
			turn += t
			move += mv
		}
		return turn to move
	}

	companion object {
		/** A hole's near sphere (its sizes): where its weak field stops standing in for its orbit. */
		const val NEAR = 20.0

		/** The march's step, of the distance to the nearest hole's centre, and its most steps (light still inside is lost). */
		const val MARCH_STEP = 0.2
		const val MARCH_LIMIT = 2000

		/** Debug (tests): field evaluations the march has made in all. */
		@JvmStatic
		var marchEvaluations = 0L

		/** Debug (tests): every hole marched, even alone. */
		@JvmStatic
		var forceMarch = false

		/** "Out past everything" (blocks): weak fields' remaining turn beyond is negligible. */
		private const val FAR = 1e8

		/** Two unit vectors perpendicular to unit [d] and each other. */
		fun tangents(d: V3): Pair<V3, V3> {
			val helper = if (abs(d.y) < 0.9) V3(0.0, 1.0, 0.0) else V3(1.0, 0.0, 0.0)
			val e1 = (d cross helper).normalized()
			return e1 to (d cross e1)
		}

		/**
		 * Where light coming along [incoming] and leaving along [outgoing] turned: the point of [outgoing] nearest
		 * [incoming], no earlier than [incoming]'s origin (light doesn't turn before it sets out); [outgoing]'s origin if
		 * they're parallel.
		 */
		fun meet(incoming: LightLine, outgoing: LightLine): V3 {
			val w = outgoing.origin - incoming.origin
			val b = incoming.direction dot outgoing.direction
			val denom = 1.0 - b * b
			if (denom < 1e-12) return outgoing.origin
			val d = incoming.direction dot w
			val e = outgoing.direction dot w
			val s = (d - b * e) / denom
			val t = (b * d - e) / denom
			return if (s < 0.0) outgoing.at(outgoing.along(incoming.origin)) else outgoing.at(t)
		}

		/**
		 * The weak field of a mass [mass] at [centre] softened by [softening] (Plummer: over a whole line it turns light
		 * by 4 M b / (b^2 + softening^2)) on light running straight from [a] to [b]: the turn of its direction, and its
		 * move at [b] (both toward the mass).
		 */
		fun pointField(centre: V3, mass: Double, softening: Double, a: V3, b: V3): Pair<V3, V3> {
			val span = b - a
			val length = span.length
			if (length < 1e-12) return V3.ZERO to V3.ZERO
			val d = span * (1.0 / length)
			val tc = (centre - a) dot d
			val offset = a + d * tc - centre
			val impact = offset.length
			if (impact < 1e-12) return V3.ZERO to V3.ZERO
			val q = impact * impact + softening * softening
			val k = 2.0 * mass * impact
			// Along u = t - tc over [-tc, length - tc]; the move at b: the turn at each point times the way left to b.
			fun turnAt(u: Double) = k * u / (q * sqrt(u * u + q))
			fun moveAt(u: Double) = k * ((length - tc) * u / (q * sqrt(u * u + q)) + 1.0 / sqrt(u * u + q))
			val turn = turnAt(length - tc) - turnAt(-tc)
			val move = moveAt(length - tc) - moveAt(-tc)
			val toward = offset * (-1.0 / impact)
			return toward * turn to toward * move
		}
	}
}
