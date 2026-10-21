package net.kernelpanicsoft.relativity.physics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class WarpScaleTest {
	@Test
	fun theNextGenerationsScale() {
		assertTrue(abs(WarpScale.speed(1.0) - 1.0) < 1e-12, "warp 1 is light speed")
		assertTrue(abs(WarpScale.speed(9.0) - 1516.38) < 0.1, "warp 9 is ${WarpScale.speed(9.0)} c")
		// Continuous at the bend, rising without bound toward 10.
		assertTrue(abs(WarpScale.speed(9.0 + 1e-9) - WarpScale.speed(9.0)) < 1e-3)
		assertTrue(WarpScale.speed(9.9) > 100 * WarpScale.speed(9.0))
		assertTrue(WarpScale.speed(9.9999) > WarpScale.speed(9.999))
		for (w in listOf(0.5, 1.0, 3.3, 8.0, 9.0, 9.5, 9.99)) {
			assertTrue(abs(WarpScale.factor(WarpScale.speed(w)) - w) < 1e-9, "factor(speed($w)) = ${WarpScale.factor(WarpScale.speed(w))}")
		}
	}
}
