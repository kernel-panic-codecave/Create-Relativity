package net.kernelpanicsoft.relativity.spacetime

import net.kernelpanicsoft.relativity.physics.spacetime.field.MetricSource
import net.kernelpanicsoft.relativity.physics.spacetime.field.PointMass
import net.kernelpanicsoft.relativity.physics.spacetime.field.WarpBubble
import net.kernelpanicsoft.relativity.physics.spacetime.field.WarpEllipsoid
import net.kernelpanicsoft.relativity.physics.spacetime.field.WarpTube
import net.minecraft.world.level.Level
import org.joml.Vector3d
import org.joml.Vector3dc

/**
 * Something that curves spacetime in a level, in game terms (block coordinates, game axes): a [BlackHole], a [Mass], a
 * [Bubble], a [Tube] or an [Ellipsoid]. Black holes come from their entities ([SpacetimeField.blackHoles]); everything else from
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

	/**
	 * A warp tube ([WarpTube]): a warp field of [radius] blocks round the polyline [spine] (game coordinates), pushing at
	 * [speed] times c along it (more than 1 is faster than light): a maglev train's, round its carriages and bending
	 * with its track.
	 */
	data class Tube(val spine: List<Vector3dc>, val speed: Double, val radius: Double) : FieldSource {
		override fun toPhysics(origin: Vector3dc): MetricSource {
			val flat = DoubleArray(3 * spine.size)
			val p = DoubleArray(3)
			for ((i, point) in spine.withIndex()) {
				GameFrame.toPhysics(Vector3d(point).sub(origin), p)
				flat[3 * i] = p[0]; flat[3 * i + 1] = p[1]; flat[3 * i + 2] = p[2]
			}
			return WarpTube(flat, speed, radius)
		}

		/** Its distance from [position] (to the nearest point of the spine). */
		fun distance(position: Vector3dc): Double {
			var best = Double.MAX_VALUE
			for (k in 0 until spine.size - 1) {
				val a = spine[k]
				val d = Vector3d(spine[k + 1]).sub(a)
				val l2 = d.lengthSquared()
				val u = if (l2 > 0) (Vector3d(position).sub(a).dot(d) / l2).coerceIn(0.0, 1.0) else 0.0
				best = minOf(best, Vector3d(a).fma(u, d).distance(position))
			}
			return best
		}

		// As a bubble's: of order speed / radius within a wall's reach of it, nothing past.
		override fun strengthAt(position: Vector3dc): Double =
			if (distance(position) < 1.5 * radius) kotlin.math.abs(speed) / radius else 0.0
	}

	/**
	 * A warp bubble with an ellipsoidal wall ([WarpEllipsoid]): a warp-driven ship's, round its bounds. Centred at
	 * [center], its three semi-axis vectors [axes] (game axes, their lengths the semi-axes), moving along unit game
	 * [direction] at [speed] times c.
	 */
	data class Ellipsoid(val center: Vector3dc, val axes: List<Vector3dc>, val direction: Vector3dc, val speed: Double) : FieldSource {
		override fun toPhysics(origin: Vector3dc): MetricSource {
			val p = DoubleArray(3)
			val n = DoubleArray(3)
			GameFrame.toPhysics(Vector3d(center).sub(origin), p)
			GameFrame.toPhysics(Vector3d(direction).normalize(), n)
			val flat = DoubleArray(9)
			val a = DoubleArray(3)
			for ((i, axis) in axes.withIndex()) {
				GameFrame.toPhysics(Vector3d(axis), a)
				flat[3 * i] = a[0]; flat[3 * i + 1] = a[1]; flat[3 * i + 2] = a[2]
			}
			return WarpEllipsoid(p[0], p[1], p[2], flat, n, speed)
		}

		/** The ellipsoidal radius of [position] (1 on the wall). */
		fun radius(position: Vector3dc): Double {
			val rel = Vector3d(position).sub(center)
			var s2 = 0.0
			for (axis in axes) {
				val u = rel.dot(axis) / axis.lengthSquared()
				s2 += u * u
			}
			return kotlin.math.sqrt(s2)
		}

		/** Its longest semi-axis. */
		val reach: Double get() = axes.maxOf { it.length() }

		// As a bubble's: of order speed over its size within a wall's reach of it, nothing past.
		override fun strengthAt(position: Vector3dc): Double = if (radius(position) < 1.5) kotlin.math.abs(speed) / reach else 0.0
	}

	/** Supplies a level's sources each time they're asked for (cheap: called per query). */
	fun interface Provider {
		fun sources(level: Level): List<FieldSource>
	}
}
