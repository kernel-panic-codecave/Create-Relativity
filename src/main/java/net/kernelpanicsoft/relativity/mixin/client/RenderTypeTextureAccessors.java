package net.kernelpanicsoft.relativity.mixin.client;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;

/** The texture a render type draws with, for the lens (BlockQuads) to read the pixels its quads show. */
public final class RenderTypeTextureAccessors {
	private RenderTypeTextureAccessors() {}

	@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeRenderType")
	public interface Composite {
		@Accessor("state")
		RenderType.CompositeState create_relativity$state();
	}

	@Mixin(RenderType.CompositeState.class)
	public interface State {
		@Accessor("textureState")
		RenderStateShard.EmptyTextureStateShard create_relativity$textureState();
	}

	@Mixin(RenderStateShard.EmptyTextureStateShard.class)
	public interface Texture {
		@Invoker("cutoutTexture")
		Optional<ResourceLocation> create_relativity$texture();
	}
}
