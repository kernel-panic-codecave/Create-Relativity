package net.kernelpanicsoft.relativity.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.kernelpanicsoft.relativity.time.RenderTime;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
	/** Draws each block entity in its own local time: partial tick, game time and animation clocks (see RenderTime). */
	@WrapMethod(method = "render")
	private <E extends BlockEntity> void create_relativity$localTime(E blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource buffers, Operation<Void> original) {
		RenderTime.Frame frame = RenderTime.enter(blockEntity, partialTick);
		if (frame == null) {
			original.call(blockEntity, partialTick, poseStack, buffers);
			return;
		}
		try {
			original.call(blockEntity, frame.getPartialTick(), poseStack, buffers);
		} finally {
			RenderTime.exit();
		}
	}
}
