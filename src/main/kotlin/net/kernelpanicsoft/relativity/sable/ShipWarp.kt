package net.kernelpanicsoft.relativity.sable

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent
import dev.ryanhcode.sable.sublevel.SubLevel
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.content.WarpCoreBlockEntity
import net.kernelpanicsoft.relativity.content.WarpDrive
import net.kernelpanicsoft.relativity.spacetime.FieldSource
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.joml.Vector3d
import java.util.WeakHashMap
import kotlin.math.sqrt

/**
 * Warp ships: a Sable ship carrying a formed, running [WarpDrive] is carried the way its core faces at the drive's
 * warp (its velocity along that way steered there, spooling up over [SPOOL_SECONDS]), inside a warp bubble
 * ([FieldSource.Ellipsoid]) round its bounds: an ellipsoid through its box's corners, a block clear.
 */
object ShipWarp {
	/** How long a ship takes to reach its drive's warp from rest (seconds), at the least. */
	private const val SPOOL_SECONDS = 10.0

	/** Clearance round the ship's box (blocks). */
	private const val CLEARANCE = 1.0

	/** A running drive aboard a ship: the ship, and where the drive carries it (world unit direction, times c). */
	class Drive(val ship: SubLevel, val core: WarpCoreBlockEntity, val direction: Vector3d, val speed: Double)

	private class Cached(val tick: Long, val drives: List<Drive>, val bubbles: List<FieldSource.Ellipsoid>)

	private val cache = WeakHashMap<Level, Cached>()

	fun init() {
		SpacetimeField.addProvider { level -> cached(level).bubbles }
	}

	/** The running drives aboard [level]'s ships (one a ship: its fastest). */
	fun drives(level: Level): List<Drive> = cached(level).drives

	@Synchronized
	private fun cached(level: Level): Cached {
		val tick = level.gameTime
		cache[level]?.takeIf { it.tick == tick }?.let { return it }
		val drives = compute(level)
		val c = RelativityConfig.speedOfLight(level.dimension())
		val made = Cached(tick, drives, drives.mapNotNull { drive ->
			// The bubble moves at the ship's spooled speed (not yet its drive's warp, spooling up).
			val speed = synchronized(spool) { spool[drive.ship.uniqueId]?.speed } ?: return@mapNotNull null
			bubble(drive, speed / c).takeIf { speed > 0.0 }
		})
		cache[level] = made
		return made
	}

	private fun compute(level: Level): List<Drive> {
		val container = SubLevelContainer.getContainer(level) ?: return emptyList()
		val byShip = HashMap<SubLevel, Drive>()
		for (pos in WarpDrive.loaded(level)) {
			if (!container.inBounds(pos)) continue
			val core = level.getBlockEntity(pos) as? WarpCoreBlockEntity ?: continue
			if (!core.formed || core.speed <= 0.0) continue
			val ship = container.getPlot(ChunkPos(pos))?.subLevel ?: continue
			if (ship.isRemoved) continue
			val facing = core.blockState.getValue(BlockStateProperties.FACING)
			val direction = ship.logicalPose().transformNormal(Vector3d(facing.stepX.toDouble(), facing.stepY.toDouble(), facing.stepZ.toDouble())).normalize()
			val drive = Drive(ship, core, direction, core.speed)
			if ((byShip[ship]?.speed ?: 0.0) < drive.speed) byShip[ship] = drive
		}
		return byShip.values.toList()
	}

	/** The bubble round [drive]'s ship, moving at [speed] (times c): through its box's corners (√3 its half-extents), a block clear. */
	private fun bubble(drive: Drive, speed: Double): FieldSource.Ellipsoid {
		val pose = drive.ship.logicalPose()
		val box = drive.ship.plot.boundingBox
		val centre = Vector3d((box.minX() + box.maxX() + 1) / 2.0, (box.minY() + box.maxY() + 1) / 2.0, (box.minZ() + box.maxZ() + 1) / 2.0)
		val halves = doubleArrayOf((box.maxX() + 1 - box.minX()) / 2.0, (box.maxY() + 1 - box.minY()) / 2.0, (box.maxZ() + 1 - box.minZ()) / 2.0)
		val axes = (0..2).map { a ->
			val unit = Vector3d(if (a == 0) 1.0 else 0.0, if (a == 1) 1.0 else 0.0, if (a == 2) 1.0 else 0.0)
			pose.transformNormal(unit).normalize(halves[a] * sqrt(3.0) + CLEARANCE)
		}
		return FieldSource.Ellipsoid(pose.transformPosition(centre), axes, drive.direction, speed)
	}

	/** Each warp ship's speed along its drive's way (blocks/s), spooling up to the drive's warp; and the tick it's for. */
	private class Spool(var speed: Double, var tick: Long)

	/** A spool not run for this long (ticks) is let go: its drive stopped or its ship gone. */
	private const val FORGET_TICKS = 40

	private val spool = HashMap<java.util.UUID, Spool>()

	/**
	 * Carries each warp ship along its drive's way at its spooling speed: the velocity's part along that way set to it
	 * each step (the bubble carries the ship whatever it rests on or pulls it: friction, gravity).
	 */
	fun onPrePhysicsTick(event: ForgeSablePrePhysicsTickEvent) {
		val system = event.physicsSystem
		val level = system.level
		val c = RelativityConfig.speedOfLight(level.dimension())
		val tick = level.gameTime
		val velocity = Vector3d()
		val running = drives(level)
		synchronized(spool) {
			spool.values.removeIf { tick - it.tick > FORGET_TICKS }
			for (drive in running) {
				if (drive.ship.isRemoved) continue
				val handle = system.getPhysicsHandle(drive.ship as dev.ryanhcode.sable.sublevel.ServerSubLevel)
				handle.getLinearVelocity(velocity)
				val target = drive.speed * c
				// (Spooled a tick at a time: this runs each physics substep.)
				val state = spool.getOrPut(drive.ship.uniqueId) { Spool(velocity.dot(drive.direction).coerceAtLeast(0.0), tick) }
				if (state.tick != tick) {
					val step = target / SPOOL_SECONDS * (tick - state.tick).coerceIn(0, 20) / 20.0
					state.speed = if (state.speed < target) minOf(state.speed + step, target) else maxOf(state.speed - step, target)
					state.tick = tick
				}
				val change = state.speed - velocity.dot(drive.direction)
				// (As an impulse that wakes it: a resting ship's body sleeps, and takes no velocity.)
				val mass = drive.ship.massTracker.takeUnless { it.isInvalid }?.mass ?: continue
				if (change != 0.0) handle.applyLinearAndAngularImpulse(Vector3d(drive.direction).mul(change * mass), Vector3d(), true)
			}
		}
	}
}
