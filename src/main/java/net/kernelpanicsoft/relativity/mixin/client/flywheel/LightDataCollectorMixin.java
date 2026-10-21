package net.kernelpanicsoft.relativity.mixin.client.flywheel;

import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;

/**
 * Flywheel's fast sky light reads go through its accessor on SkyLightSectionStorage's inner storage map. In the dev
 * runtime that accessor never applies (Loom remaps its target with '/' for the inner class '$', so mixin doesn't find
 * it), and every read throws: visuals collecting section light (Create's curved track) never draw, or the game
 * crashes. Then its slow reads instead (production applies the accessor; nothing changes there).
 */
@Mixin(targets = "dev.engine_room.flywheel.backend.engine.LightDataCollector", remap = false)
public abstract class LightDataCollectorMixin {
	private static Boolean create_relativity$accessorApplied;

	@Inject(method = "createFastSkyDataGetter", at = @At("HEAD"), cancellable = true)
	private static void create_relativity$slowWithoutAccessor(LayerLightEventListener listener, CallbackInfoReturnable<Long2ObjectFunction<DataLayer>> cir) {
		if (create_relativity$accessorApplied == null) {
			boolean applied;
			try {
				Class<?> map = Class.forName("net.minecraft.world.level.lighting.SkyLightSectionStorage$SkyDataLayerStorageMap");
				applied = Arrays.stream(map.getInterfaces()).anyMatch(i -> i.getName().endsWith("SkyDataLayerStorageMapAccessor"));
			} catch (ReflectiveOperationException | LinkageError e) {
				applied = false;
			}
			create_relativity$accessorApplied = applied;
		}
		if (!create_relativity$accessorApplied) cir.setReturnValue(null);
	}
}
