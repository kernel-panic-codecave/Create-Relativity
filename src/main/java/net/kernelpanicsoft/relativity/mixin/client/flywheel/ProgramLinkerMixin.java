package net.kernelpanicsoft.relativity.mixin.client.flywheel;

import net.kernelpanicsoft.relativity.client.lens.VertexLens;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;

/** Each linked Flywheel program gets the vertex lens's uniform block and table sampler hooked up. */
@Mixin(targets = "dev.engine_room.flywheel.backend.compile.core.ProgramLinker", remap = false)
public abstract class ProgramLinkerMixin {
	private static Method create_relativity$handle;

	@Inject(method = "link", at = @At("RETURN"))
	private void create_relativity$linkLens(CallbackInfoReturnable<Object> cir) {
		Object program = cir.getReturnValue();
		try {
			if (create_relativity$handle == null) create_relativity$handle = program.getClass().getMethod("handle");
			VertexLens.linkProgram((Integer) create_relativity$handle.invoke(program));
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Flywheel's GlProgram has no handle()", e);
		}
	}
}
