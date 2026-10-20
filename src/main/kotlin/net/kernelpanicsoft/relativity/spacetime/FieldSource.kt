package net.kernelpanicsoft.relativity.spacetime

import net.kernelpanicsoft.relativity.physics.spacetime.field.MetricSource
import net.kernelpanicsoft.relativity.physics.spacetime.field.PointMass
import net.kernelpanicsoft.relativity.physics.spacetime.field.WarpBubble
import net.minecraft.world.level.Level
import org.joml.Vector3d
import org.joml.Vector3dc

/**
 * Something that curves spacetime in a level, in game terms (block coordinates, game axes): a [BlackHole], a [Mass] or a
 * [Bubble]. Black holes come from their entities ([SpacetimeField.blackHoles]); everything else from
 * [SpacetimeField.addProvider] (gravity generators, warp drives, tests); [SpacetimeField.sources] gives both. Every
 * system that asks [SpacetimeField] sees them: free fall, clocks, the lens.
 */
sealed interface FieldSource {
	/** The physics-core source, centred relative to [origin] on physics axes (see [GameFrame]). */
	fun toPhysics(origin: Vector3dc): MetricSource

	/** Roughly how strongly it pulls at [position] (geometric: c² times this is blocks/s²), for leaving out faint ones. */
	fun strengthAt(position: Vector3dc): Double

	/**
	 * Sampled on the chunk-section grid ([FieldGrid]) within this many blocks of [gridCentre], 0 if never (it stays
	 * analytic everywhere: moving sources, whose grid would go stale each tick). Past the reach it's analytic too.
	 */
	val gridReach: Double get() = 0.0
	val gridCentre: Vector3dc get() = org.joml.Vector3d()

	/** The lattice spacing (blocks) a section whose nearest point is [distance] from [gridCentre] needs: finer near it. */
	fun gridSpacing(distance: Double): Double = 1.0

	/**
	 * A weak-field mass of [mass] (geometric, blocks: `M = G m / c²`) at [center], either sign: negative pushes matter and
	 * light away. [softening] keeps it finite inside the body (blocks).
	 */
	data class Mass(val center: Vector3dc, val mass: Double, val softening: Double = 1.0) : FieldSource {
		override fun toPhysics(origin: Vector3dc): MetricSource {
			val p = DoubleArray(3)
			GameFrame.toPhysics(Vector3d(center).sub(origin), p)
			return PointMass(p[0], p[1], p[2], mass, softening)
		}

		override fun strengthAt(position: Vector3dc): Double {
			val r2 = center.distanceSquared(position) + softening * softening
			return kotlin.math.abs(mass) / r2
		}

		// Sampled out to where the potential's curvature is gentle; a quarter of the core's width apart near it.
		override val gridReach: Double get() = maxOf(8.0 * softening, 16.0)
		override val gridCentre: Vector3dc get() = center
		override fun gridSpacing(distance: Double): Double = if (distance < 3.0 * softening) minOf(1.0, softening / 4.0) else 1.0
	}

	/**
	 * An Alcubierre warp bubble of [radius] blocks centred at [center], moving along unit game [direction] at [speed]
	 * times c (more than 1 is faster than light).
	 */
	data class Bubble(val center: Vector3dc, val direction: Vector3dc, val speed: Double, val radius: Double) : FieldSource {
		override fun toPhysics(origin: Vector3dc): MetricSource {
			val p = DoubleArray(3)
			val n = DoubleArray(3)
			GameFrame.toPhysics(Vector3d(center).sub(origin), p)
			GameFrame.toPhysics(Vector3d(direction).normalize(), n)
			return WarpBubble(p[0], p[1], p[2], n[0], n[1], n[2], speed, radius)
		}

		// The shift reaches about a wall's thickness past the radius; inside and on the wall it's of order speed / radius.
		override fun strengthAt(position: Vector3dc): Double =
			if (center.distance(position) < 1.5 * radius) kotlin.math.abs(speed) / radius else 0.0
	}

	/** Supplies a level's sources each time they're asked for (cheap: called per query). */
	fun interface Provider {
		fun sources(level: Level): List<FieldSource>
	}
}
