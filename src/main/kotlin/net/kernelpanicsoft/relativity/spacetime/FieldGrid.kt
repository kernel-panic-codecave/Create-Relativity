package net.kernelpanicsoft.relativity.spacetime

import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.physics.spacetime.field.FieldSample
import net.kernelpanicsoft.relativity.physics.spacetime.field.MetricSource
import net.kernelpanicsoft.relativity.physics.spacetime.field.SampledField
import net.minecraft.core.SectionPos
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import org.joml.Vector3d
import org.joml.Vector3dc
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * The metric field sampled per chunk section (M10): for every section within some gridded source's reach
 * ([FieldSource.gridReach]), one [SampledField] of all the gridded sources reaching it, on physics axes relative to the
 * section's corner, built off-thread and rebuilt when the sources change. Whatever their shape or number, those
 * sources then cost one lattice lookup per query. Holes and moving sources stay analytic (no affordable lattice
 * resolves a horizon; a moving source's would go stale every tick).
 */
object FieldGrid {
	/** A section's lattice and which sources it holds. */
	class Section(val origin: Vector3dc, val field: SampledField, val sources: Set<FieldSource>)

	private class LevelGrid {
		@Volatile var key: List<FieldSource> = emptyList()
		val sections = ConcurrentHashMap<Long, Section>()
		var generation = 0
	}

	private val grids: MutableMap<Level, LevelGrid> = java.util.Collections.synchronizedMap(java.util.WeakHashMap())

	/** Debug (GameTests): sections built so far for [level]. */
	fun builtSections(level: Level): Int = grids[level]?.sections?.size ?: 0

	/**
	 * The section lattice holding [position], if built for the current [sources], with what it covers. Starts (or
	 * restarts) the builds when the gridded sources have changed.
	 */
	fun sectionAt(level: Level, sources: List<FieldSource>, position: Vector3dc): Section? {
		val gridded = sources.filter { it.gridReach > 0.0 }
		val grid = synchronized(grids) { grids.getOrPut(level) { LevelGrid() } }
		if (gridded != grid.key) rebuild(grid, gridded)
		if (gridded.isEmpty()) return null
		val key = SectionPos.asLong(
			SectionPos.blockToSectionCoord(Mth.floor(position.x())),
			SectionPos.blockToSectionCoord(Mth.floor(position.y())),
			SectionPos.blockToSectionCoord(Mth.floor(position.z())),
		)
		return grid.sections[key]
	}

	private fun rebuild(grid: LevelGrid, gridded: List<FieldSource>) {
		synchronized(grid) {
			if (gridded == grid.key) return
			grid.key = gridded
			grid.sections.clear()
			val generation = ++grid.generation
			if (gridded.isEmpty()) return
			// Every section any gridded source reaches, with the sources reaching it.
			val wanted = HashMap<Long, MutableList<FieldSource>>()
			for (source in gridded) {
				val c = source.gridCentre
				val reach = source.gridReach
				for (sx in SectionPos.blockToSectionCoord(Mth.floor(c.x() - reach))..SectionPos.blockToSectionCoord(Mth.floor(c.x() + reach)))
					for (sy in SectionPos.blockToSectionCoord(Mth.floor(c.y() - reach))..SectionPos.blockToSectionCoord(Mth.floor(c.y() + reach)))
						for (sz in SectionPos.blockToSectionCoord(Mth.floor(c.z() - reach))..SectionPos.blockToSectionCoord(Mth.floor(c.z() + reach))) {
							if (distanceToSection(c, sx, sy, sz) > reach) continue
							wanted.getOrPut(SectionPos.asLong(sx, sy, sz)) { ArrayList() } += source
						}
			}
			for ((key, sources) in wanted) {
				CompletableFuture.supplyAsync({ build(key, sources) }, net.minecraft.Util.backgroundExecutor()).thenAccept { section ->
					if (grid.generation == generation) grid.sections[key] = section
				}.exceptionally { error ->
					CreateRelativity.LOGGER.error("Couldn't sample the metric field at section {}", SectionPos.of(key), error)
					null
				}
			}
		}
	}

	private fun distanceToSection(c: Vector3dc, sx: Int, sy: Int, sz: Int): Double {
		val dx = maxOf(sx * 16.0 - c.x(), 0.0, c.x() - (sx * 16.0 + 16.0))
		val dy = maxOf(sy * 16.0 - c.y(), 0.0, c.y() - (sy * 16.0 + 16.0))
		val dz = maxOf(sz * 16.0 - c.z(), 0.0, c.z() - (sz * 16.0 + 16.0))
		return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
	}

	private fun build(key: Long, sources: List<FieldSource>): Section {
		val section = SectionPos.of(key)
		val origin = Vector3d(section.minBlockX().toDouble(), section.minBlockY().toDouble(), section.minBlockZ().toDouble())
		val spacing = sources.minOf { it.gridSpacing(distanceToSection(it.gridCentre, section.x(), section.y(), section.z())) }
		val nodes = kotlin.math.ceil(16.0 / spacing).toInt() + 1
		val step = 16.0 / (nodes - 1)
		// Physics axes are game (z, x, y): the lattice over the section, relative to its corner.
		val physics = sources.map { it.toPhysics(origin) }
		val field = SampledField(0.0, 0.0, 0.0, step, nodes, nodes, nodes, physics)
		return Section(origin, field, sources.toSet())
	}

	/** [field] seen from a point [shift] (physics axes) away from its lattice's corner: queries there are at the origin. */
	class Shifted(private val field: MetricSource, private val sx: Double, private val sy: Double, private val sz: Double) : MetricSource {
		override fun contribute(t: Double, x: Double, y: Double, z: Double, out: FieldSample) = field.contribute(t, x + sx, y + sy, z + sz, out)
		override fun captures(t: Double, x: Double, y: Double, z: Double) = field.captures(t, x + sx, y + sy, z + sz)
	}
}
