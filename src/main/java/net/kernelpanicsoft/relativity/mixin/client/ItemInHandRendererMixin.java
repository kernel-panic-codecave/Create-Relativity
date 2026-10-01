package net.kernelpanicsoft.relativity.mixin.client;

import net.kernelpanicsoft.relativity.time.ReferenceFrame;
import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
	/** The first-person hand sways and swings on the local player's own tick in the singleplayer frame. */
	@ModifyVariable(method = "renderHandsWithItems", at = @At("HEAD"), argsOnly = true)
	private float create_relativity$playerPartial(float partialTick) {
		return ReferenceFrame.playerPartial(partialTick);
	}
}
