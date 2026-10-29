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
class TubeModule(
	val spine: List<V3>,
	val interior: Interior,
	val radius: Double,
	/** Each segment's own interior, where they differ ([interior] otherwise): a tube whose tilt changes along it. */
	val segmentInteriors: List<Interior>? = null,
) : LensModule {
	/** A warp tube ([net.kernelpanicsoft.relativity.physics.spacetime.field.WarpTube]) pushing at [speed] times c. */
	constructor(spine: List<V3>, speed: Double, radius: Double) : this(spine, Interior.Warp(speed), radius)

	/**
	 * The inside's inverse metric along the spine's direction t̂: g^tt = [a], g^ts = [b], g^ss = [c] (s along t̂), flat
	 * across; and who looks out from inside: light its observers see going along unit m has momentum p = m + t̂ ([alpha]
	 * + ([beta] − 1) m_s) and energy at infinity p_t = −1 + [gamma] m_s (m_s = m · t̂).
	 */
	sealed class Interior(val a: Double, val b: Double, val c: Double, val alpha: Double, val beta: Double, val gamma: Double) {
		/** Light seen going along unit [m] (by this interior's observers, spine direction [t]): its momentum and p_t. */
		fun start(m: V3, t: V3): Pair<V3, Double> {
			val ms = m dot t
			return (m + t * (alpha + (beta - 1) * ms)) to (-1.0 + gamma * ms)
		}

		/** dt/dλ for light of momentum [p] and energy [pt] here (spine direction [t]): positive is future-directed. */
		fun tDot(p: V3, pt: Double, t: V3): Double = a * pt + b * (p dot t)

		/** Light's coordinate velocity per unit coordinate time: (p + ((g^ss − 1) p_s + g^ts p_t) t̂) / ṫ. */
		fun velocity(p: V3, pt: Double, t: V3): V3 {
			val ps = p dot t
			return (p + t * ((c - 1) * ps + b * pt)) * (1.0 / tDot(p, pt, t))
		}

		/**
		 * The momentum across a wall of normal [n] for light of energy [pt] and momentum [pT] along it, here (spine
		 * direction [t]), going along n ([sense] 1) or against it (-1): null if there's none (or none future-directed).
		 */
		fun across(pt: Double, pT: V3, n: V3, t: V3, sense: Double): Double? {
			// p = pT + q n, p_s = a0 + b0 q; null: g^tt p_t² + 2 g^ts p_t p_s + g^ss p_s² + |p|² − p_s² = 0.
			val a0 = pT dot t
			val b0 = n dot t
			val qa = 1 + (c - 1) * b0 * b0
			val qb = 2 * (c - 1) * a0 * b0 + 2 * b * pt * b0
			val qc = (c - 1) * a0 * a0 + 2 * b * pt * a0 + a * pt * pt + (pT dot pT)
			val roots = if (abs(qa) < 1e-12) (if (abs(qb) < 1e-14) emptyList() else listOf(-qc / qb)) else {
				val disc = qb * qb - 4 * qa * qc
				if (disc < 0) return null
				val s = sqrt(disc)
				listOf((-qb - s) / (2 * qa), (-qb + s) / (2 * qa))
			}
			return roots.filter { q ->
				val p = pT + n * q
				val td = tDot(p, pt, t)
				// Future-directed, and going the right way across (its velocity's normal part).
				val ps = p dot t
				val vn = (q + b0 * ((c - 1) * ps + b * pt)) / td
				td > 1e-12 && vn * sense > 0
			}.minByOrNull { abs(it) }
		}

		/** The inverse of [start]: the direction its observers see light of momentum [p] and energy [pt] (any scale) going. */
		fun observe(p: V3, pt: Double, t: V3): V3 {
			val ps = p dot t
			val scale = -(1 + gamma * alpha / beta) / (pt - gamma * ps / beta)
			return (p - t * ps) * scale + t * ((scale * ps - alpha) / beta)
		}

		/**
		 * A warp tube's push at [speed] times c along t̂ ([net.kernelpanicsoft.relativity.physics.spacetime.field.WarpTube]):
		 * its observers ride it (Eulerian: time and space flat for them), light's coordinate velocity m̂ + v t̂.
		 */
		class Warp(val speed: Double) : Interior(-1.0, -speed, 1.0 - speed * speed, 0.0, 1.0, -speed)

		/**
		 * A Krasnikov tube's inside ([net.kernelpanicsoft.relativity.physics.spacetime.field.KrasnikovTube]), k in (0, 1]:
		 * light runs along t̂ at 1 / k, back at 1. Its observers stand still (g_tt is −1: they always can); along t̂ their
		 * unit vector orthogonal to ∂_t is (g_ts, t̂) 2 / (1 + k), g_ts = −(1 − k) / 2.
		 */
		class Krasnikov(val k: Double) : Interior(
			-4 * k / ((1 + k) * (1 + k)), -2 * (1 - k) / ((1 + k) * (1 + k)), 4 / ((1 + k) * (1 + k)), -(1 - k) / 2, (1 + k) / 2, 0.0,
		)

		/** Flat: outside. */
		object Flat : Interior(-1.0, 0.0, 1.0, 0.0, 1.0, 0.0)
	}

	init {
		require(spine.size >= 2) { "the spine needs at least two points" }
		require(segmentInteriors == null || segmentInteriors.size == spine.size - 1) { "one interior a segment" }
	}

	private val segments = spine.size - 1
	private val directions: List<V3> = (0 until segments).map { (spine[it + 1] - spine[it]).normalized() }

	/** Between segments k and k + 1: the plane through their joint halving the turn, its normal toward k + 1. */
	private val joints: List<V3> = (0 until segments - 1).map { (directions[it] + directions[it + 1]).normalized() }

	override val centre: V3 = spine.fold(V3.ZERO) { s, p -> s + p } * (1.0 / spine.size)
	override val scale: Double get() = radius

	/** Light's state: where, its momentum p (spatial, covariant) and energy at infinity p_t, and its region (-1: outside). */
	private class Ray(var x: V3, var p: V3, val pt: Double, var region: Int)

	private fun interiorOf(region: Int): Interior = if (region < 0) Interior.Flat else segmentInteriors?.get(region) ?: interior

	private fun axisOf(region: Int): V3 = if (region < 0) V3(1.0, 0.0, 0.0) else directions[region]

	/** dt/dλ: g^tt p_t + g^ts p_s (light's is positive: future-directed). */
	/** Coordinate velocity per unit coordinate time. */
	private fun velocity(r: Ray): V3 = interiorOf(r.region).velocity(r.p, r.pt, axisOf(r.region))

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
	private fun solve(pt: Double, pT: V3, n: V3, region: Int, sense: Double): Double? =
		interiorOf(region).across(pt, pT, n, axisOf(region), sense)

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
		val (p, pt) = interiorOf(region).start(d, directions[region])
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
	 * The direction light leaving [from] must take to reach [target] as the shaders find it (vertex_lens_core.glsl's
	 * relativityTubeAim): from [guess], or failing that the straight line, by at most [steps] Gauss-Newton steps on the
	 * traced path's miss, done once it misses by little or a step turns it by less than [angle] (radians: where the tube
	 * squeezes the view, a wide miss is a small angle); null if that doesn't get there. Far cheaper than [aim], which
	 * searches a grid first.
	 */
	fun quickAim(from: V3, target: V3, steps: Int = QUICK_STEPS, angle: Double = QUICK_ANGLE): V3? {
		if (secondaryOrClear(from, target)) return (target - from).normalized()
		val distance = (target - from).length
		val tolerance = QUICK_TOLERANCE * maxOf(distance, 1.0)
		var bestD: V3? = null
		var bestMiss = Double.MAX_VALUE
		val straight = (target - from).normalized()
		val outside = regionOf(from) < 0
		for (attempt in 0 until 3) {
			// (From outside the second guess is the first.)
			if (outside && attempt == 1) continue
			// The closed-form guesses (from outside, the straight line), and failing both the best of a coarse grid over the
			// tube's size as seen from here (light through the tube from beside it, its image refracted far off the line).
			var start = if (attempt < 2) guess(from, target, attempt == 1) else straight
			if (attempt == 0 && outside) {
				// From outside, light through the tube: its image moved mostly along the tube's axis as seen from here. The
				// best of a few directions that way (the straight line among them), as far as the grid reaches.
				val t = directions[(0 until segments).minBy { (target - closestOnSegment(target, it)).length }]
				val u = t - straight * (t dot straight)
				if (u.length > 1e-6) {
					val way = u.normalized()
					val reach = kotlin.math.atan2(3.0 * radius, maxOf(distance, radius)).coerceAtMost(0.6)
					var scanMiss = Double.MAX_VALUE
					for (i in -AXIS_SCAN..AXIS_SCAN) {
						val d = (straight + way * (reach * i / AXIS_SCAN)).normalized()
						quickTraces++
						val m = trace(from, d, target)?.length ?: continue
						if (m < scanMiss) { scanMiss = m; start = d }
					}
				}
			}
			val (e1, e2) = FieldLens.tangents(start)
			if (attempt == 2) {
				val reach = kotlin.math.atan2(3.0 * radius, maxOf(distance, radius)).coerceAtMost(0.6)
				var gridMiss = Double.MAX_VALUE
				for (i in -QUICK_GRID..QUICK_GRID) for (j in -QUICK_GRID..QUICK_GRID) {
					if (i == 0 && j == 0) continue
					val d = (straight + e1 * (reach * i / QUICK_GRID) + e2 * (reach * j / QUICK_GRID)).normalized()
					quickTraces++
					val m = trace(from, d, target)?.length ?: continue
					if (m < gridMiss) { gridMiss = m; start = d }
				}
				if (gridMiss == Double.MAX_VALUE) break
			}
			val (f1, f2) = FieldLens.tangents(start)
			fun direction(a: Double, b: Double) = (start + f1 * a + f2 * b).normalized()
			fun miss(a: Double, b: Double): V3? { quickTraces++; return trace(from, direction(a, b), target) }
			var a = 0.0
			var b = 0.0
			var settled = false
			var r = miss(a, b) ?: continue
			for (step in 0 until steps) {
				if (r.length < tolerance) break
				val h = QUICK_STEP
				val ja = ((miss(a + h, b) ?: break) - r) * (1.0 / h)
				val jb = ((miss(a, b + h) ?: break) - r) * (1.0 / h)
				val aa = ja dot ja
				val ab = ja dot jb
				val bb = jb dot jb
				val det = aa * bb - ab * ab
				if (abs(det) < 1e-30) break
				val ra = -(ja dot r)
				val rb = -(jb dot r)
				var da = (bb * ra - ab * rb) / det
				var db = (aa * rb - ab * ra) / det
				var stepped = false
				for (halving in 0 until QUICK_HALVINGS) {
					val next = miss(a + da, b + db)
					if (next != null && next.length < r.length) {
						a += da; b += db; r = next
						stepped = true
						break
					}
					da *= 0.5; db *= 0.5
				}
				if (!stepped) break
				if (sqrt(da * da + db * db) < angle) { settled = true; break }
			}
			if (settled && r.length < 0.02 * maxOf(distance, 1.0)) return direction(a, b)
			if (r.length < bestMiss) { bestMiss = r.length; bestD = direction(a, b) }
			if (r.length < tolerance) break
		}
		return bestD?.takeIf { bestMiss < 0.02 * maxOf(distance, 1.0) }
	}

	/**
	 * The path of light leaving [from] along [d] (as seen there): the points where it turned, then on out to [length]
	 * along its last line; null if it's captured.
	 */
	fun path(from: V3, d: V3, length: Double): List<V3>? {
		val points = arrayListOf(from)
		val walk = Walk(start(from, d))
		repeat(MAX_EVENTS) {
			val w = velocity(walk.ray)
			when (walk.step()) {
				null -> return null
				true -> { points += walk.ray.x + w.normalized() * length; return points }
				false -> points += walk.ray.x
			}
		}
		return null
	}

	/** Whether [x] is inside the tube. */
	fun contains(x: V3): Boolean = regionOf(x) >= 0

	/** Traces made by [quickAim] (for tests' cost counts). */
	var quickTraces = 0L

	/** Whether the straight line from [from] to [target] stays clear of the tube (both ends outside it): unbent. */
	private fun secondaryOrClear(from: V3, target: V3): Boolean =
		regionOf(from) < 0 && regionOf(target) < 0 && (0 until segments).none { k -> capsule(from, target - from, k)?.let { (t0, t1) -> t1 > 0 && t0 < 1 } == true }

	/**
	 * [quickAim]'s first guesses, in closed form, from inside the tube (else the straight line). A target in the same
	 * straight piece, exactly: the direction whose light runs straight at it. One outside: as if the light left the
	 * straight line's way through a wall facing the target (the side across from it, or the end); or ([beside]) as if
	 * it ran inside to the wall beside the target (the tube carrying it along, as a fast one does) and left there.
	 */
	internal fun guess(from: V3, target: V3, beside: Boolean = false): V3 {
		val s = (target - from).normalized()
		val k = regionOf(from)
		if (k < 0) return s
		val t = directions[k]
		val f = interiorOf(k)
		if (regionOf(target) == k) return straightAt(s, t, k) ?: s
		if (!beside) wallGuess(from, target, k)?.let { return it }
		if (beside) {
			// The wall's point nearest the target.
			var near = spine[0]
			for (j in 0 until segments) {
				val c = closestOnSegment(target, j)
				if ((target - c).length < (target - near).length) near = c
			}
			val wall = near + (target - near).normalized() * (0.999 * radius)
			return straightAt((wall - from).normalized(), t, k) ?: s
		}
		val rel = target - spine[k]
		val radial = rel - t * (rel dot t)
		// Out the side facing the target, or (beside the axis) the end.
		val n = if (radial.length > radius) radial.normalized() else t * (if ((s dot t) >= 0) 1.0 else -1.0)
		val pT = s - n * (s dot n)
		val q = solve(-1.0, pT, n, k, 1.0) ?: return guess(from, target, true)
		return f.observe(pT + n * q, -1.0, t).normalized()
	}

	/**
	 * From inside piece [k] (taken as an endless cylinder), to [target] outside it: the light that leaves through the side
	 * in the target's plane through the axis, by where along it leaves (the one place its line out runs at the target:
	 * found between the camera's and the target's places along the axis by a scan and bisection, closed form each);
	 * null if there's none.
	 */
	private fun wallGuess(from: V3, target: V3, k: Int): V3? {
		val t = directions[k]
		val a = spine[k]
		val rel = target - a
		val zTarget = rel dot t
		val radial = rel - t * zTarget
		if (radial.length <= radius) return null
		val n = radial.normalized()
		val zFrom = (from - a) dot t
		// How far the light out misses the target's way along the axis, leaving at z; and how it was seen.
		fun at(z: Double): Pair<Double, V3>? {
			val wall = a + t * z + n * radius
			val m = straightAt((wall - from).normalized(), t, k) ?: return null
			val (p, pt) = interiorOf(k).start(m, t)
			val pT = p - n * (p dot n)
			val q = solve(pt, pT, n, -1, 1.0) ?: return null
			val d = velocity(Ray(wall, pT + n * q, pt, -1)).normalized()
			return ((d dot t) - ((target - wall).normalized() dot t)) to m
		}
		// (Past either by as far as the target is from the axis: the light can turn back outside.)
		val lo = minOf(zFrom, zTarget) - radial.length
		val hi = maxOf(zFrom, zTarget) + radial.length
		var prevZ = lo
		var prev = at(lo)
		for (i in 1..WALL_SCAN) {
			val z = lo + (hi - lo) * i / WALL_SCAN
			val here = at(z)
			if (prev != null && here != null && (prev.first <= 0) != (here.first <= 0)) {
				var z0 = prevZ
				var z1 = z
				var f0 = prev.first
				var best: Pair<Double, V3> = here
				repeat(WALL_BISECTIONS) {
					val mid = 0.5 * (z0 + z1)
					val fm = at(mid) ?: return@repeat
					best = fm
					if ((fm.first <= 0) == (f0 <= 0)) { z0 = mid; f0 = fm.first } else z1 = mid
				}
				return best.second.normalized()
			}
			prevZ = z
			prev = here
		}
		return null
	}

	/** Inside (spine direction [t]), the direction its observers see light going whose path runs along unit [s]. */
	private fun straightAt(s: V3, t: V3, region: Int): V3? {
		val f = interiorOf(region)
		// Light seen along m runs along (m⊥, A + B m_s): solve for m_s putting that along s.
		val aa = f.c * f.alpha - f.b
		val bb = f.c * f.beta + f.b * f.gamma
		val ss = s dot t
		val across = s - t * ss
		val sigma = across.length
		if (sigma < 1e-9) return t * (if ((aa + bb * kotlin.math.sign(ss)) * ss >= 0) kotlin.math.sign(ss) else -kotlin.math.sign(ss))
		val qa = bb * bb * sigma * sigma + ss * ss
		val qb = 2 * aa * bb * sigma * sigma
		val qc = aa * aa * sigma * sigma - ss * ss
		val disc = qb * qb - 4 * qa * qc
		if (disc < 0) return null
		val root = sqrt(disc)
		var ms = (-qb + root) / (2 * qa)
		// (The root whose light runs toward s, not away along t̂.)
		if ((aa + bb * ms) * ss < 0 || abs(ms) > 1) ms = (-qb - root) / (2 * qa)
		ms = ms.coerceIn(-1.0, 1.0)
		return t * ms + across * (sqrt(1 - ms * ms) / sigma)
	}


	/**
	 * How far the light leaving [from] along [d] misses [target]: the target less the nearest point of its path (the
	 * straight pieces between walls, up to where it's captured, if it is: what's seen is the target's light, wherever it
	 * goes after).
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
				// (Captured: up to there. What's seen is the target's light, wherever it goes after.)
				null -> { piece(a, w, (walk.ray.x - a).length); return best }
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

		/** [quickAim]'s steps, step halvings, finite-difference step (radians) and tolerance (of the distance). */
		const val QUICK_STEPS = 6
		private const val QUICK_HALVINGS = 4
		private const val QUICK_STEP = 1e-4
		private const val QUICK_TOLERANCE = 2e-4

		/** [quickAim]'s scan from outside along the tube's axis: this many each way. */
		const val AXIS_SCAN = 1

		/** [wallGuess]'s scan along the axis, and its bisections after. */
		const val WALL_SCAN = 8
		const val WALL_BISECTIONS = 8

		/** [quickAim]'s last-resort grid: this many each side of the straight line, each way. */
		private const val QUICK_GRID = 1

		/** [quickAim]'s step small enough to stop at (radians): a quarter of a pixel's. */
		const val QUICK_ANGLE = 2.5e-4
	}
}
