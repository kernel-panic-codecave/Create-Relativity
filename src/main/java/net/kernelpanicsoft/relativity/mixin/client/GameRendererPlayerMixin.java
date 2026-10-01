package net.kernelpanicsoft.relativity.mixin.client;

import net.kernelpanicsoft.relativity.time.ReferenceFrame;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(GameRenderer.class)
public abstract class GameRendererPlayerMixin {
	/** View bobbing follows the local player's own tick in the singleplayer frame. */
	@ModifyVariable(method = "bobView", at = @At("HEAD"), argsOnly = true)
	private float create_relativity$playerPartial(float partialTick) {
		return ReferenceFrame.playerPartial(partialTick);
	}
}
