package net.kernelpanicsoft.relativity.physics.spacetime.field

import kotlin.math.cosh
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * An Alcubierre warp bubble of radius [radius] and wall sharpness [sigma], centred at ([cx], [cy], [cz]) at t = 0 and
 * moving at coordinate speed [speed] (any, faster than light included) along unit direction ([nx], [ny], [nz]):
 * `ds² = −dt² + (dx^i − v n^i f dt)²`, f = 1 inside, 0 outside. Lapse 1 and flat space; all of it is the shift
 * `β^i = −v f n^i`, so `δg^{ti} = −v f n^i`, `δg^{ij} = −v² f² n^i n^j`. Whatever sits inside rides along in free fall
 * with no time dilation; the walls hold negative energy.
 */
class WarpBubble(
	val cx: Double, val cy: Double, val cz: Double,
	val nx: Double, val ny: Double, val nz: Double,
	val speed: Double,
	val radius: Double,
	val sigma: Double = 8.0 / radius,
) : MetricSource {
	init {
		require(kotlin.math.abs(nx * nx + ny * ny + nz * nz - 1.0) < 1e-9) { "direction must be a unit vector" }
	}

	private val norm = 1.0 / (2.0 * tanh(sigma * radius))

	/** The shape function f(r), 1 inside the bubble and 0 outside. */
	fun shape(r: Double): Double = (tanh(sigma * (r + radius)) - tanh(sigma * (r - radius))) * norm

	private fun shapeSlope(r: Double): Double {
		fun sech2(u: Double): Double = 1.0 / (cosh(u) * cosh(u))
		return sigma * (sech2(sigma * (r + radius)) - sech2(sigma * (r - radius))) * norm
	}

	override fun contribute(t: Double, x: Double, y: Double, z: Double, out: FieldSample) {
		val n = doubleArrayOf(nx, ny, nz)
		val rel = doubleArrayOf(x - (cx + speed * nx * t), y - (cy + speed * ny * t), z - (cz + speed * nz * t))
		val r = sqrt(rel[0] * rel[0] + rel[1] * rel[1] + rel[2] * rel[2])
		val f = shape(r)
		if (f == 0.0) return
		// ∂_k f = f'(r) rel_k / r; the bubble moves, so ∂_t f = −v n·∇f.
		val slope = if (r > 1e-12) shapeSlope(r) / r else 0.0
		val df = DoubleArray(4)
		for (k in 1..3) df[k] = slope * rel[k - 1]
		df[0] = -speed * (nx * df[1] + ny * df[2] + nz * df[3])
		for (i in 1..3) {
			val ni = n[i - 1]
			out.add(0, i, -speed * f * ni)
			for (k in 0..3) out.addDerivative(k, 0, i, -speed * df[k] * ni)
			for (j in i..3) {
				val nn = ni * n[j - 1]
				if (nn == 0.0) continue
				out.add(i, j, -speed * speed * f * f * nn)
				for (k in 0..3) out.addDerivative(k, i, j, -2.0 * speed * speed * f * df[k] * nn)
			}
		}
	}
}
