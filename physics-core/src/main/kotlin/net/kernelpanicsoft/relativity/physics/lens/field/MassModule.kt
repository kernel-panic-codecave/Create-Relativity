package net.kernelpanicsoft.relativity.physics.lens.field

import kotlin.math.abs

/**
 * A weak-field mass [mass] (geometric, blocks; either sign) at [centre], softened by [softening] as
 * [net.kernelpanicsoft.relativity.physics.spacetime.field.PointMass] is (a Plummer sphere): weak everywhere, its
 * field turns light along each stretch. It doesn't aim: [FieldLens] finds points near it by its weak field.
 */
class MassModule(override val centre: V3, val mass: Double, val softening: Double) : LensModule {
	override val scale: Double get() = maxOf(2.0 * abs(mass), 1e-9)
	/** Turned once, where the incoming line comes nearest, by its whole deflection from where the light is on. */
	override fun pass(line: LightLine): LightLine? {
		val tc = line.along(centre)
		val far = line.at(tc + FAR)
		val turn = field(line.origin, far).first
		val bent = (line.direction + turn).normalized()
		return LightLine(if (tc > 0.0) line.at(tc) else line.origin, bent)
	}

	override fun aim(from: V3, target: V3, secondary: Boolean): V3? = null

	override fun field(a: V3, b: V3): Pair<V3, V3> = FieldLens.pointField(centre, mass, softening, a, b)

	private companion object {
		const val FAR = 1e8
	}
}
