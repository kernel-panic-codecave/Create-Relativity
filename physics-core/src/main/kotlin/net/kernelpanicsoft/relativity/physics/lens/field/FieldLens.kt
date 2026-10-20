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
 * marching, no tables built per frame. [sky] follows light from the camera out; [image] finds where a point is seen
 * (the vertex lens: the inverse, by Newton's method on the composition, with the source the point is nearest aiming
 * the last stretch exactly). The camera is a static observer; its directions are its own.
 *
 * Sources are passed in the order the light meets them. Each acts exactly from the stretch where the light reaches it
 * ([LensModule.pass], out to infinity along that line), and weakly ([LensModule.field]) on every stretch before, as the
 * light goes: so a source farther on still sees, and a strong one magnifies, what a weak one did first. When a later
 * source turns the light, each source already passed has its weak tail moved from the old line to the new one.
 */
class FieldLens(val modules: List<LensModule>) {
	/**
	 * Light leaving [camera] along unit [direction] (as the camera sees it): the line it ends on once past every source,
	 * or null if a source captures it.
	 */
	fun sky(camera: V3, direction: V3): LightLine? = propagate(LightLine(camera, coordinate(camera, direction)), null, null)

	/**
	 * The direction from [camera] in which [target] is seen (primary image, or [secondary]), or null if it isn't.
	 */
	fun image(camera: V3, target: V3, secondary: Boolean = false): V3? {
		// (A source that doesn't aim, a weak one, is passed like any other; the target is then found by where the light
		// comes level with it.)
		val seed = modules.minByOrNull { (target - it.centre).length / it.scale }?.let { it to it.aim(camera, target, secondary) }
		val terminal = seed?.takeIf { it.second != null }?.first
		val distance = (target - camera).length
		// For a launch tangent: what the light heads along where the terminal source takes over (its last stretch),
		// against what the terminal source wants there; or, where it doesn't aim, where the light comes level with the
		// target against it.
		fun miss(direction: V3): V3? {
			val line = propagate(LightLine(camera, direction), target, terminal) ?: return null
			val wanted = terminal?.aim(line.origin, target, secondary)
			if (wanted != null) return line.direction - wanted
			if (secondary) return null
			return (line.at(line.along(target)) - target) * (1.0 / distance)
		}
		var direction = seed?.second ?: (target - camera).normalized()
		var solved = false
		for (round in 0 until NEWTON_ROUNDS) {
			val r = miss(direction) ?: return null
			if (r.length < TOLERANCE) { solved = true; break }
			val (e1, e2) = tangents(direction)
			val r1 = miss((direction + e1 * STEP).normalized()) ?: return null
			val r2 = miss((direction + e2 * STEP).normalized()) ?: return null
			val j1 = (r1 - r) * (1.0 / STEP)
			val j2 = (r2 - r) * (1.0 / STEP)
			// Least squares: [j1 j2] (a, b) = -r.
			val a11 = j1 dot j1
			val a12 = j1 dot j2
			val a22 = j2 dot j2
			val b1 = -(j1 dot r)
			val b2 = -(j2 dot r)
			val det = a11 * a22 - a12 * a12
			if (abs(det) < 1e-30) return null
			direction = (direction + e1 * ((b1 * a22 - b2 * a12) / det) + e2 * ((a11 * b2 - a12 * b1) / det)).normalized()
		}
		if (!solved && (miss(direction)?.length ?: 1.0) >= CONVERGED) return null
		return observed(camera, direction)
	}

	/** A camera direction as a coordinate tangent: each source's conversion in turn. */
	private fun coordinate(camera: V3, d: V3): V3 = modules.fold(d) { v, m -> m.toCoordinate(camera, v) }

	private fun observed(camera: V3, d: V3): V3 = modules.asReversed().fold(d) { v, m -> m.toObserved(camera, v) }

	/**
	 * Light along [start] past every source in turn: the line it ends on. With [stopAt], only the sources it meets
	 * before coming level with it, and the line from its last turn with the weak fields of the rest up to there; with
	 * [terminal] too, only those met before it, that source not passed (the caller aims from there through it) and its
	 * weak field not counted on the last stretch. Null if a source captures it.
	 */
	private fun propagate(start: LightLine, stopAt: V3?, terminal: LensModule?): LightLine? {
		var line = start
		val left = modules.filter { it !== terminal }.toMutableList()
		val passed = ArrayList<LensModule>()
		// The weak fields of the sources not yet passed (and [terminal], if [withTerminal]) on the line up to [until]
		// along: the line the light is on there, turned, and through where they've moved it to (so it arrives where
		// it should, heading as it should; turning the whole stretch about its start would move it by the full turn
		// times the stretch, not by what the turn on the way adds up to).
		fun weakly(until: Double, withTerminal: Boolean): LightLine {
			if (until <= 0.0) return line
			val end = line.at(until)
			var heading = line.direction
			var arrival = end
			fun add(m: LensModule) {
				val (turn, move) = m.field(line.origin, end)
				heading += turn
				arrival += move
			}
			for (m in left) add(m)
			if (withTerminal && terminal != null) add(terminal)
			val h = heading.normalized()
			return LightLine(arrival - h * until, h)
		}
		while (left.isNotEmpty()) {
			val next = left.minBy { line.along(it.centre) }
			if (stopAt != null && line.along(next.centre) > line.along(stopAt)) break
			// (Nor any past the terminal source: the light meets it first, and its aim from here covers the way to the
			// target; those beyond act on that last stretch weakly.)
			if (terminal != null && line.along(next.centre) > line.along(terminal.centre)) break
			left.remove(next)
			// The other sources' fields up to level with this one, then this one exactly from there on.
			val arriving = weakly(line.along(next.centre), true)
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
		// (The last stretch without the terminal source's field: its aim from the stretch's start covers it.)
		if (stopAt != null) line = weakly(line.along(stopAt), false)
		return line
	}

	companion object {
		private const val NEWTON_ROUNDS = 10
		private const val STEP = 1e-6
		private const val TOLERANCE = 1e-10
		private const val CONVERGED = 1e-6
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
