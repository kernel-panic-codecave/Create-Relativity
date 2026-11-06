package net.kernelpanicsoft.relativity.lens

import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.spacetime.FieldSource
import net.minecraft.resources.ResourceLocation
import org.joml.Vector3d
import org.joml.Vector3dc

/**
 * Warp bubbles as fields (lens_fields/bubble.glsl): ships' ellipsoids ([FieldSource.Ellipsoid]) and round bubbles
 * ([FieldSource.Bubble], an ellipsoid with three equal axes), frozen where they are, the nearest [MAX_BUBBLES].
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

	private class Shape(val center: Vector3dc, val axes: List<Vector3dc>, val direction: Vector3dc, val speed: Double, val inner: Double)

	override fun upload(frame: LensFrame, out: LensFieldWriter): Int {
		val shapes = frame.sources.mapNotNull { source ->
			when (source) {
				is FieldSource.Ellipsoid -> Shape(source.center, source.axes, source.direction, source.speed, source.inner)
				is FieldSource.Bubble -> Shape(source.center, listOf(Vector3d(source.radius, 0.0, 0.0), Vector3d(0.0, source.radius, 0.0), Vector3d(0.0, 0.0, source.radius)), source.direction, source.speed, 0.0)
				else -> null
			}
		}.sortedBy { it.center.distance(frame.camera) }.take(MAX_BUBBLES)
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
