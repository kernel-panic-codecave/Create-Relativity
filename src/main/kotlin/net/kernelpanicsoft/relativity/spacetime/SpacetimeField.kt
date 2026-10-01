package net.kernelpanicsoft.relativity.spacetime

import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.network.RelativityNetwork
import net.kernelpanicsoft.relativity.physics.geodesic.CoordinateAcceleration
import net.kernelpanicsoft.relativity.physics.geodesic.Geodesic
import net.kernelpanicsoft.relativity.physics.weakfield.PostNewtonian
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

	/** Pulls weaker than this (blocks/s², ~1/3000 of vanilla gravity) are ignored, so distant holes cost nothing. */
	private const val MIN_ACCELERATION = 0.01

	/** Beyond this many r_s the cheap 1PN acceleration replaces the exact geodesic one (error ~ (r_s/r)²). */
	private const val WEAK_FIELD_RADII = 50.0

	/** Things this deep inside the horizon (fraction of r_s, spheroidal radius) have reached the singularity. */
	const val SINGULARITY_RADIUS = 0.15

	fun blackHoles(level: Level): List<BlackHole> =
		if (level is ServerLevel) SpacetimeAttachments.blackHoles.get(level).holes else clientHoles[level.dimension()].orEmpty()

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
		val updated = change(SpacetimeAttachments.blackHoles.get(level).holes)
		SpacetimeAttachments.blackHoles.set(level, BlackHoles(updated))
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
		val p = DoubleArray(3)
		for (hole in holes) {
			position.sub(hole.x, hole.y, hole.z, rel)
			val r = rel.length()
			if (r < SINGULARITY_CUTOFF * hole.schwarzschildRadius) continue
			if (c * c * hole.mass / (r * r) < MIN_ACCELERATION) continue
			if (r > WEAK_FIELD_RADII * hole.schwarzschildRadius) {
				GameFrame.toPhysics(rel, p)
				PostNewtonian.acceleration(hole.mass, p, v, a)
			} else {
				GameFrame.toPhysics(rel, x, 1)
				CoordinateAcceleration(hole.spacetime()).compute(x, v, a)
			}
			dest.add(GameFrame.toGame(a, 0, add).mul(c * c))
		}
		return dest
	}

	/**
	 * How fast a clock at [position] moving at [velocity] (blocks/s) runs against world time: 1 far from any hole,
	 * less near one or at speed, 0 at a horizon. Several holes multiply (weak-field approximation between them);
	 * with none, it's just special-relativistic time dilation.
	 */
	fun properTimeRate(level: Level, position: Vector3dc, velocity: Vector3dc): Double {
		val c = RelativityConfig.speedOfLight(level.dimension())
		val v = DoubleArray(3)
		GameFrame.toPhysics(Vector3d(velocity).div(c), v)
		val holes = blackHoles(level)
		if (holes.isEmpty()) {
			val beta2 = minOf(v[0] * v[0] + v[1] * v[1] + v[2] * v[2], 1.0)
			return kotlin.math.sqrt(1.0 - beta2)
		}
		val x = DoubleArray(4)
		val rel = Vector3d()
		var rate = 1.0
		for ((i, hole) in holes.withIndex()) {
			position.sub(hole.x, hole.y, hole.z, rel)
			GameFrame.toPhysics(rel, x, 1)
			// Velocity time dilation is counted once, with the first hole's metric.
			val holeRate = Geodesic.properTimeRate(hole.spacetime(), x, if (i == 0) v else DoubleArray(3))
			rate *= holeRate
		}
		return rate
	}

	/** The hole whose horizon [position] is inside, if any. */
	fun insideHorizon(level: Level, position: Vector3dc): BlackHole? =
		blackHoles(level).firstOrNull { spheroidalRadius(it, position) < it.horizonRadius }

	/**
	 * The hole whose horizon the straight path [from] → [to] entered, if any: a swept test, so something moving
	 * several blocks a tick can't skip over a small horizon between ticks. Treats the horizon as a sphere of its
	 * polar (smallest) Kerr–Schild extent, r₊.
	 */
	fun crossedHorizon(level: Level, from: Vector3dc, to: Vector3dc): BlackHole? =
		blackHoles(level).firstOrNull { closestApproach(it, from, to) < it.horizonRadius }

	private fun closestApproach(hole: BlackHole, from: Vector3dc, to: Vector3dc): Double {
		val seg = Vector3d(to).sub(from)
		val rel = Vector3d(hole.x, hole.y, hole.z).sub(from)
		val len2 = seg.lengthSquared()
		val t = if (len2 > 0.0) (rel.dot(seg) / len2).coerceIn(0.0, 1.0) else 0.0
		return Vector3d(from).fma(t, seg).distance(hole.x, hole.y, hole.z)
	}

	/** The hole whose singularity [position] has reached, if any. */
	fun atSingularity(level: Level, position: Vector3dc): BlackHole? =
		blackHoles(level).firstOrNull { spheroidalRadius(it, position) < SINGULARITY_RADIUS * it.schwarzschildRadius }

	private fun spheroidalRadius(hole: BlackHole, position: Vector3dc): Double {
		val p = DoubleArray(3)
		GameFrame.toPhysics(Vector3d(position).sub(hole.x, hole.y, hole.z), p)
		return hole.spacetime().radius(p[0], p[1], p[2])
	}
}
