package net.kernelpanicsoft.relativity.mixin.client;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;
import java.util.function.Supplier;

/** A shader state's shader, if it has one (LensTwinShards). */
@Mixin(RenderStateShard.ShaderStateShard.class)
public interface ShaderStateShardAccessor {
	@Accessor("shader")
	Optional<Supplier<ShaderInstance>> create_relativity$shader();
}
