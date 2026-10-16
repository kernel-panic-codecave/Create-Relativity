package net.kernelpanicsoft.relativity.mixin.compat.sodium;

import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.kernelpanicsoft.relativity.client.lens.SodiumQuads;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A quad Sodium records for translucent sorting is pushed whole (SodiumQuads): pieces it didn't record would desync it. */
@Mixin(value = TranslucentGeometryCollector.class, remap = false)
public abstract class TranslucentGeometryCollectorMixin {
	@Inject(method = "appendQuad", at = @At("HEAD"))
	private void create_relativity$whole(ChunkVertexEncoder.Vertex[] vertices, ModelQuadFacing facing, int normal, CallbackInfoReturnable<Boolean> cir) {
		SodiumQuads.translucentQuad();
	}
}
