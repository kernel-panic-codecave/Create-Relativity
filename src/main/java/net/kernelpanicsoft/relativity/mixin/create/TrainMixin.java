package net.kernelpanicsoft.relativity.mixin.create;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.simibubi.create.content.trains.entity.Train;
import net.kernelpanicsoft.relativity.content.MaglevBogey;
import net.kernelpanicsoft.relativity.content.KrasnikovTubes;
import net.kernelpanicsoft.relativity.config.RelativityConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A train wholly on maglev bogeys is faster and has less friction (Trains config: multiples of Create's); in its
 * Krasnikov tube, as fast as the tube allows, through turns too, its acceleration enough to get there (KrasnikovTubes).
 */
@Mixin(value = Train.class, remap = false)
public abstract class TrainMixin {
	@ModifyReturnValue(method = "maxSpeed", at = @At("RETURN"))
	private float create_relativity$maglevTopSpeed(float speed) {
		Train train = (Train) (Object) this;
		if (!MaglevBogey.isMaglev(train)) return speed;
		float maglev = (float) (speed * RelativityConfig.Server.Trains.INSTANCE.getMaglevTopSpeed());
		Float tube = KrasnikovTubes.topSpeed(train);
		return tube == null ? maglev : Math.max(maglev, tube);
	}

	/** In its tube, a train takes turns at full speed (g-forces be damned): its turning speed is its top speed there. */
	@ModifyReturnValue(method = "maxTurnSpeed", at = @At("RETURN"))
	private float create_relativity$maglevTurnSpeed(float speed) {
		Train train = (Train) (Object) this;
		if (!MaglevBogey.isMaglev(train)) return speed;
		float maglev = (float) (speed * RelativityConfig.Server.Trains.INSTANCE.getMaglevTurningSpeed());
		Float tube = KrasnikovTubes.topSpeed(train);
		return tube == null ? maglev : Math.max(maglev, tube);
	}

	@ModifyReturnValue(method = "acceleration", at = @At("RETURN"))
	private float create_relativity$maglevAcceleration(float acceleration) {
		Train train = (Train) (Object) this;
		if (!MaglevBogey.isMaglev(train)) return acceleration;
		return KrasnikovTubes.acceleration(train, (float) (acceleration * RelativityConfig.Server.Trains.INSTANCE.getMaglevAcceleration()));
	}
}
