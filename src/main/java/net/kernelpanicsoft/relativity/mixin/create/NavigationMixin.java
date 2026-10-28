package net.kernelpanicsoft.relativity.mixin.create;

import com.simibubi.create.content.trains.entity.Navigation;
import com.simibubi.create.content.trains.entity.Train;
import net.kernelpanicsoft.relativity.content.WarpReservations;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A warp train whose route isn't held yet waits at its station (WarpReservations): it doesn't set off. */
@Mixin(value = Navigation.class, remap = false)
public abstract class NavigationMixin {
	@Shadow
	public Train train;

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void create_relativity$waitForRoute(Level level, CallbackInfo ci) {
		if (WarpReservations.mustWait(train)) ci.cancel();
	}
}
