package net.kernelpanicsoft.relativity.mixin.client;

import com.mojang.blaze3d.platform.GlStateManager;
import net.kernelpanicsoft.relativity.client.lens.TessellationStage;
import org.lwjgl.opengl.GL40;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The tessellation stage's draws (TessellationStage): the program in use kept, and triangles drawn with a tessellated
 * one sent as patches of three.
 */
@Mixin(GlStateManager.class)
public abstract class GlStateManagerTessellationMixin {
	@Inject(method = "_glUseProgram", at = @At("HEAD"), remap = false)
	private static void create_relativity$trackProgram(int program, CallbackInfo ci) {
		TessellationStage.current = program;
	}

	@ModifyVariable(method = "_drawElements", at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
	private static int create_relativity$asPatches(int mode) {
		return TessellationStage.patches(mode) ? GL40.GL_PATCHES : mode;
	}
}
