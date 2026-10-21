package net.kernelpanicsoft.relativity.content

import com.simibubi.create.Create
import com.simibubi.create.content.trains.entity.Train
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.spacetime.FieldSource
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.minecraft.world.level.Level
import org.joml.Vector3d
import org.joml.Vector3dc
import java.util.WeakHashMap

/**
 * Every moving maglev train carries a warp tube ([FieldSource.Tube]) round its carriages, bending with its track: its
 * spine through the bogeys' anchors (lifted to the carriages' middles), from the back to the front the way it's going,
 * pushing at the train's speed over c. Worked out once a tick per level (it's asked for per query).
 */
object MaglevWarp {
	/** The tube's radius (blocks): round a carriage's body. */
	private const val RADIUS = 3.0

	/** How far above the bogeys' anchors the spine runs (blocks): the carriages' middles. */
	private const val LIFT = 1.0

	/** How far past the end bogeys the tube reaches (blocks): over the carriages' ends. */
	private const val OVERHANG = 2.5

	/** Slower than this (times c), no tube. */
	private const val MIN_SPEED = 0.01

	private class Cached(val tick: Long, val tubes: List<FieldSource.Tube>)

	private val cache = WeakHashMap<Level, Cached>()

	fun init() {
		SpacetimeField.addProvider { level -> tubes(level) }
	}

	@Synchronized
	fun tubes(level: Level): List<FieldSource.Tube> {
		val tick = level.gameTime
		cache[level]?.takeIf { it.tick == tick }?.let { return it.tubes }
		val tubes = compute(level)
		cache[level] = Cached(tick, tubes)
		return tubes
	}

	/** [tubes] worked out now (not this tick's). */
	fun compute(level: Level): List<FieldSource.Tube> = trains(level).mapNotNull { tube(it, level) }

	private fun trains(level: Level): Collection<Train> {
		val railways = if (level.isClientSide) com.simibubi.create.CreateClient.RAILWAYS else Create.RAILWAYS
		return railways.trains.values
	}

	private fun tube(train: Train, level: Level): FieldSource.Tube? {
		if (!MaglevBogey.isMaglev(train)) return null
		// (Blocks per tick to times c.)
		val speed = train.speed * 20.0 / RelativityConfig.speedOfLight(level.dimension())
		if (kotlin.math.abs(speed) < MIN_SPEED) return null
		val points = ArrayList<Vector3dc>()
		for (carriage in train.carriages) {
			if (carriage.getDimensionalIfPresent(level.dimension()) == null) return null
			for (bogey in carriage.bogeys) {
				if (bogey == null) continue
				val anchor = bogey.anchorPosition ?: continue
				val p = Vector3d(anchor.x, anchor.y + LIFT, anchor.z)
				if (points.isEmpty() || points.last().distance(p) > 0.25) points += p
			}
		}
		if (points.size < 2) return null
		// Front first in Create's order: the spine runs back to front the way it's going.
		if (train.speed > 0) points.reverse()
		extend(points, 0, 1)
		extend(points, points.size - 1, points.size - 2)
		return FieldSource.Tube(points, kotlin.math.abs(speed), RADIUS)
	}

	/** Moves end point [end] [OVERHANG] further out, away from its neighbour [from]. */
	private fun extend(points: MutableList<Vector3dc>, end: Int, from: Int) {
		val out = Vector3d(points[end]).sub(points[from])
		if (out.lengthSquared() < 1e-9) return
		points[end] = Vector3d(points[end]).fma(OVERHANG / out.length(), out)
	}
}
