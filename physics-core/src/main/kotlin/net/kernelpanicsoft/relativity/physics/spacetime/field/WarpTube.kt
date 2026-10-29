package net.kernelpanicsoft.relativity.physics.spacetime.field

import kotlin.math.cosh
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * An Alcubierre warp field shaped as a tube of [radius] round a polyline [spine] (x, y, z triples, at least two points),
 * pushing at coordinate speed [speed] (any, faster than light included) along the spine's local direction: a warp
 * bubble stretched along a train's track, bending with it. `ds² = −dt² + (dx^i − v F^i dt)²` with `F = f(ρ) t̂`: f the
 * bubble's shape on the distance ρ to the spine (round its ends too: a capsule), t̂ the spine's direction at the
 * nearest point (blended between segments' across each joint). So `δg^{ti} = −v F^i`, `δg^{ij} = −v² F^i F^j`.
 *
 * Stationary: a tube moving along its own spine (a train along its track) looks the same at every moment wherever the
 * track is straight, and nearly so round curves; its two ends are the only moving parts, left out.
 */
class WarpTube(
	val spine: DoubleArray,
	val speed: Double,
	val radius: Double,
	val sigma: Double = 8.0 / radius,
) : MetricSource {
	val points: Int = spine.size / 3

	init {
		require(spine.size % 3 == 0 && points >= 2) { "the spine needs at least two points" }
	}

	private val norm = 1.0 / (2.0 * tanh(sigma * radius))

	/** The shape function f(ρ): 1 inside the tube, 0 outside. */
	fun shape(rho: Double): Double = (tanh(sigma * (rho + radius)) - tanh(sigma * (rho - radius))) * norm

	/** Each segment's unit direction. */
	private val segmentDirections = DoubleArray(3 * (points - 1)).also { d ->
		for (k in 0 until points - 1) {
			val dx = spine[3 * k + 3] - spine[3 * k]
			val dy = spine[3 * k + 4] - spine[3 * k + 1]
			val dz = spine[3 * k + 5] - spine[3 * k + 2]
			val l = sqrt(dx * dx + dy * dy + dz * dz).takeIf { it > 0 } ?: 1.0
			d[3 * k] = dx / l; d[3 * k + 1] = dy / l; d[3 * k + 2] = dz / l
		}
	}

	/** At each joint, the plane halving the turn: its normal (toward the later segment). */
	private val jointNormals = DoubleArray(3 * maxOf(points - 2, 0)).also { d ->
		for (k in 0 until points - 2) {
			var x = segmentDirections[3 * k] + segmentDirections[3 * k + 3]
			var y = segmentDirections[3 * k + 1] + segmentDirections[3 * k + 4]
			var z = segmentDirections[3 * k + 2] + segmentDirections[3 * k + 5]
			val l = sqrt(x * x + y * y + z * z).takeIf { it > 1e-12 } ?: 1.0
			d[3 * k] = x / l; d[3 * k + 1] = y / l; d[3 * k + 2] = z / l
		}
	}

	/**
	 * The push field F = f(ρ) t̂ at (x, y, z), into [out] (3); returns ρ. t̂ is the nearest segment's direction, turning
	 * to the next across each joint's plane over a wall's thickness (as the walls rise).
	 */
	fun push(x: Double, y: Double, z: Double, out: DoubleArray): Double {
		val frame = DoubleArray(4)
		val rho = frame(x, y, z, frame)
		out[0] = frame[0] * frame[1]; out[1] = frame[0] * frame[2]; out[2] = frame[0] * frame[3]
		return rho
	}

	/**
	 * The tube's shape and direction at (x, y, z): [out] = (f, t̂x, t̂y, t̂z), f(ρ) 1 inside and 0 outside, t̂ the spine's
	 * direction there (turning across each joint's plane over a wall's thickness); returns ρ, the distance to the spine.
	 */
	fun frame(x: Double, y: Double, z: Double, out: DoubleArray): Double {
		var best = Double.MAX_VALUE
		var bestSegment = 0
		var bestU = 0.0
		for (k in 0 until points - 1) {
			val ax = spine[3 * k]; val ay = spine[3 * k + 1]; val az = spine[3 * k + 2]
			val dx = spine[3 * k + 3] - ax; val dy = spine[3 * k + 4] - ay; val dz = spine[3 * k + 5] - az
			val l2 = dx * dx + dy * dy + dz * dz
			val u = if (l2 > 0) (((x - ax) * dx + (y - ay) * dy + (z - az) * dz) / l2).coerceIn(0.0, 1.0) else 0.0
			val px = x - ax - dx * u; val py = y - ay - dy * u; val pz = z - az - dz * u
			val d2 = px * px + py * py + pz * pz
			if (d2 < best) {
				best = d2
				bestSegment = k
				bestU = u
			}
		}
		// (Where along the spine: the nearest segment and how far along it, for what's given per point: [out]'s 4, 5.)
		if (out.size >= 6) {
			out[4] = bestSegment.toDouble()
			out[5] = bestU
		}
		val rho = sqrt(best)
		val f = shape(rho)
		// The segment by the joints' planes (each a smooth step across its plane), its direction blended in.
		var tx = segmentDirections[0]; var ty = segmentDirections[1]; var tz = segmentDirections[2]
		for (j in 0 until points - 2) {
			val px = spine[3 * j + 3]; val py = spine[3 * j + 4]; val pz = spine[3 * j + 5]
			val side = (x - px) * jointNormals[3 * j] + (y - py) * jointNormals[3 * j + 1] + (z - pz) * jointNormals[3 * j + 2]
			val w = 0.5 * (1.0 + tanh(sigma * side))
			val nx = segmentDirections[3 * j + 3]; val ny = segmentDirections[3 * j + 4]; val nz = segmentDirections[3 * j + 5]
			tx += (nx - tx) * w; ty += (ny - ty) * w; tz += (nz - tz) * w
		}
		val l = sqrt(tx * tx + ty * ty + tz * tz).takeIf { it > 1e-12 } ?: 1.0
		out[0] = f; out[1] = tx / l; out[2] = ty / l; out[3] = tz / l
		return rho
	}

	/** The distance to the spine and the push's whole reach: nothing past [radius] plus a few wall thicknesses. */
	private val reach = radius + 6.0 / sigma

	override fun contribute(t: Double, x: Double, y: Double, z: Double, out: FieldSample) {
		val f0 = DoubleArray(3)
		val fp = DoubleArray(3)
		val fm = DoubleArray(3)
		val rho = push(x, y, z, f0)
		if (rho > reach) return
		val v = speed
		// ∂_k F by central differences (F's shape and turn are smooth on the scale of a wall's thickness).
		val h = 0.05 / sigma
		val dF = Array(4) { DoubleArray(3) }
		for (k in 1..3) {
			push(x + if (k == 1) h else 0.0, y + if (k == 2) h else 0.0, z + if (k == 3) h else 0.0, fp)
			push(x - if (k == 1) h else 0.0, y - if (k == 2) h else 0.0, z - if (k == 3) h else 0.0, fm)
			for (i in 0..2) dF[k][i] = (fp[i] - fm[i]) / (2 * h)
		}
		for (i in 1..3) {
			val fi = f0[i - 1]
			out.add(0, i, -v * fi)
			for (k in 1..3) out.addDerivative(k, 0, i, -v * dF[k][i - 1])
			for (j in i..3) {
				val fj = f0[j - 1]
				out.add(i, j, -v * v * fi * fj)
				for (k in 1..3) out.addDerivative(k, i, j, -v * v * (dF[k][i - 1] * fj + fi * dF[k][j - 1]))
			}
		}
	}
}
