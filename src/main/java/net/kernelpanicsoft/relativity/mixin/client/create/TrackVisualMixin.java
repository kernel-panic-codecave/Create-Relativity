package net.kernelpanicsoft.relativity.mixin.client.create;

import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackVisual;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import net.kernelpanicsoft.relativity.client.maglev.MaglevCurves;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/** Maglev curves are drawn as the guideway trough (MaglevCurves), not Create's ties and rails. */
@Mixin(value = TrackVisual.class, remap = false)
public abstract class TrackVisualMixin extends AbstractVisual {
	@Shadow @Final protected TrackBlockEntity blockEntity;
	@Shadow @Final protected BlockPos visualPos;

	@Unique
	private List<Instance> create_relativity$maglev;

	private TrackVisualMixin(VisualizationContext context, Level level, float partialTick) {
		super(context, level, partialTick);
	}

	@Inject(method = "createInstance", at = @At("HEAD"), cancellable = true)
	private void create_relativity$notMaglev(BezierConnection curve, CallbackInfoReturnable<Object> cir) {
		if (MaglevCurves.isMaglev(curve)) cir.setReturnValue(null);
	}

	@Inject(method = "collectConnections", at = @At("TAIL"))
	private void create_relativity$maglevCurves(CallbackInfo ci) {
		create_relativity$deleteMaglev();
		create_relativity$maglev = new ArrayList<>(MaglevCurves.instances(instancerProvider(), blockEntity, visualPos));
	}

	@Inject(method = "_delete", at = @At("TAIL"))
	private void create_relativity$deleteMaglevCurves(CallbackInfo ci) {
		create_relativity$deleteMaglev();
	}

	@Unique
	private void create_relativity$deleteMaglev() {
		if (create_relativity$maglev != null) create_relativity$maglev.forEach(Instance::delete);
		create_relativity$maglev = null;
	}
}
