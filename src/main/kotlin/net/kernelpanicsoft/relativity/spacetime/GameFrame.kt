package net.kernelpanicsoft.relativity.spacetime

import org.joml.Vector3d
import org.joml.Vector3dc

/**
 * Mapping between Minecraft's axes and the physics core's. Spin axes point along physics `+z`, which should be
 * world up, so physics `(x, y, z)` = game `(z, x, y)`: a cyclic permutation, so handedness (and with it the
 * sense of rotation and frame dragging) is preserved. Positive spin turns counter-clockwise seen from above.
 */
object GameFrame {
	fun toPhysics(game: Vector3dc, out: DoubleArray, offset: Int = 0) {
		out[offset] = game.z()
		out[offset + 1] = game.x()
		out[offset + 2] = game.y()
	}

	fun toGame(physics: DoubleArray, offset: Int, dest: Vector3d): Vector3d =
		dest.set(physics[offset + 1], physics[offset + 2], physics[offset])
}
