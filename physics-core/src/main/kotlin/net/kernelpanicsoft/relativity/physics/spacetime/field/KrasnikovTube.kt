package net.kernelpanicsoft.relativity.physics.spacetime.field

/**
 * A Krasnikov tube (Krasnikov 1995; Everett & Roman 1997) of [radius] round the polyline [spine] (x, y, z triples):
 * static, its light cones tipped toward the spine's direction t̂. Along t̂ (s), `ds² = −dt² + (1 − k) dt ds + k ds²`
 * (flat across), k 1 outside and [k] in (0, 1] inside, blended by the tube's shape f: `k = 1 − (1 − k_in) f`. Light
 * inside runs along t̂ at 1 / k (instant as k → 0) and back at 1; at k ≤ 0 the cone would tip past flat (light reaching
 * its own past), which k in (0, 1] rules out: no time travel.
 *
 * Inverse metric along t̂: g^tt = −4k / (1 + k)², g^ts = −2 (1 − k) / (1 + k)², g^ss = 4 / (1 + k)².
 *
 * Its tilt may change along it ([tilts]: k at each spine point, linear between along the nearest segment), as a tube
 * easing back to flat toward where its train stops does.
 */
class KrasnikovTube(
	spine: DoubleArray,
	val k: Double,
	val radius: Double,
	sigma: Double = 8.0 / radius,
	val tilts: DoubleArray? = null,
) : MetricSource {
	init {
		require(k > 0.0 && k <= 1.0) { "k must be in (0, 1]: at 0 light is instant, past it it reaches its past" }
		require(tilts == null || (tilts.size == spine.size / 3 && tilts.all { it > 0.0 && it <= 1.0 })) { "tilts: one in (0, 1] a spine point" }
	}

	/** The tube's geometry (its shape and the spine's direction), shared with the warp tube. */
	private val geometry = WarpTube(spine, 0.0, radius, sigma)

	private val reach = radius + 6.0 / sigma
	private val h = 0.05 / sigma

	/** δg^{μν} at (x, y, z) into [out] (10: tt, tx, ty, tz, xx, xy, xz, yy, yz, zz); false if there's none. */
	private fun delta(x: Double, y: Double, z: Double, out: DoubleArray): Boolean {
		val frame = DoubleArray(6)
		if (geometry.frame(x, y, z, frame) > reach) {
			out.fill(0.0)
			return false
		}
		val inside = if (tilts == null) k else {
			val i = frame[4].toInt()
			tilts[i] + (tilts[minOf(i + 1, tilts.size - 1)] - tilts[i]) * frame[5]
		}
		val kk = 1.0 - (1.0 - inside) * frame[0]
		val q = 1.0 / ((1.0 + kk) * (1.0 + kk))
		val tt = -4.0 * kk * q + 1.0
		val ts = -2.0 * (1.0 - kk) * q
		val ss = 4.0 * q - 1.0
		val t = doubleArrayOf(frame[1], frame[2], frame[3])
		out[0] = tt
		for (i in 0..2) out[1 + i] = ts * t[i]
		var n = 4
		for (i in 0..2) for (j in i..2) out[n++] = ss * t[i] * t[j]
		return true
	}

	override fun contribute(t: Double, x: Double, y: Double, z: Double, out: FieldSample) {
		val g = DoubleArray(10)
		if (!delta(x, y, z, g)) return
		val gp = DoubleArray(10)
		val gm = DoubleArray(10)
		val d = Array(4) { DoubleArray(10) }
		for (axis in 1..3) {
			delta(x + if (axis == 1) h else 0.0, y + if (axis == 2) h else 0.0, z + if (axis == 3) h else 0.0, gp)
			delta(x - if (axis == 1) h else 0.0, y - if (axis == 2) h else 0.0, z - if (axis == 3) h else 0.0, gm)
			for (c in 0 until 10) d[axis][c] = (gp[c] - gm[c]) / (2 * h)
		}
		out.add(0, 0, g[0])
		for (a in 0..3) out.addDerivative(a, 0, 0, d[a][0])
		for (i in 1..3) {
			out.add(0, i, g[i])
			for (a in 0..3) out.addDerivative(a, 0, i, d[a][i])
		}
		var n = 4
		for (i in 1..3) for (j in i..3) {
			out.add(i, j, g[n])
			for (a in 0..3) out.addDerivative(a, i, j, d[a][n])
			n++
		}
	}
}
