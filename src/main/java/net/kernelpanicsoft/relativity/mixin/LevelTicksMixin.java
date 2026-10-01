package net.kernelpanicsoft.relativity.mixin;

import net.kernelpanicsoft.relativity.time.LocalClocks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LevelTicks.class)
public abstract class LevelTicksMixin<T> {
	/** Scheduled block and fluid ticks wait the same number of local proper-time ticks (see LocalClocks). */
	@ModifyVariable(method = "schedule", at = @At("HEAD"), argsOnly = true)
	private ScheduledTick<T> create_relativity$properTimeDelay(ScheduledTick<T> tick) {
		return LocalClocks.dilate((LevelTicks<T>) (Object) this, tick);
	}
}
