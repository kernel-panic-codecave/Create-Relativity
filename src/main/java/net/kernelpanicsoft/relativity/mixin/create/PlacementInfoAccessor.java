package net.kernelpanicsoft.relativity.mixin.create;

import com.simibubi.create.content.trains.track.TrackPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = TrackPlacement.PlacementInfo.class, remap = false)
public interface PlacementInfoAccessor {
	@Accessor("valid")
	void create_relativity$setValid(boolean valid);

}
