package net.kernelpanicsoft.relativity.client

import dev.engine_room.flywheel.lib.visualization.VisualizationHelper
import net.kernelpanicsoft.relativity.time.LocalClocks
import net.kernelpanicsoft.relativity.time.ProperTimeHolder
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.ClientTickEvent
import kotlin.math.abs

/** Main-thread upkeep of client block-entity clocks: creates the ones render workers asked for, and advances all. */
object ClientClocks {
	/** A rate change this large re-sets-up the block's Flywheel visual (GPU animation speeds are baked in). */
	private const val VISUAL_UPDATE = 0.01

	fun onClientTick(event: ClientTickEvent.Post) {
		val level = Minecraft.getInstance().level ?: return
		while (true) {
			val blockEntity = LocalClocks.pendingChecks.poll() ?: break
			if (blockEntity.level === level && !blockEntity.isRemoved) LocalClocks.clockFor(blockEntity, level)
		}
		val now = level.gameTime
		val iterator = LocalClocks.clientClocked.iterator()
		while (iterator.hasNext()) {
			val blockEntity = iterator.next()
			if (blockEntity.isRemoved || blockEntity.level !== level) {
				iterator.remove()
				continue
			}
			val time = (blockEntity as ProperTimeHolder).`create_relativity$getLocalTime`() ?: continue
			val rate = LocalClocks.rateAt(level, blockEntity.blockPos)
			time.sync(now, rate)
			// Re-set-up GPU-animated visuals built before the clock existed, or with a rate that has since drifted.
			val baked = time.visualRate
			if (!time.visualUpdateQueued && (baked.isNaN() || abs(rate - baked) > VISUAL_UPDATE)) {
				time.visualUpdateQueued = true
				VisualizationHelper.queueUpdate(blockEntity)
			}
		}
	}
}
