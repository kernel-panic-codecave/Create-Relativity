package net.kernelpanicsoft.relativity.client.deform

import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.sqrt

/**
 * Where a stretched body's vertices go, in camera-relative world space. The body's rest axis [up] (feet to head,
 * length [height], centred on [center]) is laid along a spine through the centre in the radial direction
 * [radial] (hole → body), [stretch] times as long, bent back against the body's sideways motion [lag] like a trailing
 * noodle; cross-sections shrink by 1/√stretch so the volume stays about the same.
 */
class NoodleWarp(
	private val center: Vector3f,
	private val up: Vector3f,
	private val height: Float,
	radial: Vector3f,
	private val stretch: Float,
	lag: Vector3f,
) {
	/** Turns the rest axis onto the spine (head outward if it was already pointing that way, else inward). */
	private val turn: Quaternionf
	private val spine = Vector3f()
	private val bendDirection = Vector3f()
	private val length = stretch * height
	private val thin = 1f / sqrt(stretch)
	private val bend: Float

	init {
		val target = Vector3f(radial)
		if (target.dot(up) < 0f) target.negate()
		// Ease into the radial alignment as stretching begins.
		val alignment = ((stretch - 1f) / 0.3f).coerceIn(0f, 1f)
		turn = Quaternionf().rotationTo(up, target).let { Quaternionf().slerp(it, alignment) }
		spine.set(up).rotate(turn)
		bendDirection.set(lag).sub(Vector3f(spine).mul(lag.dot(spine)))
		bend = if (bendDirection.lengthSquared() > 1e-6f) {
			bendDirection.normalize()
			((stretch - 1f) * 0.15f * length).coerceAtMost(0.4f * length)
		} else 0f
	}

	val active: Boolean get() = stretch > 1.001f

	/** Warps the camera-relative position (x, y, z) into [out]. */
	fun position(x: Float, y: Float, z: Float, out: Vector3f): Vector3f {
		val relX = x - center.x
		val relY = y - center.y
		val relZ = z - center.z
		val along = relX * up.x + relY * up.y + relZ * up.z
		val s = along / height // −½ … ½ from feet to head
		out.set(relX - up.x * along, relY - up.y * along, relZ - up.z * along)
		out.rotate(turn).mul(thin)
		val curve = 4f * s * s * bend
		return out.add(
			center.x + spine.x * s * length + bendDirection.x * curve,
			center.y + spine.y * s * length + bendDirection.y * curve,
			center.z + spine.z * s * length + bendDirection.z * curve,
		)
	}

	/** Turns a normal with the body. */
	fun normal(x: Float, y: Float, z: Float, out: Vector3f): Vector3f = out.set(x, y, z).rotate(turn)

	/** How finely to subdivide each model quad so it bends smoothly. */
	val subdivisions: Int get() = (stretch * 3f).toInt().coerceIn(1, 12)
}
