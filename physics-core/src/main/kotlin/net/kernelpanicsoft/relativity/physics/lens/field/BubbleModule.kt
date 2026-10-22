package net.kernelpanicsoft.relativity.physics.lens.field

import kotlin.math.sqrt

/**
 * A warp bubble ([net.kernelpanicsoft.relativity.physics.spacetime.field.WarpEllipsoid]) as the lens takes it: frozen
 * where it is (in the game's coordinates at an instant, a bubble standing still: exact for one that does), so a uniform
 * [interior] (a warp push along unit [n]) inside a thin ellipsoidal wall centred at [centre] with semi-axis vectors
 * [axes], flat outside. Light runs straight but at the wall, which refracts or reflects it as a tube's wall does
 * ([TubeModule]); light the inside won't let out is captured. Directions inside are its observers' (riders').
 */
class BubbleModule(override val centre: V3, val axes: List<V3>, val n: V3, val interior: TubeModule.Interior) : LensModule {
	init {
		require(axes.size == 3) { "three semi-axes" }
	}

	override val scale: Double = axes.maxOf { it.length }

	/** Each axis over its length squared: a point's coordinate along it, in semi-axes, is the dot. */
	private val scaled = axes.map { it * (1.0 / (it dot it)) }

	/** Light's state: where, its momentum and energy at infinity, and whether it's inside. */
	private class Ray(var x: V3, var p: V3, val pt: Double, var inside: Boolean)

	private fun interiorOf(inside: Boolean) = if (inside) interior else TubeModule.Interior.Flat

	private fun velocity(r: Ray): V3 = interiorOf(r.inside).velocity(r.p, r.pt, n)

	/** The point's coordinates in semi-axes (1 on the wall at its length). */
	private fun local(x: V3): V3 {
		val r = x - centre
		return V3(r dot scaled[0], r dot scaled[1], r dot scaled[2])
	}

	fun contains(x: V3): Boolean = local(x).length < 1.0

	/** The wall's outward normal at (or by) [x]. */
	private fun normal(x: V3): V3 {
		val u = local(x)
		return (scaled[0] * u.x + scaled[1] * u.y + scaled[2] * u.z).normalized()
	}

	/** Where the line x + w t is inside the ellipsoid: (t0, t1), or null. */
	private fun span(x: V3, w: V3): Pair<Double, Double>? {
		val u = local(x)
		val du = V3(w dot scaled[0], w dot scaled[1], w dot scaled[2])
		val qa = du dot du
		if (qa < 1e-30) return null
		val qb = 2 * (u dot du)
		val qc = (u dot u) - 1
		val disc = qb * qb - 4 * qa * qc
		if (disc < 0) return null
		val s = sqrt(disc)
		return (-qb - s) / (2 * qa) to (-qb + s) / (2 * qa)
	}

	private fun start(x: V3, d: V3): Ray {
		if (!contains(x)) return Ray(x, d, -1.0, false)
		val (p, pt) = interior.start(d, n)
		return Ray(x, p, pt, true)
	}

	/** On to the wall: true once outside for good, null if captured, false still going. */
	private fun step(r: Ray): Boolean? {
		val w = velocity(r)
		val (t0, t1) = span(r.x, w) ?: (0.0 to 0.0)
		if (!r.inside) {
			if (t1 <= EPS || t0 <= EPS) return true
			r.x = r.x + w * t0
			// (In, or reflected off the outside: on either way.)
			cross(r, true, -normal(r.x)) ?: return null
			return false
		}
		r.x = r.x + w * maxOf(t1, 0.0)
		val out = normal(r.x)
		val crossed = cross(r, false, out) ?: return null
		// (Reflected: stepped back inside, off the wall.)
		if (!crossed) r.x = r.x - out * EPS
		return false
	}

	/** Across the wall (normal [n], the way the light goes) into [inside]: true crossed, false reflected, null captured. */
	private fun cross(r: Ray, inside: Boolean, nWall: V3): Boolean? {
		val pT = r.p - nWall * (r.p dot nWall)
		interiorOf(inside).across(r.pt, pT, nWall, n, 1.0)?.let { q ->
			r.p = pT + nWall * q
			r.inside = inside
			return true
		}
		interiorOf(r.inside).across(r.pt, pT, nWall, n, -1.0)?.let { q ->
			r.p = pT + nWall * q
			return false
		}
		return null
	}

	override fun pass(line: LightLine): LightLine? {
		val r = start(line.origin, line.direction)
		repeat(MAX_EVENTS) {
			val w = velocity(r)
			when (step(r)) {
				null -> return null
				true -> return LightLine(r.x, w.normalized())
				false -> {}
			}
		}
		return null
	}

	/** The path of light leaving [from] along [d]: the points where it turned, then on out to [length]; null if captured. */
	fun path(from: V3, d: V3, length: Double): List<V3>? {
		val points = arrayListOf(from)
		val r = start(from, d)
		repeat(MAX_EVENTS) {
			val w = velocity(r)
			when (step(r)) {
				null -> return null
				true -> { points += r.x + w.normalized() * length; return points }
				false -> points += r.x
			}
		}
		return null
	}

	/**
	 * How far light leaving [from] along [d] misses [target]: the target less its path's nearest point (up to where it's
	 * captured, if it is: what's seen is the target's light, wherever it goes after); null only if captured at once.
	 */
	fun missAt(from: V3, d: V3, target: V3): V3? {
		var best: V3? = null
		var bestD = Double.MAX_VALUE
		val r = start(from, d)
		repeat(MAX_EVENTS) {
			val a = r.x
			val w = velocity(r).normalized()
			val outcome = step(r)
			val length = if (outcome == true) Double.MAX_VALUE else (r.x - a).length
			val p = a + w * ((target - a) dot w).coerceIn(0.0, length)
			if ((target - p).length < bestD) { bestD = (target - p).length; best = target - p }
			if (outcome != false) return best
		}
		return best
	}

	override fun aim(from: V3, target: V3, secondary: Boolean): V3? = null

	override fun field(a: V3, b: V3): Pair<V3, V3> = V3.ZERO to V3.ZERO

	companion object {
		private const val EPS = 1e-7
		private const val MAX_EVENTS = 32
	}
}
