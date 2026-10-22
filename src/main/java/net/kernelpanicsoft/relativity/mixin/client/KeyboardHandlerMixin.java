package net.kernelpanicsoft.relativity.mixin.client;

import net.kernelpanicsoft.relativity.client.SpacetimeGrid;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** F3 + Z: the spacetime grid ({@link SpacetimeGrid}), beside F3 + G's chunk borders; and its line in F3 + Q's help. */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Shadow
	protected abstract void debugFeedbackTranslated(String message, Object... args);

	@Inject(method = "handleDebugKeys", at = @At("HEAD"), cancellable = true)
	private void create_relativity$spacetimeGrid(int key, CallbackInfoReturnable<Boolean> cir) {
		if (key != GLFW.GLFW_KEY_Z) return;
		boolean shown = SpacetimeGrid.toggle();
		debugFeedbackTranslated(shown ? "debug.create_relativity.spacetime_grid.on" : "debug.create_relativity.spacetime_grid.off");
		cir.setReturnValue(true);
	}

	@Inject(method = "handleDebugKeys", at = @At("RETURN"))
	private void create_relativity$spacetimeGridHelp(int key, CallbackInfoReturnable<Boolean> cir) {
		if (key == GLFW.GLFW_KEY_Q) minecraft.gui.getChat().addMessage(Component.translatable("debug.create_relativity.spacetime_grid.help"));
	}
}
