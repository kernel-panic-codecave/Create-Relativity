package net.kernelpanicsoft.relativity.lens

import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.spacetime.BlackHole
import net.minecraft.resources.ResourceLocation
import org.joml.Vector3d

/**
 * Black holes ([BlackHole]) as fields (lens_fields/hole.glsl): Schwarzschild round each in isotropic coordinates, the
 * nearest [MAX_HOLES]. Spin is left out (each as if spin 0).
 *
 * Its share: [i] hole i: xyz its centre (view space), w its mass M (blocks).
 */
object HoleField : LensFieldType {
	const val MAX_HOLES = 4

	override val slots = MAX_HOLES
	override val order = 10
	override val glsl: ResourceLocation = CreateRelativity.id("lens_fields/hole.glsl")

	override fun upload(frame: LensFrame, out: LensFieldWriter): Int {
		val holes = frame.sources.filterIsInstance<BlackHole>()
			.sortedBy { Vector3d(it.x, it.y, it.z).distance(frame.camera) }
			.take(MAX_HOLES)
		for (hole in holes) out.put(frame.toView(Vector3d(hole.x, hole.y, hole.z)), hole.mass.toFloat())
		return holes.size
	}
}
