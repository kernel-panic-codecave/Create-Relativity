package net.kernelpanicsoft.relativity.mixin;

import net.kernelpanicsoft.relativity.time.LocalClocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BoundTickingBlockEntityMixin {
	@Shadow @Final private BlockEntity blockEntity;

	/** Block entities tick on their local proper-time clock (see LocalClocks). */
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void create_relativity$properTime(CallbackInfo ci) {
		if (!LocalClocks.shouldTick(blockEntity)) {
			ci.cancel();
		}
	}
}
