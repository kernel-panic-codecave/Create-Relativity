package net.kernelpanicsoft.relativity.mixin.client;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(GameRenderer.class)
public interface GameRendererShadersAccessor {
	/** The vanilla core shaders, to link the vertex lens's uniform block and table sampler in each program. */
	@Accessor("shaders")
	Map<String, ShaderInstance> create_relativity$getShaders();
}
