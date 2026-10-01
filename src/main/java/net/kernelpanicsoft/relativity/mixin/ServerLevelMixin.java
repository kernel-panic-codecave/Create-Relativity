package net.kernelpanicsoft.relativity.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.kernelpanicsoft.relativity.time.LocalClocks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
	/** Random ticks run per section on the section's local proper-time clock (see LocalClocks). */
	@WrapOperation(
		method = "tickChunk",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;isRandomlyTicking()Z")
	)
	private boolean create_relativity$properTimeRandomTicks(LevelChunkSection section, Operation<Boolean> original, LevelChunk chunk, int randomTickSpeed) {
		return original.call(section) && LocalClocks.shouldRandomTick(chunk, section);
	}
}
