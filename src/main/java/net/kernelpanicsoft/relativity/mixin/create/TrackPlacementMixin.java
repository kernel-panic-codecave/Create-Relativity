package net.kernelpanicsoft.relativity.mixin.create;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.simibubi.create.content.trains.track.TrackPlacement;
import net.kernelpanicsoft.relativity.content.Maglev;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(TrackPlacement.class)
public abstract class TrackPlacementMixin {
	/**
	 * No curve between track of different types, nor of another type than the track being laid (maglev joins only
	 * maglev). Refused once Create has worked the curve out, so what it draws for a refusal (the ends' axes) is there.
	 */
	@ModifyReturnValue(method = "tryConnect", at = @At("RETURN"))
	private static TrackPlacement.PlacementInfo create_relativity$onlyItsType(TrackPlacement.PlacementInfo info, Level level, Player player,
		BlockPos pos, BlockState state, ItemStack stack, boolean girder, boolean maximiseTurn) {
		if (!Maglev.refusesConnection(level, state, stack)) return info;
		// (Over any refusal of Create's own: this one's the reason.)
		((PlacementInfoAccessor) info).create_relativity$setValid(false);
		return info.withMessage(Maglev.OTHER_TYPE);
	}
}
