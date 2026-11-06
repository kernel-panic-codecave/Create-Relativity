package net.kernelpanicsoft.relativity.lens

import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.spacetime.FieldSource
import net.kernelpanicsoft.relativity.spacetime.along
import net.kernelpanicsoft.relativity.spacetime.samples
import net.minecraft.resources.ResourceLocation
import kotlin.math.sqrt

/**
 * Krasnikov tubes ([FieldSource.Krasnikov]) as smooth fields (lens_fields/tube.glsl): the tilt eased in across a wall
 * as the physics has it (KrasnikovTube), the axis along the spine. Each tube's curve cut into points for this view
 * (about [PIXELS] off the curve at its distance, coarser all round if they don't all fit), nearest tubes first.
 *
 * Its share: [0] x tubes, y points; [1 + j] tube j's x radius, y first point, z points; [1 + MAX_TUBES + i] point i:
 * xyz view space, w the tube's tilt k there. Each spine reversed (light followed arriving: the field runs the other way).
 */
object TubeField : LensFieldType {
	const val MAX_TUBES = 4
	const val MAX_POINTS = 64

	/** How far (pixels) the cut may stray from a tube's curve. */
	private const val PIXELS = 2.0

	/** The finest cut (blocks). */
	private const val FINEST = 0.05

	override val slots = 1 + MAX_TUBES + MAX_POINTS
	override val order = 20
	override val glsl: ResourceLocation = CreateRelativity.id("lens_fields/tube.glsl")

	override fun upload(frame: LensFrame, out: LensFieldWriter): Int {
		val camera = frame.camera
		fun distance(x: Double, y: Double, z: Double) = sqrt((x - camera.x()) * (x - camera.x()) + (y - camera.y()) * (y - camera.y()) + (z - camera.z()) * (z - camera.z()))
		val tubes = frame.sources.filterIsInstance<FieldSource.Krasnikov>()
			.sortedBy { tube -> tube.spine.minOf { distance(it.x(), it.y(), it.z()) } }
			.take(MAX_TUBES)
		var scale = 1.0
		var cut = emptyList<List<Pair<org.joml.Vector3dc, Double>>>()
		for (attempt in 0 until 10) {
			cut = tubes.map { tube ->
				samples(tube.curve, { p -> maxOf(FINEST, PIXELS * frame.pixel * distance(p.x(), p.y(), p.z())) * scale })
					.map { (p, at) -> p to along(tube.tilts, at) }
					// (Reversed: the lens follows light from the camera back the way it came, and turning time round turns a
					// tube's tilt round.)
					.asReversed()
			}
			if (cut.sumOf { it.size } <= MAX_POINTS) break
			scale *= 2.0
		}
		var points = 0
		val kept = cut.takeWhile { points += it.size; points <= MAX_POINTS }
		out.put(kept.size.toFloat(), kept.sumOf { it.size }.toFloat(), 0f, 0f)
		var first = 0
		for ((j, spine) in kept.withIndex()) {
			out.put(tubes[j].radius.toFloat(), first.toFloat(), spine.size.toFloat(), 0f)
			first += spine.size
		}
		out.seek(1 + MAX_TUBES)
		for (spine in kept) for ((p, tilt) in spine) out.put(frame.toView(p), tilt.toFloat())
		return kept.size
	}
}
