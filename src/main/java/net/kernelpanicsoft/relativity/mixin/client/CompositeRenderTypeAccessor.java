package net.kernelpanicsoft.relativity.mixin.client;

import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** A composite render type's state (LensTwinShards: which render types get the lens's twin shaders). */
@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeRenderType")
public interface CompositeRenderTypeAccessor {
	@Invoker("state")
	RenderType.CompositeState create_relativity$state();
}
