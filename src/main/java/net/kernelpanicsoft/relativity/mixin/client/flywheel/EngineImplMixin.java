package net.kernelpanicsoft.relativity.mixin.client.flywheel;

import net.kernelpanicsoft.relativity.client.lens.VertexLens;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


/**
 * Flywheel's instanced blocks (Create's shafts, cogs, belts...): its draws take the full near-axis cut, their shaders
 * carrying no pixel check.
 */
@Mixin(targets = "dev.engine_room.flywheel.backend.engine.EngineImpl", remap = false)
public abstract class EngineImplMixin {
	/** Flywheel's shaders carry no pixel check: its draws take the full near-axis cut (VertexLens.uncheckedAxisCut). */
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Ldev/engine_room/flywheel/backend/engine/DrawManager;render(Ldev/engine_room/flywheel/backend/engine/LightStorage;Ldev/engine_room/flywheel/backend/engine/embed/EnvironmentStorage;)V"))
	private void create_relativity$fullAxisCut(CallbackInfo ci) {
		VertexLens.uncheckedAxisCut(true);
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Ldev/engine_room/flywheel/backend/engine/DrawManager;render(Ldev/engine_room/flywheel/backend/engine/LightStorage;Ldev/engine_room/flywheel/backend/engine/embed/EnvironmentStorage;)V", shift = At.Shift.AFTER))
	private void create_relativity$axisCutBack(CallbackInfo ci) {
		VertexLens.uncheckedAxisCut(false);
	}
}
