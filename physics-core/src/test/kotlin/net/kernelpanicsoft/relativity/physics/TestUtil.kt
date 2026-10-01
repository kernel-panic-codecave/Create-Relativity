package net.kernelpanicsoft.relativity.physics

import kotlin.math.abs
import kotlin.test.assertTrue

fun assertClose(expected: Double, actual: Double, relative: Double, message: String = "") {
	val err = abs(actual - expected) / maxOf(abs(expected), 1e-300)
	assertTrue(err <= relative, "$message expected $expected, got $actual (relative error $err > $relative)")
}

fun radius(state: DoubleArray) = kotlin.math.sqrt(state[1] * state[1] + state[2] * state[2] + state[3] * state[3])
