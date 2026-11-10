package net.kernelpanicsoft.relativity.client.lens;

import com.mojang.blaze3d.vertex.VertexFormat;
import foundry.veil.api.client.registry.RenderTypeShardRegistry;
import net.kernelpanicsoft.relativity.mixin.client.CompositeRenderTypeAccessor;
import net.kernelpanicsoft.relativity.mixin.client.CompositeStateAccessor;
import net.kernelpanicsoft.relativity.mixin.client.ShaderStateShardAccessor;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

import java.util.function.Predicate;

/**
 * Registers LensTwins' shards with Veil (RenderTypeShardRegistry), on the render types with a shader drawn as
 * triangles. In Java, with a raw predicate: the composite render type it's given is package-private to Minecraft, so it's
 * reached through accessors only, never named.
 */
public final class LensTwinShards {
	private LensTwinShards() {
	}

	/** [tessellated] on those drawn as plain triangles (quads and lines are), [plain] on those drawn as strips or fans. */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static void register(RenderStateShard tessellated, RenderStateShard plain) {
		Predicate plainTriangles = type -> hasShader(type) && plainTriangles(((RenderType) type).mode());
		Predicate strips = type -> hasShader(type) && triangles(((RenderType) type).mode()) && !plainTriangles(((RenderType) type).mode());
		RenderTypeShardRegistry.addGenericShard(plainTriangles, tessellated);
		RenderTypeShardRegistry.addGenericShard(strips, plain);
	}

	private static boolean hasShader(Object type) {
		RenderType.CompositeState state = ((CompositeRenderTypeAccessor) type).create_relativity$state();
		return ((ShaderStateShardAccessor) ((CompositeStateAccessor) (Object) state).create_relativity$shaderState()).create_relativity$shader().isPresent();
	}

	/** Whether [mode] draws triangles (a geometry stage takes it): all but true lines and points. */
	private static boolean triangles(VertexFormat.Mode mode) {
		return mode != VertexFormat.Mode.DEBUG_LINES && mode != VertexFormat.Mode.DEBUG_LINE_STRIP;
	}

	/** Whether [mode] draws plain triangles (patches of three). Quads and lines are drawn as such. */
	private static boolean plainTriangles(VertexFormat.Mode mode) {
		return mode == VertexFormat.Mode.QUADS || mode == VertexFormat.Mode.TRIANGLES || mode == VertexFormat.Mode.LINES;
	}
}
