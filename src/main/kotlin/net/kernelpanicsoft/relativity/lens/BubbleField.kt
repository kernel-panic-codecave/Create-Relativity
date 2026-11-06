package net.kernelpanicsoft.relativity.lens

import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.spacetime.FieldSource
import net.minecraft.resources.ResourceLocation
import org.joml.Vector3d

/**
 * Warp bubbles ([FieldSource.Ellipsoid]: ships', round ones with three equal axes) as fields (lens_fields/bubble.glsl),
 * frozen where they are, the nearest [MAX_BUBBLES].
 *
 * Its share: [5e] bubble e: xyz its centre (view space), w its speed; [5e + 1..3] its semi-axes (view space), each
 * over its length squared, w its length; [5e + 4] xyz its push's direction, reversed (light followed arriving), w its
 * inner radius (0: the tanh wall).
 */
object BubbleField : LensFieldType {
	const val MAX_BUBBLES = 4

	override val slots = 5 * MAX_BUBBLES
	override val order = 30
	override val glsl: ResourceLocation = CreateRelativity.id("lens_fields/bubble.glsl")

	override fun upload(frame: LensFrame, out: LensFieldWriter): Int {
		val shapes = frame.sources.filterIsInstance<FieldSource.Ellipsoid>().sortedBy { it.center.distance(frame.camera) }.take(MAX_BUBBLES)
		for (shape in shapes) {
			out.put(frame.toView(shape.center), shape.speed.toFloat())
			for (axis in shape.axes) {
				val length = axis.length()
				out.put(frame.toViewDirection(Vector3d(axis).div(length * length)), length.toFloat())
			}
			out.put(frame.toViewDirection(Vector3d(shape.direction).normalize().negate()), shape.inner.toFloat())
		}
		return shapes.size
	}
}
