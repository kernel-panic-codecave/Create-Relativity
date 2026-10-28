package net.kernelpanicsoft.relativity.mixin.create;

import com.simibubi.create.content.trains.GlobalRailwayManager;
import net.kernelpanicsoft.relativity.content.WarpReservations;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Warp trains' held routes (WarpReservations): put back after Create clears every block's reservation, before the
 * trains run; settled after they have.
 */
@Mixin(value = GlobalRailwayManager.class, remap = false)
public abstract class GlobalRailwayManagerMixin {
	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/trains/GlobalRailwayManager;tickTrains(Lnet/minecraft/world/level/Level;)V"))
	private void create_relativity$holdRoutes(Level level, CallbackInfo ci) {
		WarpReservations.apply();
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void create_relativity$settleRoutes(Level level, CallbackInfo ci) {
		if (level.dimension() == Level.OVERWORLD) WarpReservations.update();
	}
}
