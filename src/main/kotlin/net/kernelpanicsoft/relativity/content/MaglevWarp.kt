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
 * Warp trains: a maglev train carrying a formed [WarpDrive] can run as fast as its drive's setting ([drive]: its top
 * speed, TrainMixin; Create's controls drive it there), and under way carries a warp tube ([FieldSource.Tube]) round
 * its carriages, bending with its track: its spine through the bogeys' anchors (lifted to the carriages' middles), from
 * the back to the front the way it's going, pushing at the train's speed over c. Tubes are worked out once a tick per
 * level (they're asked for per query).
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

	/** How long a warp train takes to reach its drive's top speed from rest (ticks), at the least. */
	private const val SPOOL_TICKS = 200.0

	private class Cached(val tick: Long, val tubes: List<FieldSource.Tube>)

	/** Each train's drive's setting (highest warp factor), kept while its carriages are out of reach. */
	private val drives = java.util.concurrent.ConcurrentHashMap<java.util.UUID, Double>()

	/** When each train's blocks were last looked through (ms): at most once a second ([drive] is asked often). */
	private val lookedAt = java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long>()

	private const val LOOK_EVERY_MS = 1000L

	/**
	 * The highest warp factor [train]'s drive is set to, if it's a maglev train carrying a formed one (null if not):
	 * read from its carriages' blocks when any is loaded, remembered otherwise.
	 */
	@JvmStatic
	fun drive(train: Train): Double? {
		if (!MaglevBogey.isMaglev(train)) return null
		val now = System.currentTimeMillis()
		val last = lookedAt[train.id]
		if (last != null && now - last < LOOK_EVERY_MS) return drives[train.id]
		lookedAt[train.id] = now
		var found: Double? = null
		var looked = false
		for (carriage in train.carriages) {
			val entity = carriage.anyAvailableEntity() ?: continue
			val contraption = entity.contraption ?: continue
			looked = true
			for ((pos, info) in contraption.blocks) {
				val state = info.state()
				if (state.block !is WarpCoreBlock || !state.getValue(WarpDrive.FORMED)) continue
				val control = contraption.blocks[pos.relative(state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING))]
				val setting = control?.nbt()?.getInt("ScrollValue") ?: 0
				found = maxOf(found ?: 0.0, setting / 10.0)
			}
		}
		if (!looked) return drives[train.id]
		if (found == null || found <= 0.0) drives.remove(train.id) else drives[train.id] = found
		return found?.takeIf { it > 0.0 }
	}

	/** [train]'s top speed (blocks per tick) at its drive's warp, if it has one. */
	@JvmStatic
	fun warpTopSpeed(train: Train, level: Level?): Float? {
		val warp = drive(train) ?: return null
		val c = RelativityConfig.speedOfLight(level?.dimension() ?: Level.OVERWORLD)
		return (net.kernelpanicsoft.relativity.physics.WarpScale.speed(warp) * c / 20.0).toFloat()
	}

	/** [train]'s acceleration (blocks per tick per tick) under warp: up to its top speed in [SPOOL_TICKS] at the least. */
	@JvmStatic
	fun warpAcceleration(train: Train, level: Level?, normal: Float): Float {
		val top = warpTopSpeed(train, level) ?: return normal
		return maxOf(normal, (top / SPOOL_TICKS).toFloat())
	}

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
		if (drive(train) == null) return null
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
