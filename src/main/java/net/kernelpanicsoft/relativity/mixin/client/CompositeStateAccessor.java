package net.kernelpanicsoft.relativity.mixin.client;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A composite render state's shader (LensTwinShards). */
@Mixin(RenderType.CompositeState.class)
public interface CompositeStateAccessor {
	@Accessor("shaderState")
	RenderStateShard.ShaderStateShard create_relativity$shaderState();
}
