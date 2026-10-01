package net.kernelpanicsoft.relativity.time

import kotlin.math.floor

/**
 * A block entity's local proper time, in ticks, started at world time [start] so it's continuous with it. Advanced on
 * the main thread by [sync]; render and Flywheel worker threads only read it ([at]).
 */
class LocalTime(start: Long, rate: Double) {
	@Volatile private var local = start.toDouble()
	@Volatile private var syncedAt = start

	/** dτ/dt since the last sync. */
	@Volatile var rate = rate
		private set

	/** The rate GPU-animated visuals were last set up with (they bake it in); NaN if never. */
	@Volatile var visualRate = Double.NaN

	/** Main thread: a visual re-setup has been queued and not yet happened. */
	var visualUpdateQueued = false

	private var lastTick = start
	@Volatile private var lastVisualTick = start

	/** Main thread: brings local time up to world time [now], then continues at [rate]. */
	fun sync(now: Long, rate: Double) {
		if (now > syncedAt) {
			local += (now - syncedAt) * this.rate
			syncedAt = now
		}
		this.rate = rate
	}

	/** Local time at world time [now] plus [partialTick]; safe from any thread. */
	fun at(now: Long, partialTick: Float): Double = local + ((now - syncedAt) + partialTick) * rate

	/** Main thread, after [sync]: whether a whole local tick has passed since the last one. */
	fun tickDue(): Boolean {
		val whole = floor(local).toLong()
		if (whole <= lastTick) return false
		lastTick = whole
		return true
	}

	/** Same for the block's Flywheel visual, which ticks separately (one worker per visual). */
	fun visualTickDue(now: Long): Boolean {
		val whole = floor(at(now, 0f)).toLong()
		if (whole <= lastVisualTick) return false
		lastVisualTick = whole
		return true
	}
}
