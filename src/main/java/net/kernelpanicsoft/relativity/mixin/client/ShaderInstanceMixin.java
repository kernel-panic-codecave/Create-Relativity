package net.kernelpanicsoft.relativity.mixin.client;

import net.kernelpanicsoft.relativity.client.lens.VertexLens;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every shader instance, once linked (and whenever its program is replaced), gets the vertex lens's uniform block and table sampler hooked up (where it has
 * them): vanilla's, Veil's, and shader packs' (Iris's ExtendedShader is one).
 */
@Mixin(ShaderInstance.class)
public abstract class ShaderInstanceMixin {
	@Inject(method = "<init>*", at = @At("TAIL"))
	private void create_relativity$linkLens(CallbackInfo ci) {
		VertexLens.ensureLinked((ShaderInstance) (Object) this);
	}

	/** And again before drawing if its program changed since (Veil recompiles programs in place, with new ids). */
	@Inject(method = "apply", at = @At("HEAD"))
	private void create_relativity$relinkLens(CallbackInfo ci) {
		VertexLens.ensureLinked((ShaderInstance) (Object) this);
	}
}
