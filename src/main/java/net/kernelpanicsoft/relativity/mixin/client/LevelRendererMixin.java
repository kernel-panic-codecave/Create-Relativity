package net.kernelpanicsoft.relativity.mixin.client;

import net.kernelpanicsoft.relativity.client.lens.LensVoxels;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	/** Every client block change and chunk arrival dirties its section: the lens voxel grid re-reads it. */
	@Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
	private void create_relativity$dirtyLensVoxels(int sectionX, int sectionY, int sectionZ, boolean reRenderOnMainThread, CallbackInfo ci) {
		LensVoxels.sectionChanged(sectionX, sectionY, sectionZ);
	}
}
