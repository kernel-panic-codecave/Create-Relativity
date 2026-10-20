package net.kernelpanicsoft.relativity.physics.lens.field

import net.kernelpanicsoft.relativity.physics.lens.SchwarzschildImages
import net.kernelpanicsoft.relativity.physics.lens.SchwarzschildLens
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A non-spinning hole of Schwarzschild radius [rs] at [centre], exactly: light's orbit in the plane through the hole,
 * where it is and where it heads, from there on ([SchwarzschildLens], [SchwarzschildImages], in units of r_s).
 * Directions are coordinate tangents; a static observer's angle from the inward radial relates by
 * tan(static) = sqrt(1 - r_s / r) tan(coordinate) (proper radial length dr / sqrt(1 - r_s / r)).
 */
class HoleModule(override val centre: V3, val rs: Double) : LensModule {
	override val scale: Double get() = rs

	override fun pass(line: LightLine): LightLine? {
		val rel = line.origin - centre
		val r = rel.length
		val e1 = rel * (1.0 / r)
		val observed = toObserved(line.origin, line.direction)
		val inward = -(observed dot e1)
		val side = observed + e1 * inward // the direction's part across the radial: toward +phi
		if (side.length < 1e-12) return if (inward > 0.0) null else line // straight in, or straight out
		val e2 = side.normalized()
		val u0 = rs / r
		val psi = acos(inward.coerceIn(-1.0, 1.0))
		val escape = SchwarzschildLens.escapeAngle(u0.coerceAtMost(0.999), psi)
		if (escape.isNaN()) return null
		val out = e1 * cos(escape) + e2 * sin(escape)
		// The outgoing asymptote: impact parameter b, the light circling toward +phi (u = sin(phi_inf - phi) / b).
		val b = r * sin(psi) / sqrt(1.0 - u0)
		val nearest = centre + (e1 * sin(escape) - e2 * cos(escape)) * b
		return LightLine(FieldLens.meet(line, LightLine(nearest, out)), out)
	}

	override fun field(a: V3, b: V3): Pair<V3, V3> = FieldLens.pointField(centre, rs / 2.0, 0.0, a, b)

	/** [d] with its radial part (from the hole, at [at]) scaled by [factor] (renormalised). */
	private fun radialScaled(at: V3, d: V3, factor: Double): V3 {
		val e1 = (at - centre).normalized()
		val radial = d dot e1
		return (d + e1 * (radial * (factor - 1.0))).normalized()
	}

	override fun toCoordinate(at: V3, d: V3): V3 {
		val r = (at - centre).length
		if (r <= rs) return d
		return radialScaled(at, d, sqrt(1.0 - rs / r))
	}

	override fun toObserved(at: V3, d: V3): V3 {
		val r = (at - centre).length
		if (r <= rs) return d
		return radialScaled(at, d, 1.0 / sqrt(1.0 - rs / r))
	}

	override fun aim(from: V3, target: V3, secondary: Boolean): V3? {
		val rel = from - centre
		val r0 = rel.length
		val e1 = rel * (1.0 / r0)
		val toTarget = target - centre
		val rp = toTarget.length
		if (rp <= rs || r0 <= rs) return null
		val across = toTarget - e1 * (toTarget dot e1)
		val phi = acos(((toTarget dot e1) / rp).coerceIn(-1.0, 1.0))
		val u0 = (rs / r0).coerceAtLeast(SchwarzschildImages.MIN_U)
		val uP = (rs / rp).coerceAtLeast(SchwarzschildImages.MIN_U)
		val e2 = if (across.length > 1e-12) across.normalized() else FieldLens.tangents(e1).first
		val psi = (if (secondary) secondaryAngle(u0, uP, phi) else primaryAngle(u0, uP, phi)) ?: return null
		val side = if (secondary) -e2 else e2
		return toCoordinate(from, -e1 * cos(psi) + side * sin(psi))
	}

	/** The swept angle at which the ray leaving at [psi] reaches uP, the crossing nearest [near] (NaN if none). */
	private fun swept(u0: Double, uP: Double, psi: Double, near: Double): Double {
		val c = SchwarzschildImages.crossings(u0, psi, doubleArrayOf(uP))[0]
		return c.filter { !it.isNaN() }.minByOrNull { kotlin.math.abs(it - near) } ?: Double.NaN
	}

	/** The primary image's launch angle: the table's, refined on the exact orbit (secant on the swept angle). */
	private fun primaryAngle(u0: Double, uP: Double, phi: Double): Double? {
		val seed = SchwarzschildImages.sample(table, N0, NP, NPHI, u0, uP, phi)
		val start = if (seed.isNaN()) SchwarzschildImages.flatAngle(u0, uP, phi) else seed
		return secant(start, 1e-4) { psi -> swept(u0, uP, psi, phi) - phi }?.takeIf { it > 0.0 && it < PI }
	}

	/** The secondary image's: sweeping 2 pi - phi the other way, just outside the critical angle (secant on ln(psi - psi_c)). */
	private fun secondaryAngle(u0: Double, uP: Double, phi: Double): Double? {
		val critical = SchwarzschildImages.criticalAngle(u0)
		if (critical.isNaN()) return null
		val seed = SchwarzschildImages.sampleSecondary(secondTable, N0, NP, NPHI, u0, uP, phi)
		if (seed.isNaN()) return null
		val goal = 2.0 * PI - phi
		val s = secant(ln(seed - critical), 1e-3) { l -> swept(u0, uP, critical + exp(l), goal) - goal } ?: return null
		return critical + exp(s)
	}

	private fun secant(start: Double, h: Double, f: (Double) -> Double): Double? {
		var x0 = start
		var f0 = f(x0)
		if (f0.isNaN()) return null
		var x1 = start + h
		var f1 = f(x1)
		repeat(12) {
			if (f1.isNaN()) return null
			if (kotlin.math.abs(f1) < 1e-11 || f1 == f0) return x1
			val x2 = x1 - f1 * (x1 - x0) / (f1 - f0)
			x0 = x1; f0 = f1
			x1 = x2; f1 = f(x1)
		}
		return if (kotlin.math.abs(f1) < 1e-7) x1 else null
	}

	companion object {

		private const val N0 = 32
		private const val NP = 32
		private const val NPHI = 64

		/** Coarse tables for the seeds (the secant does the rest). */
		private val table by lazy { SchwarzschildImages.buildTable(N0, NP, NPHI, 256) }
		private val secondTable by lazy { SchwarzschildImages.buildSecondaryTable(N0, NP, NPHI, 256) }
	}
}
