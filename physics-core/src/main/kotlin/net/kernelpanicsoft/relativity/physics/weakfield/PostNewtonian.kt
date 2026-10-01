package net.kernelpanicsoft.relativity.physics.weakfield

/**
 * First post-Newtonian test-particle acceleration around a point mass [m] at the origin, in harmonic
 * coordinates (`c = G = 1`): `a = −(m/r²)[(1 − 4m/r + v²) n − 4 (n·v) v]`.
 *
 * The cheap far-field path: no Christoffels, accurate to O(v⁴) and O(m²/r²). It reproduces the 6πm/p
 * perihelion advance, so orbits precess correctly long before an entity needs the full geodesic.
 */
object PostNewtonian {
	/** Writes the acceleration at position [p] with coordinate velocity [v] into [out]. */
	fun acceleration(m: Double, p: DoubleArray, v: DoubleArray, out: DoubleArray) {
		val r = kotlin.math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
		val nx = p[0] / r
		val ny = p[1] / r
		val nz = p[2] / r
		val v2 = v[0] * v[0] + v[1] * v[1] + v[2] * v[2]
		val nv = nx * v[0] + ny * v[1] + nz * v[2]
		val k = m / (r * r)
		val radial = 1.0 - 4.0 * m / r + v2
		out[0] = -k * (radial * nx - 4.0 * nv * v[0])
		out[1] = -k * (radial * ny - 4.0 * nv * v[1])
		out[2] = -k * (radial * nz - 4.0 * nv * v[2])
	}
}
