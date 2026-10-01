package net.kernelpanicsoft.relativity.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.kernelpanicsoft.relativity.time.RenderTime;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Level.class)
public abstract class LevelMixin {
	@Shadow @Final public boolean isClientSide;

	/** While a dilated block entity is being drawn, game time is its own local time (see RenderTime). */
	@ModifyReturnValue(method = "getGameTime", at = @At("RETURN"))
	private long create_relativity$localGameTime(long time) {
		return isClientSide ? RenderTime.gameTime(time) : time;
	}
}
