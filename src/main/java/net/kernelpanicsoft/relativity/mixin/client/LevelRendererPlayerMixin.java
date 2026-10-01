package net.kernelpanicsoft.relativity.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import net.kernelpanicsoft.relativity.time.ReferenceFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererPlayerMixin {
	/** The local player's body (third person) interpolates across their own tick in the singleplayer frame. */
	@ModifyVariable(method = "renderEntity", at = @At("HEAD"), argsOnly = true)
	private float create_relativity$playerPartial(float partialTick, @Local(argsOnly = true) Entity entity) {
		return entity == Minecraft.getInstance().player ? ReferenceFrame.playerPartial(partialTick) : partialTick;
	}
}
