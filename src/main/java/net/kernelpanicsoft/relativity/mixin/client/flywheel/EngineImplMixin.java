package net.kernelpanicsoft.relativity.mixin.client.flywheel;

import net.kernelpanicsoft.relativity.CreateRelativity;
import net.kernelpanicsoft.relativity.client.lens.VertexLens;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * Flywheel's instanced blocks (Create's shafts, cogs, belts...) drawn again with their vertices at their secondary
 * images, as terrain is (LevelRendererSecondImagesMixin): right after its draw manager draws, once more in that mode.
 * (Flywheel's backend isn't on our compile path, only its API: the draw is called through its public accessors.)
 */
@Mixin(targets = "dev.engine_room.flywheel.backend.engine.EngineImpl", remap = false)
public abstract class EngineImplMixin {
	@Unique
	private static Method create_relativity$draw;
	@Unique
	private static boolean create_relativity$failed;

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Ldev/engine_room/flywheel/backend/engine/DrawManager;render(Ldev/engine_room/flywheel/backend/engine/LightStorage;Ldev/engine_room/flywheel/backend/engine/embed/EnvironmentStorage;)V", shift = At.Shift.AFTER))
	private void create_relativity$secondImages(CallbackInfo ci) {
		if (create_relativity$failed) return;
		Object engine = this;
		VertexLens.drawSecondImages(() -> {
			try {
				Class<?> type = engine.getClass();
				Object manager = type.getMethod("drawManager").invoke(engine);
				Object light = type.getMethod("lightStorage").invoke(engine);
				Object environment = type.getMethod("environmentStorage").invoke(engine);
				if (create_relativity$draw == null) {
					for (Method method : manager.getClass().getMethods()) {
						if (method.getName().equals("render") && method.getParameterCount() == 2) create_relativity$draw = method;
					}
				}
				create_relativity$draw.invoke(manager, light, environment);
			} catch (ReflectiveOperationException | RuntimeException e) {
				create_relativity$failed = true;
				CreateRelativity.INSTANCE.getLOGGER().error("Flywheel's secondary images couldn't be drawn", e);
			}
		});
	}
}
