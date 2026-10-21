package net.kernelpanicsoft.relativity.mixin.create;

import com.simibubi.create.content.trains.track.TrackPlacement;
import net.kernelpanicsoft.relativity.content.Maglev;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TrackPlacement.class)
public abstract class TrackPlacementMixin {
	/** No curve between track of different types, nor of another type than the track being laid (maglev joins only maglev). */
	@Inject(method = "tryConnect", at = @At("HEAD"), cancellable = true)
	private static void create_relativity$onlyItsType(Level level, Player player, BlockPos pos, BlockState state, ItemStack stack,
		boolean girder, boolean maximiseTurn, CallbackInfoReturnable<TrackPlacement.PlacementInfo> cir) {
		TrackPlacement.PlacementInfo refused = Maglev.refuseConnection(level, state, stack);
		if (refused != null) cir.setReturnValue(refused);
	}
}
