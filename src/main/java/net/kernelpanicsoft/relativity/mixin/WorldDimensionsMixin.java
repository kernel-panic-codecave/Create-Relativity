package net.kernelpanicsoft.relativity.mixin;

import net.kernelpanicsoft.relativity.world.PonderflatGenerator;
import net.minecraft.core.Registry;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ponderflat counts as a flat world, as superflat does: the horizon at the bottom of the world, not sea level (else
 * a player on its floor stood far below the horizon, under the void's dark sky and fog). Worked out each time a world's
 * dimensions are baked, so worlds made before this count too.
 */
@Mixin(WorldDimensions.class)
public abstract class WorldDimensionsMixin {
	@Inject(method = "specialWorldProperty", at = @At("HEAD"), cancellable = true)
	private static void create_relativity$ponderflatIsFlat(Registry<LevelStem> stems, CallbackInfoReturnable<PrimaryLevelData.SpecialWorldProperty> cir) {
		LevelStem overworld = stems.get(LevelStem.OVERWORLD);
		if (overworld != null && overworld.generator() instanceof PonderflatGenerator) cir.setReturnValue(PrimaryLevelData.SpecialWorldProperty.FLAT);
	}
}
