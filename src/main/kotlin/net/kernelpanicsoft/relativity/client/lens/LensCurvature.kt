package net.kernelpanicsoft.relativity.client.lens

import net.kernelpanicsoft.relativity.physics.lens.SchwarzschildImages
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How finely a straight edge must be split for the vertex lens to draw its image within [TOLERANCE] pixels: the
 * vertex lens draws straight lines between its pieces' images, but the edge's own image curves. Its ends, midpoint and
 * quarter points are imaged (each hole's primary image, from the same table the shader reads: vertex_lens_core.glsl's
 * relativitySchwarzschildImageAt), and the inner points' angle off the great circle through the ends' images (a
 * straight line on screen) is the error unsplit; split n ways it falls as 1 / n^2. (The midpoint alone misses an
 * edge whose image runs round a shadow from one side to the other: its middle can fall on the line again.)
 * Seen from a [View]: the camera (snapshotted: meshes are built for it, QuadSubdivision) and the pixel's angle.
 */
object LensCurvature {
	/** Pixels an edge's image may stray from straight. */
	const val TOLERANCE = 1.0

	/**
	 * Where its ends are bent more than [BENT] pixels, an edge's pieces' images are kept under [PIECE] pixels long too: an
	 * edge whose image runs round a shadow, its ends on either side, can have every point sampled on the line between
	 * them and still cross the shadow.
	 */
	const val BENT = 2.0
	const val PIECE = 48.0

	/** At least this many pieces where some of an edge's points have an image and some don't (by a shadow's edge, or
	 * the far edge of the ground seen past a hole): the triangles there stretch toward the hole. */
	const val MIXED = 8

	/** The camera (world) and a pixel's angle (radians) the levels are measured for. */
	class View(val x: Double, val y: Double, val z: Double, val pixel: Double)

	/** Point (x, y, z)'s primary image direction seen from [view] round [hole] (x, y, z, r_s; world), into [out]; false if it has none. */
	private fun image(view: View, hole: DoubleArray, x: Double, y: Double, z: Double, out: DoubleArray): Boolean {
		val table = VertexLens.primaryImages ?: return false
		val rs = hole[3]
		val hx = hole[0] - view.x
		val hy = hole[1] - view.y
		val hz = hole[2] - view.z
		val rc = sqrt(hx * hx + hy * hy + hz * hz)
		val fx = x - hole[0]
		val fy = y - hole[1]
		val fz = z - hole[2]
		val rp = sqrt(fx * fx + fy * fy + fz * fz)
		val px = x - view.x
		val py = y - view.y
		val pz = z - view.z
		val pl = maxOf(sqrt(px * px + py * py + pz * pz), 1e-9)
		if (rs <= 0.0 || rc <= rs || rp <= rs) {
			if (rp <= rs) return false
			out[0] = px / pl; out[1] = py / pl; out[2] = pz / pl
			return true
		}
		val u0 = maxOf(rs / rc, 1e-4)
		val uP = maxOf(rs / rp, 1e-4)
		// (Bending under a few milliradians: straight.)
		if (u0 < 2e-3 && uP < 2e-3) {
			out[0] = px / pl; out[1] = py / pl; out[2] = pz / pl
			return true
		}
		// e1 hole to camera, e2 across toward the point's side.
		val e1x = -hx / rc
		val e1y = -hy / rc
		val e1z = -hz / rc
		val cosPhi = ((fx * e1x + fy * e1y + fz * e1z) / rp).coerceIn(-1.0, 1.0)
		var sx = fx / rp - cosPhi * e1x
		var sy = fy / rp - cosPhi * e1y
		var sz = fz / rp - cosPhi * e1z
		val sl = maxOf(sqrt(sx * sx + sy * sy + sz * sz), 1e-9)
		sx /= sl; sy /= sl; sz /= sl
		val phi = kotlin.math.acos(cosPhi)
		val local = SchwarzschildImages.sample(table, VertexLens.IMAGES_U0, VertexLens.IMAGES_UP, VertexLens.IMAGES_PHI, u0, uP, phi)
		if (local.isNaN()) return false
		val psi = atan2(sin(local), cos(local) * sqrt(1.0 - u0))
		out[0] = -cos(psi) * e1x + sin(psi) * sx
		out[1] = -cos(psi) * e1y + sin(psi) * sy
		out[2] = -cos(psi) * e1z + sin(psi) * sz
		return true
	}

	/** The angle (radians) between point (x, y, z)'s straight-line direction from [view] and [seen]. */
	private fun turn(view: View, x: Double, y: Double, z: Double, seen: DoubleArray): Double {
		val px = x - view.x
		val py = y - view.y
		val pz = z - view.z
		val pl = maxOf(sqrt(px * px + py * py + pz * pz), 1e-9)
		return kotlin.math.acos(((px * seen[0] + py * seen[1] + pz * seen[2]) / pl).coerceIn(-1.0, 1.0))
	}

	private val scratch = ThreadLocal.withInitial { Array(3) { DoubleArray(3) } }

	/** Fractions along an edge its inner points are imaged at. */
	private val inner = doubleArrayOf(0.5, 0.25, 0.75)

	/**
	 * Pieces the edge from (ax, ay, az) to (bx, by, bz) (world) needs round [holes] seen from [view] (the most any hole
	 * asks); [hidden] where some of its points have an image and some don't (by a shadow's edge: the curve can't be
	 * measured there).
	 */
	fun pieces(view: View, holes: List<DoubleArray>, ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double, hidden: (DoubleArray) -> Int): Int {
		val (a, b, m) = scratch.get()
		var n = 1
		for (hole in holes) {
			val seenA = image(view, hole, ax, ay, az, a)
			val seenB = image(view, hole, bx, by, bz, b)
			if (!seenA && !seenB) continue
			if (!seenA || !seenB) {
				n = maxOf(n, hidden(hole), MIXED)
				continue
			}
			// Bent: the ends' images against their straight-line directions; then the image's length bounds the pieces.
			val bent = maxOf(turn(view, ax, ay, az, a), turn(view, bx, by, bz, b)) / view.pixel
			if (bent > BENT) {
				val length = kotlin.math.acos((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)) / view.pixel
				n = maxOf(n, ceil(length / PIECE).toInt())
			}
			// The great circle through the ends' images (a straight line on screen).
			var cx = a[1] * b[2] - a[2] * b[1]
			var cy = a[2] * b[0] - a[0] * b[2]
			var cz = a[0] * b[1] - a[1] * b[0]
			val cl = sqrt(cx * cx + cy * cy + cz * cz)
			if (cl >= 1e-12) {
				cx /= cl; cy /= cl; cz /= cl
			}
			var off = 0.0
			var seen = true
			for (t in inner) {
				if (!image(view, hole, ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t, m)) {
					seen = false
					break
				}
				off = maxOf(off, if (cl < 1e-12) {
					sqrt((m[0] - a[0]) * (m[0] - a[0]) + (m[1] - a[1]) * (m[1] - a[1]) + (m[2] - a[2]) * (m[2] - a[2]))
				} else {
					abs(asin((m[0] * cx + m[1] * cy + m[2] * cz).coerceIn(-1.0, 1.0)))
				})
			}
			if (!seen) {
				n = maxOf(n, hidden(hole), MIXED)
				continue
			}
			val pixels = off / view.pixel
			if (pixels > TOLERANCE) n = maxOf(n, ceil(sqrt(pixels / TOLERANCE)).toInt())
		}
		return n
	}
}
