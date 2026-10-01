package net.kernelpanicsoft.relativity.mixin.client;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelRenderer.class)
public interface LevelRendererTicksAccessor {
	/** Flywheel's shader time is this counter plus the partial tick. */
	@Accessor("ticks")
	int create_relativity$getTicks();
}
