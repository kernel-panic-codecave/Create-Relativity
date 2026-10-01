package net.kernelpanicsoft.relativity.spacetime

import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.network.RelativityNetwork
import net.kernelpanicsoft.relativity.physics.geodesic.CoordinateAcceleration
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import org.joml.Vector3d
import org.joml.Vector3dc

/**
 * The one place the rest of the mod asks what spacetime is doing at a point. Server levels read the
 * persisted sources; client levels read what [RelativityNetwork] last synced.
 */
object SpacetimeField {
	@Volatile
	private var clientHoles: Map<ResourceKey<Level>, List<BlackHole>> = emptyMap()

	/**
	 * Inside this fraction of `r_s` the field isn't evaluated: the point is well inside the horizon, where
	 * anything is already captured, and the connection diverges toward the singularity.
	 */
	private const val SINGULARITY_CUTOFF = 0.1

	fun blackHoles(level: Level): List<BlackHole> =
		if (level is ServerLevel) SpacetimeAttachments.blackHoles.get(level) else clientHoles[level.dimension()].orEmpty()

	fun addBlackHole(level: ServerLevel, hole: BlackHole) = update(level) { it + hole }

	/** Replaces the hole with [id], if it still exists, by [change] applied to it. */
	fun updateBlackHole(level: ServerLevel, id: String, change: (BlackHole) -> BlackHole) =
		update(level) { holes -> holes.map { if (it.id == id) change(it) else it } }

	fun removeBlackHoles(level: ServerLevel, predicate: (BlackHole) -> Boolean): Int {
		var removed = 0
		update(level) { holes -> holes.filterNot(predicate).also { removed = holes.size - it.size } }
		return removed
	}

	private fun update(level: ServerLevel, change: (List<BlackHole>) -> List<BlackHole>) {
		val updated = change(SpacetimeAttachments.blackHoles.get(level))
		SpacetimeAttachments.blackHoles.set(level, updated)
		RelativityNetwork.syncToDimension(level, updated)
	}

	internal fun setClientHoles(dimension: ResourceKey<Level>, holes: List<BlackHole>) {
		clientHoles = clientHoles + (dimension to holes)
	}

	internal fun clearClient() {
		clientHoles = emptyMap()
	}

	/**
	 * Coordinate acceleration (blocks/s²) of a free body at [position] (blocks) moving at [velocity] (blocks/s):
	 * the exact geodesic acceleration of each hole, summed. Summing is exact near one hole and the usual
	 * weak-field approximation between several. Writes into [dest] and returns it; zero with no sources.
	 */
	fun acceleration(level: Level, position: Vector3dc, velocity: Vector3dc, dest: Vector3d): Vector3d {
		dest.zero()
		val holes = blackHoles(level)
		if (holes.isEmpty()) return dest
		val c = RelativityConfig.speedOfLight(level.dimension())
		val x = DoubleArray(4)
		val v = DoubleArray(3)
		val a = DoubleArray(3)
		val rel = Vector3d()
		val add = Vector3d()
		GameFrame.toPhysics(Vector3d(velocity).div(c), v)
		for (hole in holes) {
			position.sub(hole.x, hole.y, hole.z, rel)
			if (rel.length() < SINGULARITY_CUTOFF * hole.schwarzschildRadius) continue
			GameFrame.toPhysics(rel, x, 1)
			CoordinateAcceleration(hole.spacetime()).compute(x, v, a)
			dest.add(GameFrame.toGame(a, 0, add).mul(c * c))
		}
		return dest
	}
}
