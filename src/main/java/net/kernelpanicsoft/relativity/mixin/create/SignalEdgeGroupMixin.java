package net.kernelpanicsoft.relativity.mixin.create;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.signal.SignalEdgeGroup;
import net.kernelpanicsoft.relativity.content.WarpReservations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/** A block held for a warp train's route (WarpReservations) doesn't stop that train: only other trains in it do. */
@Mixin(value = SignalEdgeGroup.class, remap = false)
public abstract class SignalEdgeGroupMixin {
	@Shadow
	public Set<Train> trains;

	@Inject(method = "isThisOccupiedUnless(Lcom/simibubi/create/content/trains/entity/Train;)Z", at = @At("HEAD"), cancellable = true)
	private void create_relativity$ownRoute(Train train, CallbackInfoReturnable<Boolean> cir) {
		if (WarpReservations.holds((SignalEdgeGroup) (Object) this, train))
			cir.setReturnValue(trains.size() > 1 || (!trains.contains(train) && !trains.isEmpty()));
	}
}
