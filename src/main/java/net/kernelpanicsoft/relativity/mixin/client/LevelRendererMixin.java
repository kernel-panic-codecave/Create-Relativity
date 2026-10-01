package net.kernelpanicsoft.relativity.mixin.client;

import net.kernelpanicsoft.relativity.client.lens.LensEnvironment;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	/**
	 * Rain and snow are drawn as a cylinder around the camera. In the lens environment capture the camera sits at
	 * the black hole, so they'd stamp huge streaks across every lensed ray.
	 */
	@Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true)
	private void create_relativity$skipWeatherInLensCapture(LightTexture lightTexture, float partialTick, double camX, double camY, double camZ, CallbackInfo ci) {
		if (LensEnvironment.getCapturing()) {
			ci.cancel();
		}
	}
}
