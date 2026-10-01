package net.kernelpanicsoft.relativity.mixin.client;

import net.kernelpanicsoft.relativity.client.lens.VertexLens;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Frustum.class)
public abstract class FrustumMixin {
	/** With vertex lensing, things near a hole can be seen outside the straight-line frustum (see VertexLens). */
	@Inject(method = "isVisible", at = @At("HEAD"), cancellable = true)
	private void create_relativity$bentVisibility(AABB box, CallbackInfoReturnable<Boolean> cir) {
		if (VertexLens.alwaysVisible(box)) {
			cir.setReturnValue(true);
		}
	}
}
