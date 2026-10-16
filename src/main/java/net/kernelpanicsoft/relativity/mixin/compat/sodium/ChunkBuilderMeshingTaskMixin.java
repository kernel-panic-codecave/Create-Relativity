package net.kernelpanicsoft.relativity.mixin.compat.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import net.kernelpanicsoft.relativity.client.lens.SodiumQuads;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The section a Sodium meshing task builds, for its quads' world positions (SodiumQuads). */
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public abstract class ChunkBuilderMeshingTaskMixin {
	@Unique
	private int create_relativity$x;
	@Unique
	private int create_relativity$y;
	@Unique
	private int create_relativity$z;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void create_relativity$origin(RenderSection render, int time, org.joml.Vector3dc camera, net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext context,
										  net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior sort, boolean forceSort, CallbackInfo ci) {
		create_relativity$x = render.getOriginX();
		create_relativity$y = render.getOriginY();
		create_relativity$z = render.getOriginZ();
	}

	@Inject(method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;", at = @At("HEAD"))
	private void create_relativity$begin(ChunkBuildContext context, CancellationToken token, CallbackInfoReturnable<ChunkBuildOutput> cir) {
		SodiumQuads.beginSection(create_relativity$x, create_relativity$y, create_relativity$z);
	}

	@Inject(method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;", at = @At("RETURN"))
	private void create_relativity$end(ChunkBuildContext context, CancellationToken token, CallbackInfoReturnable<ChunkBuildOutput> cir) {
		SodiumQuads.endSection();
	}
}
