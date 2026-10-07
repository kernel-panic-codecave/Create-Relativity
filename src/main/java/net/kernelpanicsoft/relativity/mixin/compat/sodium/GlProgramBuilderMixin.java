package net.kernelpanicsoft.relativity.mixin.compat.sodium;

import net.kernelpanicsoft.relativity.client.lens.VertexLens;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Each program Sodium links (its terrain shaders, which Veil gives the vertex lens: vertex_lens_sodium.json) gets the
 * lens's uniform block and table sampler hooked up.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.shader.GlProgram$Builder", remap = false)
public abstract class GlProgramBuilderMixin {
	@Shadow
	@Final
	private int program;

	@Inject(method = "link", at = @At("RETURN"))
	private void create_relativity$linkLens(CallbackInfoReturnable<Object> cir) {
		VertexLens.linkProgram(this.program);
	}
}
