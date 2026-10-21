package net.kernelpanicsoft.relativity.mixin.create;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.simibubi.create.content.trains.track.TrackBlock;
import net.kernelpanicsoft.relativity.content.Maglev;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(TrackBlock.class)
public abstract class TrackBlockMixin {
	/** No track placed with an end running into track of another type (maglev joins only maglev). */
	@ModifyReturnValue(method = "getStateForPlacement", at = @At("RETURN"))
	private BlockState create_relativity$onlyItsType(BlockState state, BlockPlaceContext context) {
		return state != null && Maglev.meetsOtherType(context.getLevel(), context.getClickedPos(), state) ? null : state;
	}
}
