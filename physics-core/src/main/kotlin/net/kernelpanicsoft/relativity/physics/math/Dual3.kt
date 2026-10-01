package net.kernelpanicsoft.relativity.physics.math

import kotlin.math.sqrt

/**
 * A value carrying its exact gradient with respect to the three spatial coordinates (forward-mode
 * automatic differentiation). Metrics written over [Dual3] get exact first derivatives for free.
 */
class Dual3(@JvmField val v: Double, @JvmField val dx: Double, @JvmField val dy: Double, @JvmField val dz: Double) {
	/** Partial derivative along spatial axis [axis] (0 = x, 1 = y, 2 = z). */
	fun d(axis: Int): Double = when (axis) {
		0 -> dx
		1 -> dy
		else -> dz
	}

	operator fun plus(o: Dual3) = Dual3(v + o.v, dx + o.dx, dy + o.dy, dz + o.dz)
	operator fun plus(c: Double) = Dual3(v + c, dx, dy, dz)
	operator fun minus(o: Dual3) = Dual3(v - o.v, dx - o.dx, dy - o.dy, dz - o.dz)
	operator fun minus(c: Double) = Dual3(v - c, dx, dy, dz)
	operator fun unaryMinus() = Dual3(-v, -dx, -dy, -dz)
	operator fun times(o: Dual3) = Dual3(v * o.v, dx * o.v + v * o.dx, dy * o.v + v * o.dy, dz * o.v + v * o.dz)
	operator fun times(c: Double) = Dual3(v * c, dx * c, dy * c, dz * c)
	operator fun div(c: Double) = times(1.0 / c)

	operator fun div(o: Dual3): Dual3 {
		val inv = 1.0 / o.v
		val q = v * inv
		return Dual3(q, (dx - q * o.dx) * inv, (dy - q * o.dy) * inv, (dz - q * o.dz) * inv)
	}


	fun sqrt(): Dual3 {
		val s = sqrt(v)
		val k = 0.5 / s
		return Dual3(s, dx * k, dy * k, dz * k)
	}

	companion object {
		fun constant(c: Double) = Dual3(c, 0.0, 0.0, 0.0)
		fun x(value: Double) = Dual3(value, 1.0, 0.0, 0.0)
		fun y(value: Double) = Dual3(value, 0.0, 1.0, 0.0)
		fun z(value: Double) = Dual3(value, 0.0, 0.0, 1.0)
	}
}

operator fun Double.plus(d: Dual3) = d + this
operator fun Double.minus(d: Dual3) = -d + this
operator fun Double.times(d: Dual3) = d * this
operator fun Double.div(d: Dual3) = Dual3.constant(this) / d
