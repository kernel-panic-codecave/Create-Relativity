package net.kernelpanicsoft.relativity.mixin.client.flywheel;

import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl", remap = false)
public abstract class VisualizationManagerImplMixin {
	/**
	 * GameTests turn Flywheel's visualization off (VertexLens.flywheelOff) to compare its lensed instances with the
	 * vanilla path, where renderers that defer to Flywheel instances (Create's kinetic blocks) draw their own quads.
	 */
	@Inject(method = "supportsVisualization", at = @At("HEAD"), cancellable = true)
	private static void create_relativity$flywheelOff(LevelAccessor level, CallbackInfoReturnable<Boolean> cir) {
		if (net.kernelpanicsoft.relativity.client.lens.VertexLens.flywheelOff) cir.setReturnValue(false);
	}
}
