package net.kernelpanicsoft.relativity.physics.spacetime.field

import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * An Alcubierre warp bubble with an ellipsoidal wall (round a ship: its bounds' box), centred at ([cx], [cy], [cz]) at
 * t = 0 and moving at coordinate speed [speed] (any, faster than light included) along unit direction [n]. Its
 * [axes] are three perpendicular semi-axis vectors (x, y, z triples: their lengths the semi-axes). `ds² = −dt² +
 * (dx^i − v n^i f dt)²` (Alcubierre's), f the shape on the ellipsoidal radius s (1 on the wall): a round bubble of
 * radius R is three equal semi-axes of R. [sigma] is the wall's sharpness on s (per unit of s: R times a sphere's). Not
 * [moving], it stays where it is, its push the same (frozen: the lens's view of it, BubbleModule). With [inner] > 0
 * the shape has compact support instead: exactly 1 inside ellipsoidal radius [inner] (truly flat: a ship's box inside
 * it feels nothing at any warp, where a tanh wall's tail times v² still pulls hard), easing to exactly 0 at 1 by the
 * C² smootherstep 6t⁵ − 15t⁴ + 10t³.
 */
class WarpEllipsoid(
	val cx: Double, val cy: Double, val cz: Double,
	val axes: DoubleArray,
	val n: DoubleArray,
	val speed: Double,
	val sigma: Double = 8.0,
	val moving: Boolean = true,
	val inner: Double = 0.0,
) : MetricSource {
	init {
		require(axes.size == 9 && n.size == 3) { "three semi-axes and a direction" }
	}

	private val norm = 1.0 / (2.0 * tanh(sigma))

	/** The shape function f(s): 1 inside, 0 outside (the wall at s = 1). */
	fun shape(s: Double): Double {
		if (inner <= 0.0) return (tanh(sigma * (s + 1.0)) - tanh(sigma * (s - 1.0))) * norm
		if (s <= inner) return 1.0
		if (s >= 1.0) return 0.0
		val t = (1.0 - s) / (1.0 - inner)
		return t * t * t * (t * (6.0 * t - 15.0) + 10.0)
	}

	/** Each axis's unit vector over its length (so a point's coordinate along it in semi-axes is the dot). */
	private val scaled = DoubleArray(9).also { out ->
		for (a in 0..2) {
			val x = axes[3 * a]; val y = axes[3 * a + 1]; val z = axes[3 * a + 2]
			val l2 = (x * x + y * y + z * z).takeIf { it > 0 } ?: 1.0
			out[3 * a] = x / l2; out[3 * a + 1] = y / l2; out[3 * a + 2] = z / l2
		}
	}

	/** The ellipsoidal radius of (x, y, z) at time [t]: 1 on the wall. */
	fun radius(t: Double, x: Double, y: Double, z: Double): Double {
		val travelled = if (moving) speed * t else 0.0
		val rx = x - (cx + travelled * n[0])
		val ry = y - (cy + travelled * n[1])
		val rz = z - (cz + travelled * n[2])
		var s2 = 0.0
		for (a in 0..2) {
			val u = rx * scaled[3 * a] + ry * scaled[3 * a + 1] + rz * scaled[3 * a + 2]
			s2 += u * u
		}
		return sqrt(s2)
	}

	private val reach = if (inner > 0.0) 1.0 else 1.0 + 6.0 / sigma

	override fun contribute(t: Double, x: Double, y: Double, z: Double, out: FieldSample) {
		val s = radius(t, x, y, z)
		if (s > reach) return
		val f = shape(s)
		// ∂_k f by central differences (smooth on a wall's scale); it moves, so ∂_t f = −v n·∇f.
		val shortest = (0..2).minOf { a -> sqrt(axes[3 * a] * axes[3 * a] + axes[3 * a + 1] * axes[3 * a + 1] + axes[3 * a + 2] * axes[3 * a + 2]) }
		val h = if (inner > 0.0) 0.02 * shortest * (1.0 - inner) else 0.05 * shortest / sigma
		val df = DoubleArray(4)
		for (k in 1..3) {
			val fp = shape(radius(t, x + if (k == 1) h else 0.0, y + if (k == 2) h else 0.0, z + if (k == 3) h else 0.0))
			val fm = shape(radius(t, x - if (k == 1) h else 0.0, y - if (k == 2) h else 0.0, z - if (k == 3) h else 0.0))
			df[k] = (fp - fm) / (2 * h)
		}
		df[0] = if (moving) -speed * (n[0] * df[1] + n[1] * df[2] + n[2] * df[3]) else 0.0
		val v = speed
		for (i in 1..3) {
			val ni = n[i - 1]
			out.add(0, i, -v * f * ni)
			for (k in 0..3) out.addDerivative(k, 0, i, -v * df[k] * ni)
			for (j in i..3) {
				val nn = ni * n[j - 1]
				if (nn == 0.0) continue
				out.add(i, j, -v * v * f * f * nn)
				for (k in 0..3) out.addDerivative(k, i, j, -2.0 * v * v * f * df[k] * nn)
			}
		}
	}
}
