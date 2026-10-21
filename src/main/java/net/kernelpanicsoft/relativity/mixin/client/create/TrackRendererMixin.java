package net.kernelpanicsoft.relativity.mixin.client.create;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackRenderer;
import net.kernelpanicsoft.relativity.client.maglev.MaglevCurves;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Where Flywheel is off: maglev curves drawn as the guideway trough too (MaglevCurves). */
@Mixin(value = TrackRenderer.class, remap = false)
public abstract class TrackRendererMixin {
	@Inject(method = "renderBezierTurn", at = @At("HEAD"), cancellable = true)
	private static void create_relativity$maglev(Level level, BezierConnection curve, PoseStack pose, VertexConsumer buffer, CallbackInfo ci) {
		if (!MaglevCurves.isMaglev(curve)) return;
		MaglevCurves.render(level, curve, pose, buffer);
		ci.cancel();
	}
}
