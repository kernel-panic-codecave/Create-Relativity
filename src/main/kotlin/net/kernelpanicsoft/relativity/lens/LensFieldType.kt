package net.kernelpanicsoft.relativity.lens

import net.kernelpanicsoft.relativity.spacetime.FieldSource
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import org.joml.Quaternionfc
import org.joml.Vector3dc
import org.joml.Vector3f
import java.nio.FloatBuffer

/**
 * A kind of source the lens bends light round, as a smooth field (SDF-like: soft-walled, so the light's map comes out
 * smooth and sources merge where they meet), registered in [RelativityRegistries.LENS_FIELD_TYPES] (from any mod: an
 * entry of that registry, its [glsl] file, nothing else). The lens puts every type's GLSL into one include
 * (client/lens/field/LensFields), gives each its share of the field block, and adds every source's field in at each
 * point the light reaches.
 *
 * Its GLSL file defines `void fieldSample(vec3 x, inout LensFieldSample s)` (x view space: add its part with
 * lens_fields/common.glsl's lensFieldAddIsotropic or lensFieldAddAxial, and a safe step with lensFieldStep), reading
 * its data with `fieldData(i)` (vec4 i of its share) and `fieldCount()` (how many of it there are). Every name in it
 * starting `field` and a capital letter is its own: renamed per type, so types can't clash.
 *
 * Optionally, for the tessellation stage (which splits geometry on the GPU as finely as the lens bends it), its
 * extremes, where three points of an edge can't show the bend: `float fieldPieces(vec3 a, vec3 b)`, how many pieces the
 * edge from a to b needs for it (1 none),; and `bool fieldNear(vec3 a,
 * vec3 b, vec3 c)`, whether it bends light round triangle a, b, c enough to show (without it: wherever it has sources),
 * else the stage doesn't measure the triangle's bend (a lens call a corner, which it then saves).
 */
interface LensFieldType {
	/** Its share of the field block (vec4s). */
	val slots: Int

	/** Where its code and sources go among the types' (lower first; ties by id). */
	val order: Int get() = 100

	/** Its field's GLSL: assets/<namespace>/<path> in its own mod's resources. */
	val glsl: ResourceLocation

	/** Its sources this frame into [out] (its share; view space, camera-relative): how many there are. */
	fun upload(frame: LensFrame, out: LensFieldWriter): Int
}

/**
 * This frame's view for the field types: the camera's place, the view rotation (world to view, as the shaders work),
 * the level and its field sources, and how big a pixel is (radians, for cutting curves finely enough).
 */
class LensFrame(val level: Level, val camera: Vector3dc, val view: Quaternionfc, val sources: List<FieldSource>, val pixel: Double) {
	/** World point [p] in view space (camera-relative, rotated). */
	fun toView(p: Vector3dc): Vector3f = view.transform(Vector3f((p.x() - camera.x()).toFloat(), (p.y() - camera.y()).toFloat(), (p.z() - camera.z()).toFloat()))

	/** World direction [d] in view space. */
	fun toViewDirection(d: Vector3dc): Vector3f = view.transform(Vector3f(d.x().toFloat(), d.y().toFloat(), d.z().toFloat()))
}

/** A type's share of the field block: [put] writes the next vec4 (past [slots], dropped). */
class LensFieldWriter(private val buffer: FloatBuffer, private val base: Int, val slots: Int) {
	/** How many vec4s are written. */
	var written = 0
		private set

	val remaining: Int get() = slots - written

	fun put(x: Float, y: Float, z: Float, w: Float) {
		if (written >= slots) return
		val at = 4 * (base + written)
		buffer.put(at, x).put(at + 1, y).put(at + 2, z).put(at + 3, w)
		written += 1
	}

	fun put(v: Vector3f, w: Float) = put(v.x, v.y, v.z, w)

	/** Skips to vec4 [index] of the share (the ones passed left as they are: zero). */
	fun seek(index: Int) {
		written = index.coerceIn(0, slots)
	}
}
