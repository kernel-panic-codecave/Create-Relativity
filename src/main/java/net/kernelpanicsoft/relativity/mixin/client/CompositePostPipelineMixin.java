package net.kernelpanicsoft.relativity.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import foundry.veil.api.client.render.post.PostPipeline;
import foundry.veil.api.client.render.post.stage.CompositePostPipeline;
import gg.moonflower.molangcompiler.api.MolangEnvironment;
import gg.moonflower.molangcompiler.api.MolangEnvironmentBuilder;
import net.kernelpanicsoft.relativity.client.lens.LensResolution;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The lens resolution setting in Veil's post pipelines: their framebuffer sizes can read it as
 * {@code q.relativity_lens_scale} (black_hole_lens.json's tables), and a change rebuilds them as a resize would.
 */
@Mixin(value = CompositePostPipeline.class, remap = false)
public abstract class CompositePostPipelineMixin {
	@Shadow
	private int screenWidth;

	private float relativity$scale = -1.0F;

	@Inject(method = "apply", at = @At("HEAD"))
	private void relativity$rescale(PostPipeline.Context context, CallbackInfo ci) {
		float scale = LensResolution.scale();
		if (scale != this.relativity$scale) {
			this.relativity$scale = scale;
			this.screenWidth = -1;
		}
	}

	@WrapOperation(method = "apply", at = @At(value = "INVOKE", target = "Lgg/moonflower/molangcompiler/api/MolangEnvironmentBuilder;create()Lgg/moonflower/molangcompiler/api/MolangEnvironment;"))
	private MolangEnvironment relativity$query(MolangEnvironmentBuilder<?> builder, Operation<MolangEnvironment> original) {
		return original.call(builder.setQuery("relativity_lens_scale", LensResolution.scale()));
	}
}
