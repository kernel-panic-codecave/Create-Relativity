package net.kernelpanicsoft.relativity.physics.spacetime

/** Flat spacetime in Cartesian coordinates `(t, x, y, z)`, signature `(−, +, +, +)`. */
object Minkowski : Spacetime {
	override fun metric(x: DoubleArray, out: DoubleArray) {
		out.fill(0.0)
		out[0] = -1.0
		out[5] = 1.0
		out[10] = 1.0
		out[15] = 1.0
	}

	override fun christoffel(x: DoubleArray, out: DoubleArray) = out.fill(0.0)
}
