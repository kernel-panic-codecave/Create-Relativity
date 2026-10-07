package net.kernelpanicsoft.relativity.mixin.client;

import net.kernelpanicsoft.relativity.gametest.ClientTestInput;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While a client GameTest runs, the mouse doesn't turn the camera, click or scroll (repeatable screenshots). */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
	private void create_relativity$lockTurn(double movementTime, CallbackInfo ci) {
		if (ClientTestInput.locked) ci.cancel();
	}

	@Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
	private void create_relativity$lockPress(long window, int button, int action, int modifiers, CallbackInfo ci) {
		if (ClientTestInput.locked) ci.cancel();
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void create_relativity$lockScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
		if (ClientTestInput.locked) ci.cancel();
	}
}
