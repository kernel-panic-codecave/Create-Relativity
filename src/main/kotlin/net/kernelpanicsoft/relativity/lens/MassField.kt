package net.kernelpanicsoft.relativity.lens

import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.spacetime.FieldSource
import net.minecraft.resources.ResourceLocation

/**
 * Masses ([FieldSource.Mass]) as fields (lens_fields/mass.glsl): each one's softened weak field, the [MAX_MASSES]
 * pulling hardest at the camera.
 *
 * Its share: [2i] mass i: xyz its centre (view space), w its mass (blocks); [2i + 1] x its softening.
 */
object MassField : LensFieldType {
	const val MAX_MASSES = 8

	override val slots = 2 * MAX_MASSES
	override val order = 15
	override val glsl: ResourceLocation = CreateRelativity.id("lens_fields/mass.glsl")

	override fun upload(frame: LensFrame, out: LensFieldWriter): Int {
		val masses = frame.sources.filterIsInstance<FieldSource.Mass>()
			.sortedByDescending { it.strengthAt(frame.camera) }
			.take(MAX_MASSES)
		for (mass in masses) {
			out.put(frame.toView(mass.center), mass.mass.toFloat())
			out.put(mass.softening.toFloat(), 0f, 0f, 0f)
		}
		return masses.size
	}
}
