package net.kernelpanicsoft.relativity.mixin.client;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LightTexture.class)
public interface LightTextureAccessor {
	/** The lightmap's texture, so the lens lights traced surfaces exactly as the game lights blocks. */
	@Accessor("lightTextureLocation")
	ResourceLocation create_relativity$location();
}
