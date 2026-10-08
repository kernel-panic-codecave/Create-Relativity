package net.kernelpanicsoft.relativity.physics.lens

import net.kernelpanicsoft.relativity.physics.geodesic.HamiltonianRay
import net.kernelpanicsoft.relativity.physics.spacetime.field.ComposedSpacetime
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * How light crosses one cubic cell (corner [x0], [y0], [z0], side [size]; physics axes) of a spacetime: for a ray
 * entering through a face at face position (u, v) in [0, 1]² heading along a direction in the inward hemisphere (the
 * hemi-octahedral square, [hemisphere]), where it leaves and heading which way. Stored as how far it strays from the
 * straight line, which is small and smooth, on a [positions]² × [directions]² grid per face; between grid points,
 * quadrilinear. Tracing through many cells is then one lookup per cell instead of many steps ([cross]).
 *
 * Light followed forward in time (the map of a static spacetime serves light traced backward too: reverse both ends).
 */
class CellLightMap(
	val spacetime: ComposedSpacetime,
	val x0: Double, val y0: Double, val z0: Double, val size: Double,
	val positions: Int, val directions: Int,
) {
	/** Per face, position (u, v) and direction (a, b): 6 numbers (drift x y z over half the length squared, turn x y z over the length). */
	private val data = DoubleArray(6 * positions * positions * directions * directions * STRIDE)

	/** Rays the map couldn't follow (captured, or still inside after a long way): marked NaN. */
	var failures = 0
		private set

	init {
		val ray = HamiltonianRay(spacetime, rtol = 1e-9, atol = 1e-11)
		for (face in 0 until 6) for (pu in 0 until positions) for (pv in 0 until positions)
			for (da in 0 until directions) for (db in 0 until directions) {
				val u = pu / (positions - 1.0)
				val v = pv / (positions - 1.0)
				val a = da / (directions - 1.0) * 2 - 1
				val b = db / (directions - 1.0) * 2 - 1
				val entry = facePoint(face, u, v)
				val dir = faceDirection(face, a, b)
				val exit = trace(ray, entry, dir)
				val base = index(face, pu, pv, da, db)
				if (exit == null) {
					failures += 1
					for (k in 0 until STRIDE) data[base + k] = Double.NaN
					continue
				}
				// How the ray bends per unit of its path (smooth: the field averaged along the crossing, whereas the path's
				// own length jumps about where it grazes an edge): its sideways drift over half its length squared, and its
				// turn over its length.
				val dx = exit[0] - entry[0]
				val dy = exit[1] - entry[1]
				val dz = exit[2] - entry[2]
				val along = dx * dir[0] + dy * dir[1] + dz * dir[2]
				// (Rays skimming the face barely enter: over such a short way their bend is nothing, and dividing their
				// round-off by a vanishing length would only spread noise to their neighbours.)
				if (along < 0.25) {
					for (k in 0 until STRIDE) data[base + k] = 0.0
					continue
				}
				val bend = 2.0 / (along * along)
				data[base] = (dx - along * dir[0]) * bend
				data[base + 1] = (dy - along * dir[1]) * bend
				data[base + 2] = (dz - along * dir[2]) * bend
				data[base + 3] = (exit[3] - dir[0]) / along
				data[base + 4] = (exit[4] - dir[1]) / along
				data[base + 5] = (exit[5] - dir[2]) / along
			}
	}

	private fun index(face: Int, pu: Int, pv: Int, da: Int, db: Int) =
		((((face * positions + pu) * positions + pv) * directions + da) * directions + db) * STRIDE

	/** The point at face position (u, v) of face (0..5: -x, +x, -y, +y, -z, +z). */
	fun facePoint(face: Int, u: Double, v: Double): DoubleArray {
		val p = DoubleArray(3)
		val axis = face / 2
		val (s, t) = when (axis) { 0 -> 1 to 2; 1 -> 2 to 0; else -> 0 to 1 }
		p[axis] = if (face % 2 == 0) 0.0 else size
		p[s] = u * size
		p[t] = v * size
		return doubleArrayOf(x0 + p[0], y0 + p[1], z0 + p[2])
	}

	/** The inward direction at hemi-octahedral square position (a, b) in [-1, 1]² for face. */
	fun faceDirection(face: Int, a: Double, b: Double): DoubleArray {
		val px = (a + b) / 2
		val py = (b - a) / 2
		val pz = 1 - abs(px) - abs(py)
		val n = sqrt(px * px + py * py + pz * pz)
		val axis = face / 2
		val (s, t) = when (axis) { 0 -> 1 to 2; 1 -> 2 to 0; else -> 0 to 1 }
		val d = DoubleArray(3)
		d[axis] = (if (face % 2 == 0) 1.0 else -1.0) * pz / n
		d[s] = px / n
		d[t] = py / n
		return d
	}

	/** (a, b) of inward direction [dir] for face (inverse of [faceDirection]). */
	fun hemisphere(face: Int, dir: DoubleArray): DoubleArray {
		val axis = face / 2
		val (s, t) = when (axis) { 0 -> 1 to 2; 1 -> 2 to 0; else -> 0 to 1 }
		val nz = abs(dir[axis])
		val l1 = abs(dir[s]) + abs(dir[t]) + nz
		val px = dir[s] / l1
		val py = dir[t] / l1
		return doubleArrayOf(px - py, px + py)
	}

	/** Exact: where light entering at [entry] heading [dir] leaves the cell: x, y, z, then unit direction; null if not. */
	fun trace(ray: HamiltonianRay, entry: DoubleArray, dir: DoubleArray): DoubleArray? {
		val state = HamiltonianRay.lightlike(spacetime, doubleArrayOf(0.0, entry[0], entry[1], entry[2]), dir)
		val velocity = DoubleArray(3)
		var out: DoubleArray? = null
		var previous = entry.copyOf()
		var steps = 0
		try {
			ray.advance(state, Double.MAX_VALUE, stepLimit = { s ->
				ray.coordinateVelocity(s, velocity)
				0.02 * size / maxOf(sqrt(velocity[0] * velocity[0] + velocity[1] * velocity[1] + velocity[2] * velocity[2]), 1e-9) * maxOf(1.0 / maxOf(abs(s[4]), 1e-9), 1.0)
			}) { s ->
				steps += 1
				if (spacetime.captures(s) || steps > 20000) return@advance false
				val inside = s[1] in x0..x0 + size && s[2] in y0..y0 + size && s[3] in z0..z0 + size
				if (!inside) {
					// Back to the boundary along the last step.
					var lo = 0.0
					var hi = 1.0
					fun at(f: Double) = doubleArrayOf(previous[0] + f * (s[1] - previous[0]), previous[1] + f * (s[2] - previous[1]), previous[2] + f * (s[3] - previous[2]))
					repeat(40) {
						val mid = (lo + hi) / 2
						val p = at(mid)
						if (p[0] in x0..x0 + size && p[1] in y0..y0 + size && p[2] in z0..z0 + size) lo = mid else hi = mid
					}
					val p = at(lo)
					ray.coordinateVelocity(s, velocity)
					val n = sqrt(velocity[0] * velocity[0] + velocity[1] * velocity[1] + velocity[2] * velocity[2])
					out = doubleArrayOf(p[0], p[1], p[2], velocity[0] / n, velocity[1] / n, velocity[2] / n)
					return@advance false
				}
				previous = doubleArrayOf(s[1], s[2], s[3])
				true
			}
		} catch (e: IllegalArgumentException) {
			return null
		}
		return out
	}

	/** From the map: where light entering face [face] at (u, v) heading [dir] (inward) leaves; null where it can't say. */
	fun cross(face: Int, u: Double, v: Double, dir: DoubleArray): DoubleArray? {
		val ab = hemisphere(face, dir)
		val coords = doubleArrayOf(u * (positions - 1), v * (positions - 1), (ab[0] + 1) / 2 * (directions - 1), (ab[1] + 1) / 2 * (directions - 1))
		val limits = intArrayOf(positions, positions, directions, directions)
		val base = IntArray(4)
		val frac = DoubleArray(4)
		for (k in 0..3) {
			base[k] = floor(coords[k]).toInt().coerceIn(0, limits[k] - 2)
			frac[k] = coords[k] - base[k]
		}
		val mixed = DoubleArray(STRIDE)
		for (corner in 0 until 16) {
			var weight = 1.0
			val idx = IntArray(4)
			for (k in 0..3) {
				val bit = (corner shr k) and 1
				idx[k] = base[k] + bit
				weight *= if (bit == 1) frac[k] else 1 - frac[k]
			}
			if (weight == 0.0) continue
			val at = index(face, idx[0], idx[1], idx[2], idx[3])
			if (data[at].isNaN()) return null
			for (c in 0 until STRIDE) mixed[c] += weight * data[at + c]
		}
		// The straight ray's length through the cell (exact), bent by the map's drift and turn per length.
		val entry = facePoint(face, u, v)
		val lo = doubleArrayOf(x0, y0, z0)
		var length = Double.MAX_VALUE
		for (k in 0..2) {
			if (abs(dir[k]) < 1e-12) continue
			val wall = if (dir[k] > 0) lo[k] + size else lo[k]
			length = minOf(length, (wall - entry[k]) / dir[k])
		}
		length = maxOf(length, 0.0)
		val exit = DoubleArray(6)
		for (k in 0..2) exit[k] = (entry[k] + length * dir[k] + 0.5 * mixed[k] * length * length).coerceIn(lo[k], lo[k] + size)
		val w = doubleArrayOf(dir[0] + mixed[3] * length, dir[1] + mixed[4] * length, dir[2] + mixed[5] * length)
		val n = sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2])
		for (k in 0..2) exit[3 + k] = w[k] / n
		return exit
	}

	companion object {
		const val STRIDE = 6
	}
}
