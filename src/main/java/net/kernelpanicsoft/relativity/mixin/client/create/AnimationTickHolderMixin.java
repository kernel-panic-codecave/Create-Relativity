package net.kernelpanicsoft.relativity.mixin.client.create;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.kernelpanicsoft.relativity.time.RenderTime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = AnimationTickHolder.class, remap = false)
public abstract class AnimationTickHolderMixin {
	/** Create's animation clock reads the drawn block entity's local time (see RenderTime). */
	@ModifyReturnValue(method = "getTicks(Z)I", at = @At("RETURN"))
	private static int create_relativity$localTicks(int ticks) {
		return RenderTime.animationTicks(ticks);
	}

	@ModifyReturnValue(method = "getPartialTicks()F", at = @At("RETURN"))
	private static float create_relativity$localPartial(float partialTick) {
		return RenderTime.partialTick(partialTick);
	}
}
