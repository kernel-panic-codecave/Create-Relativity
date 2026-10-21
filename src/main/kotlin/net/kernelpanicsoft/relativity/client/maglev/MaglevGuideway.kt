package net.kernelpanicsoft.relativity.client.maglev

import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.Direction
import net.neoforged.neoforge.client.model.pipeline.QuadBakingVertexConsumer
import kotlin.math.abs
import kotlin.math.atan2
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
	fun build(track: List<Frame>, others: List<Line>, rank: Int, sprite: TextureAtlasSprite, out: MutableList<BakedQuad>) {
		if (track.size < 2) return
		val frames = tiled(track)
		val lift = FLOOR_LIFT * rank
		solid(frames, FLOOR_PART, emptyList(), lift, sprite, out)
		for (wall in listOf(RIGHT_WALL, LEFT_WALL)) solid(frames, wall, others, 0.0, sprite, out)
	}

	/** [frames] with one more wherever the texture's tile (2 blocks along) ends, so no face runs past it. */
	private fun tiled(frames: List<Frame>): List<Frame> {
		val out = ArrayList<Frame>()
		for (i in 0 until frames.size - 1) {
			val a = frames[i]
			val b = frames[i + 1]
			out += a
			var edge = (floor(a.s / TILE) + 1) * TILE
			while (edge < b.s - 1e-9) {
				val w = (edge - a.s) / (b.s - a.s)
				fun mix(p: Double, q: Double) = p + (q - p) * w
				out += Frame(
					mix(a.x, b.x), mix(a.y, b.y), mix(a.z, b.z), mix(a.rx, b.rx), mix(a.ry, b.ry), mix(a.rz, b.rz),
					mix(a.ux, b.ux), mix(a.uy, b.uy), mix(a.uz, b.uz), edge,
				)
				edge += TILE
			}
		}
		out += frames.last()
		return out
	}

	/** The texture's tile along the track (blocks): its width. */
	private const val TILE = TEX_W / 16.0

	/** [frames] with [n] between each two (interpolated), for curves: the cut follows them closer. */
	fun refined(frames: List<Frame>, n: Int): List<Frame> {
		if (frames.size < 2 || n <= 1) return frames
		val out = ArrayList<Frame>()
		for (i in 0 until frames.size - 1) {
			val a = frames[i]
			val b = frames[i + 1]
			for (k in 0 until n) {
				val w = k.toDouble() / n
				fun mix(p: Double, q: Double) = p + (q - p) * w
				out += Frame(
					mix(a.x, b.x), mix(a.y, b.y), mix(a.z, b.z), mix(a.rx, b.rx), mix(a.ry, b.ry), mix(a.rz, b.rz),
					mix(a.ux, b.ux), mix(a.uy, b.uy), mix(a.uz, b.uz), mix(a.s, b.s),
				)
			}
		}
		out += frames.last()
		return out
	}

	// A vertex while cutting: position, distance along the track, profile point (pixels), and how far it is inside
	// what's kept (blocks; negative: cut away).
	private class V(val x: Double, val y: Double, val z: Double, val s: Double, val qx: Double, val qy: Double, val keep: Double) {
		fun toward(o: V, t: Double) = V(
			x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t, s + (o.s - s) * t, qx + (o.qx - qx) * t, qy + (o.qy - qy) * t,
			keep + (o.keep - keep) * t,
		)

		fun pos() = doubleArrayOf(x, y, z)
	}

	/**
	 * One convex part of the profile along [frames], as the slabs between each two frames: each clipped to where it's
	 * kept (no nearer any of [others] than its own centre line: the mitre), its section there capped, the part's ends capped.
	 */
	private fun solid(frames: List<Frame>, part: List<Pair<P, String?>>, others: List<Line>, lift: Double, sprite: TextureAtlasSprite, out: MutableList<BakedQuad>) {
		val pts = part.map { it.first }
		val n = pts.size
		val vs = frames.map { f ->
			pts.map { q ->
				val x = q.x / 16.0
				val y = q.y / 16.0 + lift
				val px = f.x + f.rx * x + f.ux * y
				val py = f.y + f.ry * x + f.uy * y
				val pz = f.z + f.rz * x + f.uz * y
				val keep = if (others.isEmpty()) 1.0 else others.minOf { it.distance(px, pz) } - abs(x) + SLACK
				V(px, py, pz, f.s, q.x, q.y, keep)
			}
		}
		for (i in 0 until frames.size - 1) {
			val a = vs[i]
			val b = vs[i + 1]
			if (a.all { it.keep < 0 } && b.all { it.keep < 0 }) continue
			val f = frames[i]
			val g = frames[i + 1]
			// The faces along the track.
			for (k in 0 until n) {
				val kind = part[k].second ?: continue
				val k1 = (k + 1) % n
				val poly = clip(listOf(a[k], a[k1], b[k1], b[k]))
				if (poly.size < 3) continue
				val nx = pts[k1].y - pts[k].y
				val ny = -(pts[k1].x - pts[k].x)
				val normal = doubleArrayOf(
					(f.rx + g.rx) * nx + (f.ux + g.ux) * ny, (f.ry + g.ry) * nx + (f.uy + g.uy) * ny, (f.rz + g.rz) * nx + (f.uz + g.uz) * ny,
				)
				val base = floor((f.s + g.s) / 2 / TILE) * TILE * 16
				polygon(poly, poly.map { it.s * 16 - base to bandV(kind, pts[k], pts[k1], P(it.qx, it.qy)) }, normal, sprite, out)
			}
			// The cut: the slab's section where it stops being kept.
			if ((a + b).any { it.keep < 0 } && (a + b).any { it.keep >= 0 }) {
				val section = ArrayList<V>()
				fun edge(p: V, q: V) {
					if ((p.keep < 0) != (q.keep < 0)) section += p.toward(q, p.keep / (p.keep - q.keep))
				}
				for (k in 0 until n) {
					edge(a[k], a[(k + 1) % n])
					edge(b[k], b[(k + 1) % n])
					edge(a[k], b[k])
				}
				if (section.size >= 3) {
					val removed = (a + b).filter { it.keep < 0 }
					val cx = section.sumOf { it.x } / section.size
					val cy = section.sumOf { it.y } / section.size
					val cz = section.sumOf { it.z } / section.size
					val rx = removed.sumOf { it.x } / removed.size - cx
					val ry = removed.sumOf { it.y } / removed.size - cy
					val rz = removed.sumOf { it.z } / removed.size - cz
					val ordered = ordered(section, doubleArrayOf(rx, ry, rz))
					polygon(ordered, ordered.map { capUv(it) }, planeNormal(ordered, doubleArrayOf(rx, ry, rz)), sprite, out)
				}
			}
		}
		// The ends.
		for (index in listOf(0, frames.size - 1)) {
			val poly = clip(vs[index])
			if (poly.size < 3) continue
			val other = frames[if (index == 0) 1 else frames.size - 2]
			val f = frames[index]
			val outward = doubleArrayOf(f.x - other.x, f.y - other.y, f.z - other.z)
			polygon(poly, poly.map { capUv(it) }, planeNormal(poly, outward), sprite, out)
		}
	}

	/** [poly] (a convex polygon, in order) cut to where it's kept. */
	private fun clip(poly: List<V>): List<V> {
		if (poly.all { it.keep >= 0 }) return poly
		val out = ArrayList<V>()
		for (k in poly.indices) {
			val p = poly[k]
			val q = poly[(k + 1) % poly.size]
			if (p.keep >= 0) out += p
			if ((p.keep < 0) != (q.keep < 0)) out += p.toward(q, p.keep / (p.keep - q.keep))
		}
		return out
	}

	/** Points of a convex section, in order round it (seen along [axis]). */
	private fun ordered(points: List<V>, axis: DoubleArray): List<V> {
		val cx = points.sumOf { it.x } / points.size
		val cy = points.sumOf { it.y } / points.size
		val cz = points.sumOf { it.z } / points.size
		val al = sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]).takeIf { it > 1e-12 } ?: 1.0
		val n = DoubleArray(3) { axis[it] / al }
		val helper = if (abs(n[1]) < 0.9) doubleArrayOf(0.0, 1.0, 0.0) else doubleArrayOf(1.0, 0.0, 0.0)
		val e1 = cross(n, helper).let { v -> val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); DoubleArray(3) { v[it] / l } }
		val e2 = cross(n, e1)
		val unique = ArrayList<V>()
		for (p in points) if (unique.none { abs(it.x - p.x) + abs(it.y - p.y) + abs(it.z - p.z) < 1e-7 }) unique += p
		return unique.sortedBy { p ->
			val dx = p.x - cx
			val dy = p.y - cy
			val dz = p.z - cz
			atan2(dx * e2[0] + dy * e2[1] + dz * e2[2], dx * e1[0] + dy * e1[1] + dz * e1[2])
		}
	}

	private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

	/** [poly]'s plane's normal, facing along [toward]. */
	private fun planeNormal(poly: List<V>, toward: DoubleArray): DoubleArray {
		val n = DoubleArray(3)
		for (k in poly.indices) {
			val p = poly[k]
			val q = poly[(k + 1) % poly.size]
			n[0] += (p.y - q.y) * (p.z + q.z)
			n[1] += (p.z - q.z) * (p.x + q.x)
			n[2] += (p.x - q.x) * (p.y + q.y)
		}
		if (n[0] * toward[0] + n[1] * toward[1] + n[2] * toward[2] < 0) for (i in 0..2) n[i] = -n[i]
		return n
	}

	private fun capUv(v: V): Pair<Double, Double> {
		val (lo, hi) = BANDS.getValue("cap")
		return (v.qx + WALL_OUT) / (2 * WALL_OUT) * TEX_W to hi - (v.qy - BOTTOM) / (WALL_TOP - BOTTOM) * (hi - lo)
	}

	/** A convex polygon, as a fan of quads (triangles: their last corner twice). */
	private fun polygon(poly: List<V>, uvs: List<Pair<Double, Double>>, normal: DoubleArray, sprite: TextureAtlasSprite, out: MutableList<BakedQuad>) {
		if (poly.size == 4) {
			quad(poly.map { it.pos() }, uvs, normal, sprite, out)
			return
		}
		var k = 1
		while (k + 1 < poly.size) {
			if (k + 2 < poly.size) {
				quad(listOf(poly[0].pos(), poly[k].pos(), poly[k + 1].pos(), poly[k + 2].pos()), listOf(uvs[0], uvs[k], uvs[k + 1], uvs[k + 2]), normal, sprite, out)
				k += 2
			} else {
				quad(listOf(poly[0].pos(), poly[k].pos(), poly[k + 1].pos()), listOf(uvs[0], uvs[k], uvs[k + 1]), normal, sprite, out)
				k += 1
			}
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
