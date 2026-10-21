package net.kernelpanicsoft.relativity.mixin.create;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.track.TrackBlock;
import net.kernelpanicsoft.relativity.content.Maglev;
import net.kernelpanicsoft.relativity.content.RelativityBlocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(TrackBlock.class)
public abstract class TrackBlockBogeyMixin {
	/** A train casing used on maglev track lays a maglev bogey (Create's own would be its small bogey). */
	@ModifyReturnValue(method = "getBogeyAnchor", at = @At("RETURN"))
	private BlockState create_relativity$maglevBogey(BlockState anchor) {
		if (((TrackBlock) (Object) this).getMaterial().trackType != Maglev.INSTANCE.getTRACK_TYPE()) return anchor;
		return RelativityBlocks.INSTANCE.getMAGLEV_BOGEY().get().defaultBlockState()
			.setValue(AbstractBogeyBlock.AXIS, anchor.getValue(AbstractBogeyBlock.AXIS));
	}
}
