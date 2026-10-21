package net.kernelpanicsoft.relativity.mixin.client.create;

import com.simibubi.create.content.trains.track.TrackBlockEntity;
import net.kernelpanicsoft.relativity.client.maglev.MaglevCurves;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A maglev track's curves arriving: the walls along the lines they leave are cut anew (MaglevTrackModel). */
@Mixin(value = TrackBlockEntity.class, remap = false)
public abstract class TrackBlockEntityMixin extends SmartBlockEntity {
	private TrackBlockEntityMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	@Inject(method = "read", at = @At("TAIL"))
	private void create_relativity$remesh(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
		if (clientPacket && level != null) MaglevCurves.remesh(level, worldPosition);
	}
}
