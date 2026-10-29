package net.kernelpanicsoft.relativity.mixin.create;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageSyncData;
import net.kernelpanicsoft.relativity.content.WarpTrainSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * On the client, a warp train's places come from WarpTrainSync, set as they are: Create's own (each carriage's wheels
 * every few ticks, run after) is ignored meanwhile, and what it had left to run cleared, so it picks up from there after.
 */
@Mixin(value = CarriageSyncData.class, remap = false)
public abstract class CarriageSyncDataMixin {
	@Shadow
	private float[] pointDistanceSnapshot;

	@Shadow
	private float destinationDistanceSnapshot;

	@Inject(method = "apply", at = @At("HEAD"), cancellable = true)
	private void create_relativity$warpApply(CarriageContraptionEntity entity, Carriage carriage, CallbackInfo ci) {
		if (!WarpTrainSync.isHeld(carriage.train)) return;
		create_relativity$clear();
		ci.cancel();
	}

	@Inject(method = "approach", at = @At("HEAD"), cancellable = true)
	private void create_relativity$warpApproach(CarriageContraptionEntity entity, Carriage carriage, float partial, CallbackInfo ci) {
		if (!WarpTrainSync.isHeld(carriage.train)) return;
		create_relativity$clear();
		ci.cancel();
	}

	private void create_relativity$clear() {
		Arrays.fill(pointDistanceSnapshot, 0f);
		destinationDistanceSnapshot = 0f;
	}
}
