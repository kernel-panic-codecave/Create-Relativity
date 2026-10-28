package net.kernelpanicsoft.relativity.command

import net.kernelpanicsoft.relativity.spacetime.Cubic
import net.kernelpanicsoft.relativity.spacetime.FieldSource
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import org.joml.Vector3dc

/**
 * Tubes and bubbles placed by command (`relativity tube`, `relativity bubble`), for testing: kept by dimension, not
 * saved, and not sent to clients (in singleplayer the client's level sees them all the same: one game, one table).
 */
object TestSources {
	private val byDimension = java.util.concurrent.ConcurrentHashMap<ResourceKey<Level>, List<FieldSource>>()

	init {
		SpacetimeField.addProvider { level -> byDimension[level.dimension()] ?: emptyList() }
	}

	fun add(level: Level, source: FieldSource) {
		byDimension.merge(level.dimension(), listOf(source)) { old, new -> old + new }
	}

	/** Adds [point] to the end of the last tube placed in [level]'s dimension; false if there's none. */
	fun extend(level: Level, point: Vector3dc): Boolean {
		var extended = false
		byDimension.computeIfPresent(level.dimension()) { _, sources ->
			val last = sources.indexOfLast { it is FieldSource.Krasnikov }
			if (last < 0) return@computeIfPresent sources
			val tube = sources[last] as FieldSource.Krasnikov
			extended = true
			sources.toMutableList().also { it[last] = tube.copy(curve = tube.curve + Cubic.line(tube.curve.last().d, org.joml.Vector3d(point))) }
		}
		return extended
	}

	/** Removes [level]'s dimension's tubes (or bubbles); how many. */
	fun clear(level: Level, tubes: Boolean): Int {
		var removed = 0
		byDimension.computeIfPresent(level.dimension()) { _, sources ->
			val (gone, kept) = sources.partition { (it is FieldSource.Krasnikov) == tubes }
			removed = gone.size
			kept
		}
		return removed
	}
}
