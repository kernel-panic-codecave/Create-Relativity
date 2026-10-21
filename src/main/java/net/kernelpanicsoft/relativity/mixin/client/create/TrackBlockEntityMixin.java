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
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A track's curves arriving on the client, or going: maglev curves indexed (MaglevCurves), the walls they cut anew. */
@Mixin(value = TrackBlockEntity.class, remap = false)
public abstract class TrackBlockEntityMixin extends SmartBlockEntity {
	private TrackBlockEntityMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	@Shadow public abstract java.util.Map<BlockPos, com.simibubi.create.content.trains.track.BezierConnection> getConnections();

	@Inject(method = "read", at = @At("TAIL"))
	private void create_relativity$index(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
		if (level != null) MaglevCurves.update(level, worldPosition, getConnections().values());
	}

	@Inject(method = "remove", at = @At("TAIL"))
	private void create_relativity$unindex(CallbackInfo ci) {
		if (level != null) MaglevCurves.update(level, worldPosition, java.util.List.of());
	}
}
