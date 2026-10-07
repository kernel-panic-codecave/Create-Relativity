package net.kernelpanicsoft.relativity.mixin.client.flywheel;

import net.kernelpanicsoft.relativity.client.lens.VertexLens;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Flywheel's vertex shaders get the vertex lens where they place vertices on screen (VertexLens.flywheelSource). */
@Mixin(targets = "dev.engine_room.flywheel.backend.compile.core.Compilation", remap = false)
public abstract class CompilationMixin {
	@ModifyArg(
		method = "compile",
		at = @At(value = "INVOKE", target = "Ldev/engine_room/flywheel/backend/gl/GlCompat;safeShaderSource(ILjava/lang/CharSequence;)V"),
		index = 1
	)
	private CharSequence create_relativity$lens(CharSequence source) {
		return VertexLens.flywheelSource(source);
	}
}
