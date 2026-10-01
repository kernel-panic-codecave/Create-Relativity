package net.kernelpanicsoft.relativity.physics.geodesic

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Adaptive Dormand–Prince 5(4) integrator for an autonomous ODE `y' = f(y)` of dimension [dim].
 *
 * Error is controlled per component against `atol + rtol·|y|`. Not thread-safe: stage buffers are reused.
 */
class Dopri5(
	val dim: Int,
	var rtol: Double = 1e-9,
	var atol: Double = 1e-12,
	private val f: (y: DoubleArray, dydt: DoubleArray) -> Unit,
) {
	/** Largest step allowed; geodesics cap this by local curvature scale. */
	var maxStep: Double = Double.POSITIVE_INFINITY

	/** Step size the controller would try next; carried between [integrate] calls. */
	var nextStep: Double = 0.0

	/**
	 * Most accepted steps one [integrate] call may take before returning early. Near a singularity the
	 * step size collapses, and without a budget a caller (or a server tick) would stall.
	 */
	var maxStepsPerCall: Long = 1_000_000

	/** Number of accepted steps since construction. */
	var acceptedSteps: Long = 0
		private set

	private val k1 = DoubleArray(dim)
	private val k2 = DoubleArray(dim)
	private val k3 = DoubleArray(dim)
	private val k4 = DoubleArray(dim)
	private val k5 = DoubleArray(dim)
	private val k6 = DoubleArray(dim)
	private val k7 = DoubleArray(dim)
	private val tmp = DoubleArray(dim)
	private val yNew = DoubleArray(dim)

	/**
	 * Advances [y] in place by at most [span] of the independent variable (must be positive).
	 *
	 * @param stepLimit optional per-step cap computed from the current state, e.g. a fraction of `r`.
	 * @param onStep called after each accepted step with the new state; returning `false` stops early.
	 * @return how much of [span] was actually covered; less than [span] if stopped or out of step budget.
	 */
	fun integrate(
		y: DoubleArray,
		span: Double,
		stepLimit: ((DoubleArray) -> Double)? = null,
		onStep: ((DoubleArray) -> Boolean)? = null,
	): Double {
		require(span > 0.0) { "span must be positive, was $span" }
		var covered = 0.0
		var steps = 0L
		var h = if (nextStep > 0.0) nextStep else min(span, 1e-3)
		f(y, k1)

		while (covered < span) {
			h = min(h, span - covered)
			h = min(h, maxStep)
			if (stepLimit != null) h = min(h, stepLimit(y))
			require(h > 1e-300) { "step size underflow at covered=$covered" }

			stage(y, h, k1, A21)
			f(tmp, k2)
			stage(y, h, k1, A31, k2, A32)
			f(tmp, k3)
			stage(y, h, k1, A41, k2, A42, k3, A43)
			f(tmp, k4)
			stage(y, h, k1, A51, k2, A52, k3, A53, k4, A54)
			f(tmp, k5)
			stage(y, h, k1, A61, k2, A62, k3, A63, k4, A64, k5, A65)
			f(tmp, k6)
			for (i in 0 until dim) {
				yNew[i] = y[i] + h * (B1 * k1[i] + B3 * k3[i] + B4 * k4[i] + B5 * k5[i] + B6 * k6[i])
			}
			f(yNew, k7)

			var errSq = 0.0
			for (i in 0 until dim) {
				val e = h * (E1 * k1[i] + E3 * k3[i] + E4 * k4[i] + E5 * k5[i] + E6 * k6[i] + E7 * k7[i])
				val scale = atol + rtol * max(abs(y[i]), abs(yNew[i]))
				errSq += (e / scale) * (e / scale)
			}
			val err = sqrt(errSq / dim)

			if (err <= 1.0 || h <= MIN_STEP) {
				covered += h
				yNew.copyInto(y)
				k7.copyInto(k1) // FSAL
				acceptedSteps++
				val factor = if (err == 0.0) MAX_GROWTH else min(MAX_GROWTH, max(MIN_SHRINK, SAFETY * err.pow(-0.2)))
				h *= factor
				nextStep = h
				if (onStep != null && !onStep(y)) return covered
				if (++steps >= maxStepsPerCall) return covered
			} else {
				h *= max(MIN_SHRINK, SAFETY * err.pow(-0.25))
			}
		}
		return covered
	}

	private fun stage(y: DoubleArray, h: Double, a: DoubleArray, ca: Double) {
		for (i in 0 until dim) tmp[i] = y[i] + h * ca * a[i]
	}

	private fun stage(y: DoubleArray, h: Double, a: DoubleArray, ca: Double, b: DoubleArray, cb: Double) {
		for (i in 0 until dim) tmp[i] = y[i] + h * (ca * a[i] + cb * b[i])
	}

	private fun stage(y: DoubleArray, h: Double, a: DoubleArray, ca: Double, b: DoubleArray, cb: Double, c: DoubleArray, cc: Double) {
		for (i in 0 until dim) tmp[i] = y[i] + h * (ca * a[i] + cb * b[i] + cc * c[i])
	}

	private fun stage(
		y: DoubleArray, h: Double,
		a: DoubleArray, ca: Double, b: DoubleArray, cb: Double, c: DoubleArray, cc: Double, d: DoubleArray, cd: Double,
	) {
		for (i in 0 until dim) tmp[i] = y[i] + h * (ca * a[i] + cb * b[i] + cc * c[i] + cd * d[i])
	}

	private fun stage(
		y: DoubleArray, h: Double,
		a: DoubleArray, ca: Double, b: DoubleArray, cb: Double, c: DoubleArray, cc: Double, d: DoubleArray, cd: Double,
		e: DoubleArray, ce: Double,
	) {
		for (i in 0 until dim) tmp[i] = y[i] + h * (ca * a[i] + cb * b[i] + cc * c[i] + cd * d[i] + ce * e[i])
	}

	private companion object {
		const val SAFETY = 0.9
		const val MIN_SHRINK = 0.2
		const val MAX_GROWTH = 5.0
		const val MIN_STEP = 1e-14

		const val A21 = 1.0 / 5.0
		const val A31 = 3.0 / 40.0
		const val A32 = 9.0 / 40.0
		const val A41 = 44.0 / 45.0
		const val A42 = -56.0 / 15.0
		const val A43 = 32.0 / 9.0
		const val A51 = 19372.0 / 6561.0
		const val A52 = -25360.0 / 2187.0
		const val A53 = 64448.0 / 6561.0
		const val A54 = -212.0 / 729.0
		const val A61 = 9017.0 / 3168.0
		const val A62 = -355.0 / 33.0
		const val A63 = 46732.0 / 5247.0
		const val A64 = 49.0 / 176.0
		const val A65 = -5103.0 / 18656.0

		const val B1 = 35.0 / 384.0
		const val B3 = 500.0 / 1113.0
		const val B4 = 125.0 / 192.0
		const val B5 = -2187.0 / 6784.0
		const val B6 = 11.0 / 84.0

		// Difference between the 5th- and 4th-order weights.
		const val E1 = 71.0 / 57600.0
		const val E3 = -71.0 / 16695.0
		const val E4 = 71.0 / 1920.0
		const val E5 = -17253.0 / 339200.0
		const val E6 = 22.0 / 525.0
		const val E7 = -1.0 / 40.0
	}
}
