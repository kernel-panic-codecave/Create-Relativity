package net.kernelpanicsoft.relativity.mixin.create;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.simibubi.create.content.trains.entity.Train;
import net.kernelpanicsoft.relativity.content.MaglevBogey;
import net.kernelpanicsoft.relativity.content.MaglevWarp;
import net.kernelpanicsoft.relativity.config.RelativityConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A train wholly on maglev bogeys is faster and has less friction (Trains config: multiples of Create's); one carrying
 * a warp drive as fast as the drive's warp, its acceleration enough to get there (MaglevWarp).
 */
@Mixin(value = Train.class, remap = false)
public abstract class TrainMixin {
	@ModifyReturnValue(method = "maxSpeed", at = @At("RETURN"))
	private float create_relativity$maglevTopSpeed(float speed) {
		Train train = (Train) (Object) this;
		if (!MaglevBogey.isMaglev(train)) return speed;
		float maglev = (float) (speed * RelativityConfig.Server.Trains.INSTANCE.getMaglevTopSpeed());
		Float warp = MaglevWarp.warpTopSpeed(train, null);
		return warp == null ? maglev : Math.max(maglev, warp);
	}

	@ModifyReturnValue(method = "maxTurnSpeed", at = @At("RETURN"))
	private float create_relativity$maglevTurnSpeed(float speed) {
		return MaglevBogey.isMaglev((Train) (Object) this) ? (float) (speed * RelativityConfig.Server.Trains.INSTANCE.getMaglevTurningSpeed()) : speed;
	}

	@ModifyReturnValue(method = "acceleration", at = @At("RETURN"))
	private float create_relativity$maglevAcceleration(float acceleration) {
		Train train = (Train) (Object) this;
		if (!MaglevBogey.isMaglev(train)) return acceleration;
		return MaglevWarp.warpAcceleration(train, null, (float) (acceleration * RelativityConfig.Server.Trains.INSTANCE.getMaglevAcceleration()));
	}
}
