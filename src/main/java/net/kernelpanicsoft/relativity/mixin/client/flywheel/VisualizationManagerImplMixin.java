package net.kernelpanicsoft.relativity.mixin.client.flywheel;

import net.kernelpanicsoft.relativity.client.lens.BlockQuads;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl", remap = false)
public abstract class VisualizationManagerImplMixin {
	/**
	 * While the lens records a block entity's full render, Flywheel reports no visualization, so renderers
	 * that defer to Flywheel instances (Create's kinetic blocks) emit the same model quads themselves. GameTests
	 * also turn it off (VertexLens.flywheelOff) to compare Flywheel's lensed instances with the vanilla path.
	 */
	@Inject(method = "supportsVisualization", at = @At("HEAD"), cancellable = true)
	private static void create_relativity$recordForLens(LevelAccessor level, CallbackInfoReturnable<Boolean> cir) {
		if (BlockQuads.isRecording() || net.kernelpanicsoft.relativity.client.lens.VertexLens.flywheelOff) cir.setReturnValue(false);
	}
}
