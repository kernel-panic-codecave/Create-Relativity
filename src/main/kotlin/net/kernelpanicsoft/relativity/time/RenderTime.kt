package net.kernelpanicsoft.relativity.time

import net.minecraft.world.level.block.entity.BlockEntity
import kotlin.math.floor

/**
 * Draws a dilated block entity in its own local time. While it's drawn (on whichever thread), the time sources
 * renderers read report its clock instead of the world's: the partial tick, level game time, and Create's animation
 * clock. So tick-driven state (prev → current) and time-driven animation (time × speed) both slow down together.
 */
object RenderTime {
	/** Local game time [gameTime] + [partialTick], [tickShift] world ticks behind the world. */
	class Frame(val gameTime: Long, val partialTick: Float, val tickShift: Long)

	private val current = ThreadLocal<Frame?>()

	/** Interpolation may run at most one tick past the last state (a local tick is due within one world tick). */
	const val MAX_PARTIAL = 2.0

	/** Starts drawing [blockEntity]; null (and nothing to [exit]) if it runs on world time. */
	@JvmStatic
	fun enter(blockEntity: BlockEntity, partialTick: Float): Frame? {
		val frame = frameFor(blockEntity, partialTick) ?: return null
		current.set(frame)
		return frame
	}

	@JvmStatic
	fun exit() = current.remove()

	fun frameFor(blockEntity: BlockEntity, partialTick: Float): Frame? {
		val level = blockEntity.level ?: return null
		val time = LocalClocks.localTimeOf(blockEntity, level) ?: return null
		val now = level.gameTime
		val base = floor(time.at(now, 0f)).toLong()
		// Tick-driven state was last updated at local tick `base`. A local tick lands partway through a world tick, so
		// carry on past 1 at the same pace until it arrives (as if the next step continued the last one); stopping at 1
		// would hold, then jump ahead when the tick comes.
		val partial = (time.at(now, partialTick) - base).coerceIn(0.0, MAX_PARTIAL).toFloat()
		return Frame(base, partial, now - base)
	}

	/** Runs [action] in [frame]'s time. */
	inline fun <T> within(frame: Frame, action: () -> T): T {
		set(frame)
		try {
			return action()
		} finally {
			exit()
		}
	}

	@PublishedApi
	internal fun set(frame: Frame) = current.set(frame)

	@JvmStatic
	fun gameTime(time: Long): Long = current.get()?.gameTime ?: time

	@JvmStatic
	fun partialTick(partialTick: Float): Float = current.get()?.partialTick ?: partialTick

	/** Create's tick counter isn't game time, so shift it by the same amount. */
	@JvmStatic
	fun animationTicks(ticks: Int): Int {
		val frame = current.get() ?: return ticks
		return (ticks - frame.tickShift).toInt()
	}
}
