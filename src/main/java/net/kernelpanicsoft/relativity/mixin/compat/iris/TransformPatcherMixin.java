package net.kernelpanicsoft.relativity.mixin.compat.iris;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.kernelpanicsoft.relativity.client.lens.VertexLens;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.EnumMap;
import java.util.Map;

/**
 * Shader-pack world programs get the vertex lens (VertexLens.irisSource) after Iris patches them, on both its paths:
 * the programs replacing vanilla's, and Sodium terrain. Iris caches what it returns, so a changed copy is returned.
 */
@Mixin(targets = "net.irisshaders.iris.pipeline.transform.TransformPatcher", remap = false)
public abstract class TransformPatcherMixin {
	@ModifyReturnValue(method = {"patchVanilla", "patchSodium"}, at = @At("RETURN"))
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Map<?, String> create_relativity$lens(Map<?, String> patched, @Local(argsOnly = true, ordinal = 0) String name) {
		if (patched == null) return null;
		boolean laterStages = false;
		Object vertex = null;
		for (Map.Entry<?, String> entry : patched.entrySet()) {
			String stage = ((Enum<?>) entry.getKey()).name();
			if (stage.equals("VERTEX")) vertex = entry.getKey();
			else if (!stage.equals("FRAGMENT") && entry.getValue() != null) laterStages = true;
		}
		if (vertex == null || patched.get(vertex) == null) return patched;
		Map copy = patched instanceof EnumMap<?, ?> e ? new EnumMap(e) : new java.util.HashMap(patched);
		copy.put(vertex, VertexLens.irisSource(name, patched.get(vertex), laterStages));
		return copy;
	}
}
