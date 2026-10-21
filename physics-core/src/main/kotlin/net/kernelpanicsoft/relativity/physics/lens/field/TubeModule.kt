package net.kernelpanicsoft.relativity.physics.lens.field

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A tube-shaped field of radius [radius] round the polyline [spine], uniform inside ([interior]: a warp tube's push, a
 * Krasnikov tube's tipped light cones), its walls thin. Light runs straight everywhere but the walls (and the joints
 * between segments, where the field turns with the spine), and at a wall keeps what the wall can't change: its energy
 * at infinity (p_t: the field is stationary) and its momentum along the wall; the momentum across it is what makes it
 * null on the other side. So a wall refracts like the face of a moving medium, or reflects (no such momentum); light the
 * interior won't let out (a warp tube faster than light: the light ahead of a rider) can't leave at all: a horizon,
 * captured.
 *
 * Directions of light inside are the ones the interior's observers see ([Interior.start]), throughout ([LensModule]'s
 * lines, [aim]'s answer), not coordinate tangents: faster than light, those can't tell light apart.
 */
class TubeModule(val spine: List<V3>, val interior: Interior, val radius: Double) : LensModule {
	/** A warp tube ([net.kernelpanicsoft.relativity.physics.spacetime.field.WarpTube]) pushing at [speed] times c. */
	constructor(spine: List<V3>, speed: Double, radius: Double) : this(spine, Interior.Warp(speed), radius)

	/**
	 * The inside's inverse metric along the spine's direction t̂: g^tt = [a], g^ts = [b], g^ss = [c] (s along t̂), flat
	 * across; and who looks out from inside.
	 */
	sealed class Interior(val a: Double, val b: Double, val c: Double) {
		/** Light seen going along unit [m] (by this interior's observers, spine direction [t]): its momentum and p_t. */
		abstract fun start(m: V3, t: V3): Pair<V3, Double>

		/**
		 * A warp tube's push at [speed] times c along t̂ ([net.kernelpanicsoft.relativity.physics.spacetime.field.WarpTube]):
		 * its observers ride it (Eulerian: time and space flat for them), light's coordinate velocity m̂ + v t̂.
		 */
		class Warp(val speed: Double) : Interior(-1.0, -speed, 1.0 - speed * speed) {
			override fun start(m: V3, t: V3): Pair<V3, Double> = m to (-1.0 - speed * (t dot m))
		}

		/**
		 * A Krasnikov tube's inside ([net.kernelpanicsoft.relativity.physics.spacetime.field.KrasnikovTube]), k in (0, 1]:
		 * light runs along t̂ at 1 / k, back at 1. Its observers stand still (g_tt is −1: they always can).
		 */
		class Krasnikov(val k: Double) : Interior(-4 * k / ((1 + k) * (1 + k)), -2 * (1 - k) / ((1 + k) * (1 + k)), 4 / ((1 + k) * (1 + k))) {
			override fun start(m: V3, t: V3): Pair<V3, Double> {
				// The static observer's frame: along t̂ the unit vector orthogonal to ∂_t is (g_ts, t̂) 2 / (1 + k).
				val gts = -(1 - k) / 2
				val ms = m dot t
				val kt = 1 + ms * gts * 2 / (1 + k)
				val ks = ms * 2 / (1 + k)
				val pt = -kt + gts * ks
				val ps = gts * kt + k * ks
				return (m - t * ms + t * ps) to pt
			}
		}

		/** Flat: outside. */
		object Flat : Interior(-1.0, 0.0, 1.0) {
			override fun start(m: V3, t: V3): Pair<V3, Double> = m to -1.0
		}
	}
	init {
		require(spine.size >= 2) { "the spine needs at least two points" }
	}

	private val segments = spine.size - 1
	private val directions: List<V3> = (0 until segments).map { (spine[it + 1] - spine[it]).normalized() }

	/** Between segments k and k + 1: the plane through their joint halving the turn, its normal toward k + 1. */
	private val joints: List<V3> = (0 until segments - 1).map { (directions[it] + directions[it + 1]).normalized() }

	override val centre: V3 = spine.fold(V3.ZERO) { s, p -> s + p } * (1.0 / spine.size)
	override val scale: Double get() = radius

	/** Light's state: where, its momentum p (spatial, covariant) and energy at infinity p_t, and its region (-1: outside). */
	private class Ray(var x: V3, var p: V3, val pt: Double, var region: Int)

	private fun interiorOf(region: Int): Interior = if (region < 0) Interior.Flat else interior

	private fun axisOf(region: Int): V3 = if (region < 0) V3(1.0, 0.0, 0.0) else directions[region]

	/** dt/dλ: g^tt p_t + g^ts p_s (light's is positive: future-directed). */
	private fun tDot(pt: Double, p: V3, region: Int): Double {
		val f = interiorOf(region)
		return f.a * pt + f.b * (p dot axisOf(region))
	}

	/** Coordinate velocity per unit coordinate time: (p + ((g^ss − 1) p_s + g^ts p_t) t̂) / ṫ. */
	private fun velocity(r: Ray): V3 {
		val f = interiorOf(r.region)
		val t = axisOf(r.region)
		val ps = r.p dot t
		return (r.p + t * ((f.c - 1) * ps + f.b * r.pt)) * (1.0 / tDot(r.pt, r.p, r.region))
	}

	/** The nearest segment within the tube's radius of [x] (thin walls), or -1. */
	private fun regionOf(x: V3): Int {
		var best = -1
		var bestD = radius
		for (k in 0 until segments) {
			val d = distanceToSegment(x, k)
			if (d < bestD) { bestD = d; best = k }
		}
		if (best < 0) return -1
		// (Across a joint's plane, the next segment's.)
		var k = best
		while (k < segments - 1 && ((x - spine[k + 1]) dot joints[k]) > 0) k++
		while (k > 0 && ((x - spine[k]) dot joints[k - 1]) < 0) k--
		return k
	}

	private fun closestOnSegment(x: V3, k: Int): V3 {
		val a = spine[k]
		val d = spine[k + 1] - a
		val l2 = d dot d
		val u = if (l2 > 0) (((x - a) dot d) / l2).coerceIn(0.0, 1.0) else 0.0
		return a + d * u
	}

	private fun distanceToSegment(x: V3, k: Int): Double = (x - closestOnSegment(x, k)).length

	/** Where the line x + w t is within the capsule round segment k: [t0, t1] (it's convex), or null. */
	private fun capsule(x: V3, w: V3, k: Int): Pair<Double, Double>? {
		val a = spine[k]
		val b = spine[k + 1]
		var lo = Double.MAX_VALUE
		var hi = -Double.MAX_VALUE
		fun include(t0: Double, t1: Double) {
			if (t1 < t0) return
			lo = minOf(lo, t0); hi = maxOf(hi, t1)
		}
		fun sphere(c: V3) {
			val o = x - c
			val qa = w dot w
			val qb = 2 * (o dot w)
			val qc = (o dot o) - radius * radius
			val disc = qb * qb - 4 * qa * qc
			if (disc < 0) return
			val s = sqrt(disc)
			include((-qb - s) / (2 * qa), (-qb + s) / (2 * qa))
		}
		sphere(a)
		sphere(b)
		// The cylinder's side, within the slab between the ends.
		val axis = (b - a).normalized()
		val length = (b - a).length
		val o = x - a
		val wp = w - axis * (w dot axis)
		val op = o - axis * (o dot axis)
		val qa = wp dot wp
		var t0: Double
		var t1: Double
		if (qa < 1e-14) {
			if ((op dot op) > radius * radius) t0 = 1.0.also { t1 = 0.0 } else { t0 = -Double.MAX_VALUE; t1 = Double.MAX_VALUE }
		} else {
			val qb = 2 * (op dot wp)
			val qc = (op dot op) - radius * radius
			val disc = qb * qb - 4 * qa * qc
			if (disc < 0) { t0 = 1.0; t1 = 0.0 } else {
				val s = sqrt(disc)
				t0 = (-qb - s) / (2 * qa); t1 = (-qb + s) / (2 * qa)
			}
		}
		if (t0 <= t1) {
			val wa = w dot axis
			val oa = o dot axis
			if (abs(wa) < 1e-14) {
				if (oa < 0 || oa > length) t0 = 1.0.also { t1 = 0.0 }
			} else {
				val s0 = (0 - oa) / wa
				val s1 = (length - oa) / wa
				t0 = maxOf(t0, minOf(s0, s1)); t1 = minOf(t1, maxOf(s0, s1))
			}
			include(t0, t1)
		}
		return if (lo <= hi) lo to hi else null
	}

	/**
	 * The momentum across a wall of normal [n] for light of energy at infinity [pt] and momentum [pT] along it, in
	 * [region], going along n ([sense] 1) or against it (-1): null if there's none (or none with positive energy).
	 */
	private fun solve(pt: Double, pT: V3, n: V3, region: Int, sense: Double): Double? {
		val f = interiorOf(region)
		val t = axisOf(region)
		// p = pT + q n, p_s = a0 + b0 q; null: g^tt p_t² + 2 g^ts p_t p_s + g^ss p_s² + |p|² − p_s² = 0.
		val a0 = pT dot t
		val b0 = n dot t
		val qa = 1 + (f.c - 1) * b0 * b0
		val qb = 2 * (f.c - 1) * a0 * b0 + 2 * f.b * pt * b0
		val qc = (f.c - 1) * a0 * a0 + 2 * f.b * pt * a0 + f.a * pt * pt + (pT dot pT)
		val roots = if (abs(qa) < 1e-12) (if (abs(qb) < 1e-14) emptyList() else listOf(-qc / qb)) else {
			val disc = qb * qb - 4 * qa * qc
			if (disc < 0) return null
			val s = sqrt(disc)
			listOf((-qb - s) / (2 * qa), (-qb + s) / (2 * qa))
		}
		return roots.filter { q ->
			val p = pT + n * q
			val td = tDot(pt, p, region)
			// Future-directed, and going the right way across (its velocity's normal part).
			val ps = p dot t
			val vn = (q + (n dot t) * ((f.c - 1) * ps + f.b * pt)) / td
			td > 1e-12 && vn * sense > 0
		}.minByOrNull { abs(it) }
	}

	override fun pass(line: LightLine): LightLine? {
		val walk = Walk(start(line.origin, line.direction))
		repeat(MAX_EVENTS) {
			val w = velocity(walk.ray)
			when (walk.step()) {
				null -> return null
				true -> return LightLine(walk.ray.x, w.normalized())
				false -> {}
			}
		}
		// (Trapped: bouncing inside, a fibre.)
		return null
	}

	/** Light at [x] heading along [d] (inside, the way the interior's observers see it): its state. */
	private fun start(x: V3, d: V3): Ray {
		val region = regionOf(x)
		if (region < 0) return Ray(x, d, -1.0, -1)
		val (p, pt) = interior.start(d, directions[region])
		return Ray(x, p, pt, region)
	}

	override fun aim(from: V3, target: V3, secondary: Boolean): V3? {
		if (secondary) return null
		val straight = (target - from).normalized()
		if (regionOf(from) < 0 && regionOf(target) < 0 && (0 until segments).none { k -> capsule(from, target - from, k)?.let { (t0, t1) -> t1 > 0 && t0 < 1 } == true }) {
			return straight
		}
		// Gauss-Newton on the traced path's miss at the target, over two angles round the straight line; each step
		// halved until it misses by less (the miss has kinks where light starts or stops crossing a wall).
		val (e1, e2) = FieldLens.tangents(straight)
		fun direction(a: Double, b: Double) = (straight + e1 * a + e2 * b).normalized()
		fun miss(a: Double, b: Double): V3? = trace(from, direction(a, b), target)
		val distance = (target - from).length
		// Seeded from the best of a coarse grid over the tube's size as seen from here: refraction moves images a long
		// way, and the miss is too rough for the solver to find them from the straight line.
		val reachAngle = kotlin.math.atan2(3.0 * radius, maxOf((target - from).length, radius)).coerceAtMost(0.6)
		var a = 0.0
		var b = 0.0
		var r: V3 = miss(a, b) ?: V3(1e9, 0.0, 0.0)
		for (i in -SEEDS..SEEDS) for (j in -SEEDS..SEEDS) {
			val sa = reachAngle * i / SEEDS
			val sb = reachAngle * j / SEEDS
			val m = miss(sa, sb) ?: continue
			if (m.length < r.length) { a = sa; b = sb; r = m }
		}
		if (r.length >= 1e9) return null
		repeat(30) {
			if (r.length < 1e-6 * maxOf(distance, 1.0)) return direction(a, b)
			val h = 1e-6
			val ja = ((miss(a + h, b) ?: return@repeat) - r) * (1.0 / h)
			val jb = ((miss(a, b + h) ?: return@repeat) - r) * (1.0 / h)
			// Least squares: [ja jb] [da db] = −r.
			val aa = ja dot ja
			val ab = ja dot jb
			val bb = jb dot jb
			val det = aa * bb - ab * ab
			if (abs(det) < 1e-30) return@repeat
			val ra = -(ja dot r)
			val rb = -(jb dot r)
			var da = (bb * ra - ab * rb) / det
			var db = (aa * rb - ab * ra) / det
			var stepped = false
			for (halving in 0 until 12) {
				val next = miss(a + da, b + db)
				if (next != null && next.length < r.length) {
					a += da; b += db; r = next
					stepped = true
					break
				}
				da *= 0.5; db *= 0.5
			}
			if (!stepped) return@repeat
		}
		// (Not reached exactly: the nearest it came, if near; otherwise no light from here reaches it: hidden.)
		return if (r.length < 0.02 * maxOf(distance, 1.0)) direction(a, b) else null
	}

	/**
	 * How far the light leaving [from] along [d] misses [target]: the target less the nearest point of its path (the
	 * straight pieces between walls), or null if it's captured.
	 */
	internal fun missAt(from: V3, d: V3, target: V3): V3? = trace(from, d, target)

	private fun trace(from: V3, d: V3, target: V3): V3? {
		var best: V3? = null
		var bestD = Double.MAX_VALUE
		fun piece(a: V3, w: V3, length: Double) {
			val wn = w.normalized()
			val p = a + wn * ((target - a) dot wn).coerceIn(0.0, length)
			val dd = (target - p).length
			if (dd < bestD) { bestD = dd; best = target - p }
		}
		val walk = Walk(start(from, d))
		repeat(MAX_EVENTS) {
			val a = walk.ray.x
			val w = velocity(walk.ray)
			when (walk.step()) {
				null -> return null
				true -> { piece(a, w, Double.MAX_VALUE); return best }
				false -> piece(a, w, (walk.ray.x - a).length)
			}
		}
		return best
	}

	/** Light's walk through the tube, a wall (or joint) at a time. */
	private inner class Walk(val ray: Ray) {
		/** Set when the light can't go on (a horizon). */
		var captured = false

		/** On to the next wall: true once outside for good (left where it last crossed), null if captured. */
		fun step(): Boolean? {
			val w = velocity(ray)
			if (ray.region < 0) {
				// Outside: the first capsule it enters.
				var best = Double.MAX_VALUE
				var bestK = -1
				for (k in 0 until segments) {
					val (t0, t1) = capsule(ray.x, w, k) ?: continue
					if (t0 > EPS && t0 < best && t1 > t0) { best = t0; bestK = k }
				}
				if (bestK < 0) return true
				ray.x = ray.x + w * best
				val into = regionOf(ray.x + w * EPS).takeIf { it >= 0 } ?: bestK
				cross(into, (closestOnSegment(ray.x, bestK) - ray.x).normalized())
				return if (captured) null else false
			}
			// Inside segment k: out of its capsule, or across a joint into the next.
			val k = ray.region
			val (_, exit) = capsule(ray.x, w, k) ?: (0.0 to 0.0)
			var event = maxOf(exit, 0.0)
			var next = -1
			var normal = V3.ZERO
			if (k < segments - 1) {
				val m = joints[k]
				val wm = w dot m
				if (wm > 1e-14) {
					val t = ((spine[k + 1] - ray.x) dot m) / wm
					if (t > -EPS && t < event) { event = maxOf(t, 0.0); next = k + 1; normal = m }
				}
			}
			if (k > 0) {
				val m = joints[k - 1]
				val wm = w dot m
				if (wm < -1e-14) {
					val t = ((spine[k] - ray.x) dot m) / wm
					if (t > -EPS && t < event) { event = maxOf(t, 0.0); next = k - 1; normal = -m }
				}
			}
			ray.x = ray.x + w * event
			if (next >= 0) cross(next, normal)
			else {
				val outward = (ray.x - closestOnSegment(ray.x, k)).normalized()
				val other = regionOf(ray.x + w * EPS)
				if (other >= 0 && other != k) cross(other, outward)
				// (Reflected: stepped back inside, off the wall.)
				else if (!cross(-1, outward) && !captured) ray.x = ray.x - outward * EPS
			}
			return if (captured) null else false
		}

		/**
		 * Crosses into [into] at a wall of normal [n] (unit, the way it goes), keeping p's part along the wall and p_t
		 * and solving for the part across. False if it can't cross: reflected, or if not even that, [captured].
		 */
		fun cross(into: Int, n: V3): Boolean {
			val pT = ray.p - n * (ray.p dot n)
			solve(ray.pt, pT, n, into, 1.0)?.let { q ->
				ray.p = pT + n * q
				ray.region = into
				return true
			}
			solve(ray.pt, pT, n, ray.region, -1.0)?.let { q ->
				ray.p = pT + n * q
				return false
			}
			captured = true
			return false
		}
	}

	override fun field(a: V3, b: V3): Pair<V3, V3> = V3.ZERO to V3.ZERO

	// Inside, light's direction is the one the interior's observers see: faster than light, two of them can share a
	// coordinate direction (one a warp rider sends back still runs forward), so a coordinate direction can't tell them
	// apart. Outside they're the same. So no conversion either way.
	override fun toCoordinate(at: V3, d: V3): V3 = d

	override fun toObserved(at: V3, d: V3): V3 = d

	companion object {
		private const val EPS = 1e-7
		private const val MAX_EVENTS = 64

		/** The aim's seed grid: this many each side of the straight line, each way. */
		private const val SEEDS = 4
	}
}
