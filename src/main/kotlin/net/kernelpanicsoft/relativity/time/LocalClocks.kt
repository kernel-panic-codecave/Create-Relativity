package net.kernelpanicsoft.relativity.time

import dev.ryanhcode.sable.companion.SableCompanion
import dev.ryanhcode.sable.companion.SubLevelAccess
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.ticks.LevelTicks
import net.minecraft.world.ticks.ScheduledTick
import net.neoforged.neoforge.event.level.LevelEvent
import org.joml.Vector3d
import java.util.UUID
import java.util.WeakHashMap
import kotlin.math.ceil

/**
 * Local tick rates from proper time. Server time is coordinate time (the clock far from every hole), so dτ/dt ≤ 1
 * everywhere and dilated things only ever skip ticks: each keeps an accumulator, adds dτ/dt every server tick, and
 * really ticks each time it reaches 1. Everyone agrees on proper time, so this is consistent for every observer.
 * Blocks in a Sable ship's plot run on the ship's clock (its centre, moving with the ship).
 */
object LocalClocks {
	/** Rates this close to 1 are 1: a skipped tick every 10⁴ isn't worth computing. */
	private const val NEGLIGIBLE = 1e-4

	/** A tick accumulator; starts full so the first tick runs. */
	class Clock {
		private var accumulated = 1.0

		/** The rate of the last advance. */
		var rate = 1.0
			private set

		/** Progress toward the next local tick, 0…1. */
		val fraction: Double get() = accumulated.coerceIn(0.0, 1.0)

		/** Advances by one server tick at [rate]; true if a local tick is due. */
		fun advance(rate: Double): Boolean {
			this.rate = rate
			accumulated += rate
			if (accumulated < 1.0) return false
			accumulated = minOf(accumulated - 1.0, 1.0)
			return true
		}
	}

	/** Client block entities waiting for the main thread to check whether they need a clock (seen off-thread). */
	val pendingChecks = java.util.concurrent.ConcurrentLinkedQueue<BlockEntity>()

	/** Client block entities with clocks, kept in step with world time each client tick (main thread only). */
	val clientClocked: MutableSet<BlockEntity> = java.util.Collections.newSetFromMap(WeakHashMap())

	private const val RECHECK_TICKS = 20L
	private val sectionClocks = WeakHashMap<LevelChunkSection, Clock>()
	private val ticksOwners = WeakHashMap<LevelTicks<*>, ServerLevel>()

	private class ShipRate(val gameTime: Long, val rate: Double)

	/** Shared by the client and server threads in singleplayer. */
	private val shipRates = java.util.concurrent.ConcurrentHashMap<UUID, ShipRate>()

	/** Block-entity ticks skipped so far (both sides), for `/relativity clock`. */
	var skippedBlockEntityTicks = 0L
		private set

	val enabled: Boolean get() = RelativityConfig.Server.Time.dilateTicks

	fun onLevelLoad(event: LevelEvent.Load) {
		val level = event.level as? ServerLevel ?: return
		ticksOwners[level.blockTicks] = level
		ticksOwners[level.fluidTicks] = level
	}

	/** dτ/dt for something at rest at block [pos], or on the ship whose plot holds it. */
	fun rateAt(level: Level, pos: BlockPos): Double {
		if (SpacetimeField.isFlat(level) && SableCompanion.INSTANCE.getContaining(level, pos) == null) return 1.0
		val ship = SableCompanion.INSTANCE.getContaining(level, pos)
		if (ship != null) return shipRate(level, ship)
		return clean(SpacetimeField.properTimeRate(level, Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5), ZERO))
	}

	/** dτ/dt for something at rest at [position] in the world (not on a ship). */
	fun rateAt(level: Level, position: Vector3d): Double {
		if (SpacetimeField.isFlat(level)) return 1.0
		return clean(SpacetimeField.properTimeRate(level, position, ZERO))
	}

	/** A ship runs on the clock at its origin moving with it; cached per tick. */
	private fun shipRate(level: Level, ship: SubLevelAccess): Double {
		val time = level.gameTime
		shipRates[ship.uniqueId]?.let { if (it.gameTime == time) return it.rate }
		val pose = ship.logicalPose()
		val position = Vector3d(pose.position())
		// Sable takes the point in the ship's plot coordinates; its pivot moves at the ship's linear velocity.
		val velocity = SableCompanion.INSTANCE.getVelocity(level, ship, Vector3d(pose.rotationPoint()))
		val rate = clean(SpacetimeField.properTimeRate(level, position, velocity))
		if (shipRates.size > 4096) shipRates.clear()
		shipRates[ship.uniqueId] = ShipRate(time, rate)
		return rate
	}

	/** Near 1 is 1; no static observer exists inside a horizon (NaN), so that clock stops. */
	private fun clean(rate: Double) = when {
		rate.isNaN() -> 0.0
		rate > 1.0 - NEGLIGIBLE -> 1.0
		else -> rate.coerceAtLeast(0.0)
	}

	private fun holder(blockEntity: BlockEntity) = blockEntity as ProperTimeHolder

	/** Main thread: [blockEntity]'s clock, created if it's dilated (it then keeps it: its lag is real). */
	fun clockFor(blockEntity: BlockEntity, level: Level): LocalTime? {
		val holder = holder(blockEntity)
		holder.`create_relativity$getLocalTime`()?.let { return it }
		val rate = rateAt(level, blockEntity.blockPos)
		if (rate >= 1.0) return null
		val time = LocalTime(level.gameTime, rate)
		holder.`create_relativity$setLocalTime`(time)
		if (level.isClientSide) clientClocked.add(blockEntity)
		return time
	}

	/**
	 * Any thread: [blockEntity]'s clock if it has one. Render workers can't compute rates (the field caches aren't
	 * thread-safe), so a block without one is queued for the main thread to check, at most once a second.
	 */
	fun localTimeOf(blockEntity: BlockEntity, level: Level): LocalTime? {
		if (!enabled) return null
		val holder = holder(blockEntity)
		holder.`create_relativity$getLocalTime`()?.let { return it }
		val now = level.gameTime
		if (now - holder.`create_relativity$getCheckedAt`() >= RECHECK_TICKS) {
			holder.`create_relativity$setCheckedAt`(now)
			pendingChecks.add(blockEntity)
		}
		return null
	}

	/** Whether [blockEntity] ticks this server (or client) tick. */
	@JvmStatic
	fun shouldTick(blockEntity: BlockEntity): Boolean {
		val level = blockEntity.level ?: return true
		if (!enabled) return true
		val time = clockFor(blockEntity, level) ?: return true
		// The client ticks block entities before advancing game time (the server after), so it's a tick behind here.
		val now = if (level.isClientSide) level.gameTime + 1 else level.gameTime
		time.sync(now, rateAt(level, blockEntity.blockPos))
		val due = time.tickDue()
		if (!due) skippedBlockEntityTicks++
		return due
	}

	/** Whether [section] of [chunk] gets its random ticks this tick. */
	@JvmStatic
	fun shouldRandomTick(chunk: LevelChunk, section: LevelChunkSection): Boolean {
		if (!enabled) return true
		val level = chunk.level ?: return true
		if (SpacetimeField.isFlat(level) && SableCompanion.INSTANCE.getContaining(level, chunk.pos) == null) return true
		val index = chunk.sections.indexOfFirst { it === section }
		if (index < 0) return true
		val sectionY = chunk.getSectionYFromSectionIndex(index)
		val center = SectionPos.of(chunk.pos, sectionY).center()
		val rate = rateAt(level, center)
		if (rate >= 1.0) {
			sectionClocks.remove(section)
			return true
		}
		return sectionClocks.getOrPut(section, ::Clock).advance(rate)
	}

	/** Stretches a newly scheduled tick's delay to the same number of local ticks: delay / (dτ/dt). */
	@JvmStatic
	fun <T> dilate(ticks: LevelTicks<T>, tick: ScheduledTick<T>): ScheduledTick<T> {
		if (!enabled) return tick
		val level = ticksOwners[ticks] ?: return tick
		val now = level.levelData.gameTime
		val delay = tick.triggerTick() - now
		if (delay <= 0) return tick
		val rate = rateAt(level, tick.pos())
		if (rate >= 1.0) return tick
		val dilated = ceil(delay / rate.coerceAtLeast(1e-3)).toLong().coerceAtMost(delay * 1000)
		return ScheduledTick(tick.type(), tick.pos(), now + dilated, tick.priority(), tick.subTickOrder())
	}

	private val ZERO = Vector3d()
}
