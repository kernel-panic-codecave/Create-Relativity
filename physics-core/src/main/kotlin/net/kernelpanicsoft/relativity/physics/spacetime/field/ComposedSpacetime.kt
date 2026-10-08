package net.kernelpanicsoft.relativity.physics.spacetime.field

import net.kernelpanicsoft.relativity.physics.spacetime.Spacetime
import net.kernelpanicsoft.relativity.physics.spacetime.gammaIndex

/**
 * The spacetime of several [sources] together: `g^{μν} = η^{μν} + Σ δg^{μν}` (see [MetricSource]). Light is followed
 * through [inverse] alone ([net.kernelpanicsoft.relativity.physics.geodesic.HamiltonianRay]); [metric] and [christoffel]
 * (for timelike geodesics and clocks) invert it numerically. Not thread-safe: scratch buffers are reused.
 */
class ComposedSpacetime(val sources: List<MetricSource>) : Spacetime {
	private val sample = FieldSample()
	private val g = DoubleArray(16)
	private val dg = Array(4) { DoubleArray(16) }
	private val tmp = DoubleArray(16)

	/** `g^{μν}` and its derivatives at [x] (t, x, y, z) into [out]. */
	fun inverse(x: DoubleArray, out: FieldSample) {
		out.flat()
		for (source in sources) source.contribute(x[0], x[1], x[2], x[3], out)
	}

	/** Whether [x] (t, x, y, z) is behind some source's horizon. */
	fun captures(x: DoubleArray): Boolean = sources.any { it.captures(x[0], x[1], x[2], x[3]) }

	override fun metric(x: DoubleArray, out: DoubleArray) {
		inverse(x, sample)
		require(invert(sample.value, out)) { "the metric is degenerate at ${x.contentToString()}" }
	}

	override fun christoffel(x: DoubleArray, out: DoubleArray) {
		inverse(x, sample)
		require(invert(sample.value, g)) { "the metric is degenerate at ${x.contentToString()}" }
		// ∂_k g_{ab} = −g_{ac} ∂_k g^{cd} g_{db}
		for (k in 0..3) {
			multiply(g, sample.d[k], tmp)
			multiply(tmp, g, dg[k])
			for (i in 0..15) dg[k][i] = -dg[k][i]
		}
		val gInv = sample.value
		for (lambda in 0..3) for (mu in 0..3) for (nu in mu..3) {
			var sum = 0.0
			for (sigma in 0..3) {
				val gi = gInv[lambda * 4 + sigma]
				if (gi == 0.0) continue
				sum += gi * (dg[mu][sigma * 4 + nu] + dg[nu][sigma * 4 + mu] - dg[sigma][mu * 4 + nu])
			}
			out[gammaIndex(lambda, mu, nu)] = 0.5 * sum
			out[gammaIndex(lambda, nu, mu)] = 0.5 * sum
		}
	}

	companion object {
		/** c = a b (4 × 4, row-major). */
		fun multiply(a: DoubleArray, b: DoubleArray, c: DoubleArray) {
			for (i in 0..3) for (j in 0..3) {
				var s = 0.0
				for (k in 0..3) s += a[i * 4 + k] * b[k * 4 + j]
				c[i * 4 + j] = s
			}
		}

		/** Inverts the 4 × 4 matrix [m] into [out] (Gauss–Jordan, partial pivoting); false if it's singular. */
		fun invert(m: DoubleArray, out: DoubleArray): Boolean {
			val a = m.copyOf()
			for (i in 0..15) out[i] = if (i % 5 == 0) 1.0 else 0.0
			for (col in 0..3) {
				var pivot = col
				for (row in col + 1..3) if (kotlin.math.abs(a[row * 4 + col]) > kotlin.math.abs(a[pivot * 4 + col])) pivot = row
				if (kotlin.math.abs(a[pivot * 4 + col]) < 1e-300) return false
				if (pivot != col) for (j in 0..3) {
					var t = a[col * 4 + j]; a[col * 4 + j] = a[pivot * 4 + j]; a[pivot * 4 + j] = t
					t = out[col * 4 + j]; out[col * 4 + j] = out[pivot * 4 + j]; out[pivot * 4 + j] = t
				}
				val inv = 1.0 / a[col * 4 + col]
				for (j in 0..3) {
					a[col * 4 + j] *= inv
					out[col * 4 + j] *= inv
				}
				for (row in 0..3) {
					if (row == col) continue
					val f = a[row * 4 + col]
					if (f == 0.0) continue
					for (j in 0..3) {
						a[row * 4 + j] -= f * a[col * 4 + j]
						out[row * 4 + j] -= f * out[col * 4 + j]
					}
				}
			}
			return true
		}
	}
}
