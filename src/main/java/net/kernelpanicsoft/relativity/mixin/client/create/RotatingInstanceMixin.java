package net.kernelpanicsoft.relativity.mixin.client.create;

import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import net.kernelpanicsoft.relativity.time.GpuTime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Rotating instances spin on the GPU as offset + renderSeconds × speed, one global time for all of them. For a dilated
 * block, scale the speed by its dτ/dt and shift the offset so the angle continues from its local time (see GpuTime).
 */
@Mixin(value = RotatingInstance.class, remap = false)
public abstract class RotatingInstanceMixin {
	private static final String SETUP = "setup(Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;Lnet/minecraft/core/Direction$Axis;F)Lcom/simibubi/create/content/kinetics/base/RotatingInstance;";

	@ModifyArg(method = SETUP, at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/base/RotatingInstance;setRotationalSpeed(F)Lcom/simibubi/create/content/kinetics/base/RotatingInstance;"))
	private float create_relativity$localSpeed(float degreesPerSecond, @Local(argsOnly = true) KineticBlockEntity be) {
		return GpuTime.speed(be, degreesPerSecond);
	}

	@ModifyArg(method = SETUP, at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/base/RotatingInstance;setRotationOffset(F)Lcom/simibubi/create/content/kinetics/base/RotatingInstance;"))
	private float create_relativity$localOffset(float offset, @Local(argsOnly = true) KineticBlockEntity be, @Local(argsOnly = true) float speed) {
		return GpuTime.offset(be, offset, speed * 6f);
	}
}
