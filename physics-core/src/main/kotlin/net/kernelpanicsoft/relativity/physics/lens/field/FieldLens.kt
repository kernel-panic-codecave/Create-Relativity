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

	/** A camera direction as a coordinate tangent: each source's conversion in turn. */
	private fun coordinate(camera: V3, d: V3): V3 = modules.fold(d) { v, m -> m.toCoordinate(camera, v) }

	private fun observed(camera: V3, d: V3): V3 = modules.asReversed().fold(d) { v, m -> m.toObserved(camera, v) }

	/** Light along [start] past every source in turn: the line it ends on, or null if a source captures it. */
	private fun propagate(start: LightLine): LightLine? {
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
		}
		return line
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
