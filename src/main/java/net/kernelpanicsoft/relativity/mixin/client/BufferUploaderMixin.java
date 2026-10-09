package net.kernelpanicsoft.relativity.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.kernelpanicsoft.relativity.client.lens.VertexLens;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla's immediate draws (the batched entities, block entities and particles) drawn again with their vertices at
 * their secondary images, as terrain is (LevelRendererSecondImagesMixin): the uploaded buffer, once more in that mode,
 * for draws in the level's own view.
 */
@Mixin(BufferUploader.class)
public abstract class BufferUploaderMixin {
	@WrapOperation(method = "_drawWithShader", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;drawWithShader(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lnet/minecraft/client/renderer/ShaderInstance;)V"))
	private static void create_relativity$secondImages(VertexBuffer buffer, Matrix4f modelView, Matrix4f projection, ShaderInstance shader, Operation<Void> original) {
		original.call(buffer, modelView, projection, shader);
		if (VertexLens.wantsImmediateSecondImages(projection)) {
			VertexLens.drawSecondImages(() -> original.call(buffer, modelView, projection, shader));
		}
	}
}
