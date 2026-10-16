package net.kernelpanicsoft.relativity.mixin.compat.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.kernelpanicsoft.relativity.client.lens.SodiumQuads;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Near a black hole, Sodium's terrain quads are split finer for the vertex lens (SodiumQuads, QuadSubdivision). */
@Mixin(value = ChunkMeshBufferBuilder.class, remap = false)
public abstract class ChunkMeshBufferBuilderMixin {
	@Inject(method = "push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)V", at = @At("HEAD"), cancellable = true)
	private void create_relativity$subdivide(ChunkVertexEncoder.Vertex[] vertices, int material, CallbackInfo ci) {
		if (SodiumQuads.push((ChunkMeshBufferBuilder) (Object) this, vertices, material)) ci.cancel();
	}
}
