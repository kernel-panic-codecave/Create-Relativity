package net.kernelpanicsoft.relativity.physics.lens.field

import kotlin.math.sqrt

/**
 * The lens's model of light through several sources, followed finely (the reference [FieldLens] is measured against):
 * the superposed orbit field. Each hole bends light as its own photon orbit does (Cartesian form of u'' + u = 3/2 r_s u^2:
 * -3/2 r_s h^2 x / r^5, h = |x × v|), exact for a hole alone; each mass weakly, twice its Newtonian pull across the light
 * (Plummer-softened: over a whole line 4 m b / (b^2 + s^2), as [MassModule]); added. Positions and directions are
 * coordinates (a hole's Schwarzschild ones).
 */
class OrbitField(val holes: List<HoleModule>, val masses: List<MassModule>) {
	constructor(modules: List<LensModule>) : this(modules.filterIsInstance<HoleModule>(), modules.filterIsInstance<MassModule>())

	/** Where the light is (x) heading (v, any length): its acceleration. */
	fun acceleration(x: V3, v: V3): V3 {
		var a = V3.ZERO
		for (h in holes) {
			val d = x - h.centre
			val r2 = d dot d
			val hv = d cross v
			a += d * (-1.5 * h.rs * (hv dot hv) / (r2 * r2 * sqrt(r2)))
		}
		if (masses.isNotEmpty()) {
			val speed2 = v dot v
			val u = v * (1.0 / sqrt(speed2))
			for (m in masses) {
				val d = x - m.centre
				val across = d - u * (d dot u)
				val q = (d dot d) + m.softening * m.softening
				a += across * (-2.0 * m.mass * speed2 / (q * sqrt(q)))
			}
		}
		return a
	}

	/** Light followed: where it ends heading (null if a hole captures it), and its path's points as it went. */
	class Traced(val escape: V3?, val points: List<V3>)

	/**
	 * Light leaving [from] along coordinate direction [direction], followed (RK4, steps [tolerance] of the distance to
	 * the nearest source, at most [maxStep]) until a hole captures it or it's [reach] past every source heading away.
	 * [record]: keep its path's points (every [recordEvery] blocks or so).
	 */
	fun trace(from: V3, direction: V3, tolerance: Double = 0.002, maxStep: Double = 0.5, reach: Double = 1e5, record: Boolean = false, recordEvery: Double = 0.2): Traced {
		var x = from
		var v = direction.normalized()
		val points = ArrayList<V3>()
		if (record) points += x
		var sinceRecord = 0.0
		val centres = holes.map { it.centre } + masses.map { it.centre }
		while (true) {
			var nearest = Double.MAX_VALUE
			var away = true
			for (h in holes) {
				val d = x - h.centre
				val r = d.length
				if (r <= h.rs) return Traced(null, points)
				nearest = minOf(nearest, r - h.rs)
			}
			for (c in centres) {
				val d = x - c
				nearest = minOf(nearest, d.length)
				if (d.length < reach || (d dot v) <= 0.0) away = false
			}
			if (away || centres.isEmpty() || (x - from).length > 2.0 * reach) return Traced(v.normalized(), points.also { if (record) it += x })
			val step = minOf(tolerance * nearest.coerceAtLeast(1e-3), maxStep * maxOf(1.0, nearest / 50.0))
			// RK4 on (x, v).
			val a1 = acceleration(x, v)
			val x2 = x + v * (0.5 * step); val v2 = v + a1 * (0.5 * step)
			val a2 = acceleration(x2, v2)
			val x3 = x + v2 * (0.5 * step); val v3 = v + a2 * (0.5 * step)
			val a3 = acceleration(x3, v3)
			val x4 = x + v3 * step; val v4 = v + a3 * step
			val a4 = acceleration(x4, v4)
			x += (v + v2 * 2.0 + v3 * 2.0 + v4) * (step / 6.0)
			v += (a1 + a2 * 2.0 + a3 * 2.0 + a4) * (step / 6.0)
			if (record) {
				sinceRecord += step
				if (sinceRecord >= recordEvery) { points += x; sinceRecord = 0.0 }
			}
		}
	}
}
