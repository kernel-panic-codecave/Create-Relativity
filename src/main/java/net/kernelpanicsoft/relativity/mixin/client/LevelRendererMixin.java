package net.kernelpanicsoft.relativity.mixin.client;

import net.kernelpanicsoft.relativity.client.lens.LensVoxels;
import net.kernelpanicsoft.relativity.client.lens.VertexLens;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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

	@Shadow
	private void renderSectionLayer(RenderType renderType, double x, double y, double z, Matrix4f frustumMatrix, Matrix4f projectionMatrix) {
	}

	/** Each terrain layer drawn again with its vertices at their secondary images (the Einstein ring's), when lensing. */
	@Inject(method = "renderSectionLayer", at = @At("TAIL"))
	private void create_relativity$secondImages(RenderType renderType, double x, double y, double z, Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
		VertexLens.drawSecondImages(() -> this.renderSectionLayer(renderType, x, y, z, frustumMatrix, projectionMatrix));
	}
}
