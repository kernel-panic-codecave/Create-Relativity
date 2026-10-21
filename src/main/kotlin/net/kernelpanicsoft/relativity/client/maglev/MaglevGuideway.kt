package net.kernelpanicsoft.relativity.client.maglev

import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.Direction
import net.neoforged.neoforge.client.model.pipeline.QuadBakingVertexConsumer
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Maglev track's guideway trough |\_______/| (tools/maglev_track_models.py draws the same profile for the fallback
 * models), swept along any track: a block's straight path or a curve, as a chain of [Frame]s. Where other track runs
 * through it (a crossing, a curve leaving at a junction) its walls are cut away inside the other's channel, mitred where
 * they meet: a point of a wall is kept where it's at least as far from the other's centre line as from its own. So two
 * crossing troughs open into one floor, and where a curve leaves a straight, the walls between them rise out of the
 * floor together where the two have diverged.
 */
object MaglevGuideway {
	/**
	 * A cross-section of the track: where the profile's origin (the centre line, at the bottom of a block's track) is,
	 * which way is across ([r], toward +x of the profile) and up ([u]), unit vectors, and how far along the track it is
	 * ([s], blocks).
	 */
	class Frame(
		val x: Double, val y: Double, val z: Double,
		val rx: Double, val ry: Double, val rz: Double,
		val ux: Double, val uy: Double, val uz: Double,
		val s: Double,
	)

	/** Another track's centre line, seen from above: (x, z) pairs (in the frames' coordinates). */
	data class Line(val points: List<Double>) {
		/** How far (x, z) is from the line. */
		fun distance(x: Double, z: Double): Double {
			if (points.size == 2) return hypot(x - points[0], z - points[1])
			var best = Double.MAX_VALUE
			var i = 0
			while (i + 3 < points.size) {
				val ax = points[i]
				val az = points[i + 1]
				val dx = points[i + 2] - ax
				val dz = points[i + 3] - az
				val length2 = dx * dx + dz * dz
				val t = if (length2 > 0) (((x - ax) * dx + (z - az) * dz) / length2).coerceIn(0.0, 1.0) else 0.0
				best = minOf(best, hypot(x - ax - dx * t, z - az - dz * t))
				i += 2
			}
			return best
		}
	}

	/** A profile point (pixels: across from the centre line, up from the bottom of a block's track). */
	private data class P(val x: Double, val y: Double)

	private const val FLOOR = 15.0
	private const val TOP = 1.0
	private const val BOTTOM = -2.0
	private const val WALL_IN = 21.0
	private const val WALL_OUT = 23.0
	private const val WALL_TOP = 7.0

	/** A trough's half-width (blocks): out to its walls' outer faces. */
	const val HALF_WIDTH = WALL_OUT / 16.0

	/** Slack (blocks) in the cut, so a wall running exactly along another (where a curve leaves) isn't cut to slivers. */
	private const val SLACK = 0.25 / 16.0

	private const val FLOOR_LIFT = 0.002

	private const val TEX_W = 32.0
	private const val TEX_H = 64.0
	private val BANDS = mapOf("floor" to (0.0 to 30.0), "slope" to (30.0 to 39.0), "top" to (39.0 to 41.0), "outer" to (41.0 to 50.0), "bottom" to (50.0 to 58.0), "cap" to (58.0 to 64.0))

	// Convex parts, counter-clockwise; a null kind marks an edge inside the profile (between parts), not drawn.
	private val FLOOR_PART = listOf(P(-FLOOR, BOTTOM) to "bottom", P(FLOOR, BOTTOM) to null, P(FLOOR, TOP) to "floor", P(-FLOOR, TOP) to null)
	private val RIGHT_WALL = listOf(P(FLOOR, BOTTOM) to "bottom", P(WALL_OUT, BOTTOM) to "outer", P(WALL_OUT, WALL_TOP) to "top", P(WALL_IN, WALL_TOP) to "slope", P(FLOOR, TOP) to null)
	private val LEFT_WALL = listOf(P(-WALL_OUT, BOTTOM) to "bottom", P(-FLOOR, BOTTOM) to null, P(-FLOOR, TOP) to "slope", P(-WALL_IN, WALL_TOP) to "top", P(-WALL_OUT, WALL_TOP) to "outer")

	/**
	 * The trough along [frames], its walls cut by [others], as quads textured from [sprite]. Its floor sits [rank] hairs
	 * up (each direction its own: where floors overlap, no flicker).
	 */
	fun build(frames: List<Frame>, others: List<Line>, rank: Int, sprite: TextureAtlasSprite, out: MutableList<BakedQuad>) {
		if (frames.size < 2) return
		val last = (frames.size - 1).toDouble()
		val lift = FLOOR_LIFT * rank
		sweep(frames, FLOOR_PART, { 0.0 to last }, 0, lift, sprite, out)
		for (wall in listOf(RIGHT_WALL, LEFT_WALL)) {
			val cuts = wall.associate { (q, _) -> q to kept(frames, q.x / 16.0, others) }
			val count = cuts.values.first().size
			// (Every point of a wall is cut the same number of times, but for slivers at the slack's edge: then all as its first.)
			val uniform = cuts.values.any { it.size != count }
			for (i in 0 until count) {
				sweep(frames, wall, { q -> (if (uniform) cuts.values.first() else cuts.getValue(q))[i] }, 1, 0.0, sprite, out)
			}
		}
	}

	/** Where (fractional frame indices) a line along the track at [offset] across it (blocks) is kept by every other line. */
	private fun kept(frames: List<Frame>, offset: Double, others: List<Line>): List<Pair<Double, Double>> {
		val last = (frames.size - 1).toDouble()
		if (others.isEmpty()) return listOf(0.0 to last)
		val margin = DoubleArray(frames.size) { i ->
			val f = frames[i]
			val x = f.x + f.rx * offset
			val z = f.z + f.rz * offset
			others.minOf { it.distance(x, z) } - abs(offset) + SLACK
		}
		val stretches = ArrayList<Pair<Double, Double>>()
		var start = if (margin[0] >= 0) 0.0 else Double.NaN
		for (i in 1 until frames.size) {
			val a = margin[i - 1]
			val b = margin[i]
			if ((a >= 0) == (b >= 0)) continue
			val t = i - 1 + a / (a - b)
			if (b >= 0) start = t
			else {
				if (t - start > 1e-3) stretches += start to t
				start = Double.NaN
			}
		}
		if (!start.isNaN() && last - start > 1e-3) stretches += start to last
		return stretches
	}

	/** Where profile point [q] is at fractional frame index [t] (and how far along the track). */
	private fun at(frames: List<Frame>, t: Double, q: P, lift: Double): DoubleArray {
		val i = floor(t).toInt().coerceIn(0, frames.size - 2)
		val w = t - i
		val a = frames[i]
		val b = frames[i + 1]
		fun mix(p: Double, r: Double) = p + (r - p) * w
		val x = q.x / 16.0
		val y = q.y / 16.0 + lift
		return doubleArrayOf(
			mix(a.x, b.x) + mix(a.rx, b.rx) * x + mix(a.ux, b.ux) * y,
			mix(a.y, b.y) + mix(a.ry, b.ry) * x + mix(a.uy, b.uy) * y,
			mix(a.z, b.z) + mix(a.rz, b.rz) * x + mix(a.uz, b.uz) * y,
			mix(a.s, b.s),
		)
	}

	/** The track's direction at fractional frame index [t]. */
	private fun along(frames: List<Frame>, t: Double): DoubleArray {
		val i = floor(t).toInt().coerceIn(0, frames.size - 2)
		val a = frames[i]
		val b = frames[i + 1]
		return doubleArrayOf(b.x - a.x, b.y - a.y, b.z - a.z)
	}

	/** One part of the profile, each of its points over its own stretch (fractional frame indices), capped at both ends. */
	private fun sweep(
		frames: List<Frame>, part: List<Pair<P, String?>>, stretch: (P) -> Pair<Double, Double>, kindOffset: Int, lift: Double,
		sprite: TextureAtlasSprite, out: MutableList<BakedQuad>,
	) {
		val pts = part.map { it.first }
		for ((k, entry) in part.withIndex()) {
			val (a, kind) = entry
			if (kind == null) continue
			val b = pts[(k + 1) % pts.size]
			val (fromA, toA) = stretch(a)
			val (fromB, toB) = stretch(b)
			// Stations along it: its two ends' starts, every frame both pass, their ends.
			val stations = ArrayList<Pair<Double, Double>>()
			stations += fromA to fromB
			var m = floor(maxOf(fromA, fromB)).toInt() + 1
			while (m < minOf(toA, toB)) {
				stations += m.toDouble() to m.toDouble()
				m++
			}
			stations += toA to toB
			val nx = b.y - a.y
			val ny = -(b.x - a.x)
			val va = bandV(kind, a, b, a)
			val vb = bandV(kind, a, b, b)
			for (j in 1 until stations.size) {
				val (ta1, tb1) = stations[j - 1]
				val (ta2, tb2) = stations[j]
				val corners = listOf(at(frames, ta1, a, lift), at(frames, tb1, b, lift), at(frames, tb2, b, lift), at(frames, ta2, a, lift))
				val mid = frames[floor((ta1 + ta2) / 2).toInt().coerceIn(0, frames.size - 1)]
				val normal = doubleArrayOf(mid.rx * nx + mid.ux * ny, mid.ry * nx + mid.uy * ny, mid.rz * nx + mid.uz * ny)
				val base = floor(corners.minOf { it[3] }) * 16
				val uvs = listOf(corners[0][3] * 16 - base to va, corners[1][3] * 16 - base to vb, corners[2][3] * 16 - base to vb, corners[3][3] * 16 - base to va)
				quad(corners, uvs, normal, sprite, out)
			}
		}
		val (lo, hi) = BANDS.getValue("cap")
		val cuv = pts.map { (it.x + WALL_OUT) / (2 * WALL_OUT) * TEX_W to hi - (it.y - BOTTOM) / (WALL_TOP - BOTTOM) * (hi - lo) }
		for (end in 0..1) {
			val cap = pts.map { q -> stretch(q).let { at(frames, if (end == 0) it.first else it.second, q, lift) } }
			val t = stretch(pts[0]).let { if (end == 0) it.first else it.second }
			val dir = along(frames, t)
			val sign = if (end == 0) -1.0 else 1.0
			// (Its plane's normal, facing out along the track: square across it, or slanted where cut.)
			val e1 = DoubleArray(3) { cap[1][it] - cap[0][it] }
			val e2 = DoubleArray(3) { cap[2][it] - cap[0][it] }
			val normal = doubleArrayOf(e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0])
			if ((normal[0] * dir[0] + normal[1] * dir[1] + normal[2] * dir[2]) * sign < 0) for (i in 0..2) normal[i] = -normal[i]
			for (k in 1 until pts.size - 1) quad(listOf(cap[0], cap[k], cap[k + 1]), listOf(cuv[0], cuv[k], cuv[k + 1]), normal, sprite, out)
		}
	}

	private fun quad(corners: List<DoubleArray>, uvs: List<Pair<Double, Double>>, normal: DoubleArray, sprite: TextureAtlasSprite, out: MutableList<BakedQuad>) {
		var points = corners
		var coords = uvs
		val e1 = DoubleArray(3) { points[1][it] - points[0][it] }
		val e2 = DoubleArray(3) { points[2][it] - points[0][it] }
		val gx = e1[1] * e2[2] - e1[2] * e2[1]
		val gy = e1[2] * e2[0] - e1[0] * e2[2]
		val gz = e1[0] * e2[1] - e1[1] * e2[0]
		if (gx * normal[0] + gy * normal[1] + gz * normal[2] < 0) {
			points = points.reversed()
			coords = coords.reversed()
		}
		if (points.size == 3) {
			points = points + listOf(points[2])
			coords = coords + listOf(coords[2])
		}
		val n = sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]).takeIf { it > 0 } ?: 1.0
		val nx = (normal[0] / n).toFloat()
		val ny = (normal[1] / n).toFloat()
		val nz = (normal[2] / n).toFloat()
		val baker = QuadBakingVertexConsumer()
		baker.setSprite(sprite)
		baker.setDirection(Direction.getNearest(nx, ny, nz))
		baker.setShade(true)
		baker.setHasAmbientOcclusion(false)
		for ((p, uv) in points.zip(coords)) {
			baker.addVertex(p[0].toFloat(), p[1].toFloat(), p[2].toFloat())
			baker.setColor(255, 255, 255, 255)
			baker.setUv(sprite.getU((uv.first / TEX_W).toFloat()), sprite.getV((uv.second / TEX_H).toFloat()))
			baker.setNormal(nx, ny, nz)
		}
		out += baker.bakeQuad()
	}

	private fun bandV(kind: String, a: P, b: P, p: P): Double {
		val (lo, hi) = BANDS.getValue(kind)
		return when (kind) {
			"floor" -> lo + (p.x + FLOOR) / (2 * FLOOR) * (hi - lo)
			"slope", "top", "outer" -> {
				val first = if (kind == "top") (if (abs(a.x) <= abs(b.x)) a else b) else (if (a.y <= b.y) a else b)
				val length = hypot(b.x - a.x, b.y - a.y)
				lo + (if (length > 0) hypot(p.x - first.x, p.y - first.y) / length else 0.0) * (hi - lo)
			}
			else -> lo + (p.x + WALL_OUT) / (2 * WALL_OUT) * (hi - lo)
		}
	}
}
