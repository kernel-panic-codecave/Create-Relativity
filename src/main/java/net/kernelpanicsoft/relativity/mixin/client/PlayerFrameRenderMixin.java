package net.kernelpanicsoft.relativity.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import net.kernelpanicsoft.relativity.time.ReferenceFrame;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** In the singleplayer frame the local player's ticks are spread out; the camera interpolates across their own tick. */
@Mixin(Camera.class)
public abstract class PlayerFrameRenderMixin {
	@ModifyVariable(method = "setup", at = @At("HEAD"), argsOnly = true)
	private float create_relativity$playerPartial(float partialTick, @Local(argsOnly = true) Entity entity) {
		return entity == Minecraft.getInstance().player ? ReferenceFrame.playerPartial(partialTick) : partialTick;
	}
}
