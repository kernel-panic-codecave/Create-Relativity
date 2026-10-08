package net.kernelpanicsoft.relativity.physics.spacetime.field

import kotlin.math.floor

/**
 * Sources sampled on a grid: [sources]' `δg^{μν}` and its spatial gradient at the nodes of an `nx × ny × nz` lattice
 * with spacing [spacing] from corner ([x0], [y0], [z0]), evaluated once at time [time] (the sources are taken as still:
 * moving ones stay analytic). Between nodes, cubic Hermite along each axis through the nodes' values and derivatives
 * (blended across the cell's other two axes), so gradients, which bend light, err as the cube of the spacing; outside
 * the lattice it contributes nothing (the caller covers that space otherwise).
 *
 * Whatever its sources, a sampled region costs the same per lookup: one trilinear fetch of 40 numbers. That's what lets
 * sources of any shape (a generator's field, many masses) share one path for physics and light.
 */
class SampledField(
	val x0: Double, val y0: Double, val z0: Double,
	val spacing: Double,
	val nx: Int, val ny: Int, val nz: Int,
	sources: List<MetricSource>,
	time: Double = 0.0,
) : MetricSource {
	/** Per node: the 10 independent components (see [COMPONENTS]), then their x, y and z derivatives. */
	val data = DoubleArray(nx * ny * nz * STRIDE)

	init {
		require(nx >= 2 && ny >= 2 && nz >= 2) { "a lattice needs at least 2 nodes per axis" }
		val sample = FieldSample()
		for (k in 0 until nz) for (j in 0 until ny) for (i in 0 until nx) {
			sample.clear()
			for (source in sources) source.contribute(time, x0 + i * spacing, y0 + j * spacing, z0 + k * spacing, sample)
			val base = index(i, j, k)
			for ((c, mn) in COMPONENTS.withIndex()) {
				data[base + c] = sample.value[mn]
				for (axis in 0..2) data[base + 10 * (axis + 1) + c] = sample.d[axis + 1][mn]
			}
		}
	}

	private fun index(i: Int, j: Int, k: Int) = ((k * ny + j) * nx + i) * STRIDE

	/** Whether (x, y, z) is inside the lattice. */
	fun contains(x: Double, y: Double, z: Double): Boolean {
		val u = (x - x0) / spacing
		val v = (y - y0) / spacing
		val w = (z - z0) / spacing
		return u >= 0.0 && v >= 0.0 && w >= 0.0 && u <= nx - 1.0 && v <= ny - 1.0 && w <= nz - 1.0
	}

	override fun contribute(t: Double, x: Double, y: Double, z: Double, out: FieldSample) {
		if (!contains(x, y, z)) return
		val u = (x - x0) / spacing
		val v = (y - y0) / spacing
		val w = (z - z0) / spacing
		val i = floor(u).toInt().coerceIn(0, nx - 2)
		val j = floor(v).toInt().coerceIn(0, ny - 2)
		val k = floor(w).toInt().coerceIn(0, nz - 2)
		val fu = u - i
		val fv = v - j
		val fw = w - k
		val f = doubleArrayOf(fu, fv, fw)
		val value = DoubleArray(10)
		val gradient = Array(3) { DoubleArray(10) }
		// Along each axis a: a cubic Hermite through each of the 4 lattice edges parallel to it (the corner values and
		// their derivatives along a), blended bilinearly across; its slope is the gradient's a component. The value is
		// the mean of the three axes' Hermite values. Exact for cubics along each axis: gradients err as h^3, not h.
		for (axis in 0..2) {
			val t = f[axis]
			val t2 = t * t
			val t3 = t2 * t
			val h00 = 2 * t3 - 3 * t2 + 1
			val h10 = t3 - 2 * t2 + t
			val h01 = -2 * t3 + 3 * t2
			val h11 = t3 - t2
			val d00 = (6 * t2 - 6 * t) / spacing
			val d10 = 3 * t2 - 4 * t + 1
			val d01 = (-6 * t2 + 6 * t) / spacing
			val d11 = 3 * t2 - 2 * t
			val others = (0..2).filter { it != axis }
			for (edge in 0..3) {
				val o0 = edge and 1
				val o1 = (edge shr 1) and 1
				val weight = (if (o0 == 1) f[others[0]] else 1 - f[others[0]]) * (if (o1 == 1) f[others[1]] else 1 - f[others[1]])
				if (weight == 0.0) continue
				val offset = IntArray(3)
				offset[others[0]] = o0
				offset[others[1]] = o1
				val a = index(i + offset[0], j + offset[1], k + offset[2])
				offset[axis] = 1
				val b = index(i + offset[0], j + offset[1], k + offset[2])
				for (c in 0 until 10) {
					val ga = data[a + 10 * (axis + 1) + c] * spacing
					val gb = data[b + 10 * (axis + 1) + c] * spacing
					value[c] += weight * (h00 * data[a + c] + h10 * ga + h01 * data[b + c] + h11 * gb) / 3.0
					gradient[axis][c] += weight * (d00 * data[a + c] + d10 * ga / spacing + d01 * data[b + c] + d11 * gb / spacing)
				}
			}
		}
		for ((c, mn) in COMPONENTS.withIndex()) {
			val mu = mn / 4
			val nu = mn % 4
			out.add(mu, nu, value[c])
			for (axis in 0..2) out.addDerivative(axis + 1, mu, nu, gradient[axis][c])
		}
	}

	companion object {
		/** The independent components of a symmetric 4 × 4, as flat indices μ * 4 + ν with μ ≤ ν. */
		val COMPONENTS = intArrayOf(0, 1, 2, 3, 5, 6, 7, 10, 11, 15)

		/** Numbers per node: 10 values and their 30 spatial derivatives. */
		const val STRIDE = 40
	}
}
