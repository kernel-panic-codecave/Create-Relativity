package net.kernelpanicsoft.relativity.mixin.client;

import net.kernelpanicsoft.relativity.client.lens.VertexLens;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Each terrain layer drawn again with its vertices at their secondary images (the Einstein ring's), when lensing. Applied
 * after the default priority: Sodium overwrites renderSectionLayer with its own chunk renderer's draw, and this wraps
 * whichever body is there (vanilla's or Sodium's, which Iris draws shader packs' terrain through).
 */
@Mixin(value = LevelRenderer.class, priority = 1500)
public abstract class LevelRendererSecondImagesMixin {
	@Shadow
	private void renderSectionLayer(RenderType renderType, double x, double y, double z, Matrix4f frustumMatrix, Matrix4f projectionMatrix) {
	}

	@Inject(method = "renderSectionLayer", at = @At("TAIL"))
	private void create_relativity$secondImages(RenderType renderType, double x, double y, double z, Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
		VertexLens.drawSecondImages(() -> this.renderSectionLayer(renderType, x, y, z, frustumMatrix, projectionMatrix));
	}
}
