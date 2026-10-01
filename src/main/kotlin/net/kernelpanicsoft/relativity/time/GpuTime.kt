package net.kernelpanicsoft.relativity.time

import net.kernelpanicsoft.relativity.mixin.client.LevelRendererTicksAccessor
import net.minecraft.client.Minecraft
import net.minecraft.world.level.block.entity.BlockEntity

/**
 * GPU-animated instances draw angle = offset + renderSeconds × speed from one global time (Flywheel's renderer ticks).
 * For a dilated block that should be speed × local time instead. With rate r, speed × local = speed·r × renderSeconds
 * + speed × (local − r × render), so scale the speed by r and fold the second term into the offset, evaluated at
 * setup. Both sides then advance together until the rate drifts and the visual is set up again (ClientClocks).
 */
object GpuTime {
	@JvmStatic
	fun speed(blockEntity: BlockEntity, degreesPerSecond: Float): Float {
		val time = clock(blockEntity) ?: return degreesPerSecond
		time.visualRate = time.rate
		time.visualUpdateQueued = false
		return (degreesPerSecond * time.rate).toFloat()
	}

	@JvmStatic
	fun offset(blockEntity: BlockEntity, offset: Float, degreesPerSecond: Float): Float {
		val time = clock(blockEntity) ?: return offset
		val level = blockEntity.level ?: return offset
		val minecraft = Minecraft.getInstance()
		val renderTicks = (minecraft.levelRenderer as LevelRendererTicksAccessor).`create_relativity$getTicks`()
		val partial = minecraft.timer.getGameTimeDeltaPartialTick(false)
		val now = level.gameTime
		// Local time on the renderer's tick counter (it and game time both count client ticks, a fixed offset apart).
		val local = time.at(now, partial) + (renderTicks - now)
		val lagTicks = local - (renderTicks + partial) * time.rate
		val shift = (degreesPerSecond.toDouble() * lagTicks / 20.0) % 360.0
		return (offset + shift).toFloat()
	}

	private fun clock(blockEntity: BlockEntity): LocalTime? {
		if (!LocalClocks.enabled) return null
		return (blockEntity as ProperTimeHolder).`create_relativity$getLocalTime`()
	}
}
