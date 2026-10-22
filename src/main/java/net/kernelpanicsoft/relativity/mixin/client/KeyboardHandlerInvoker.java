package net.kernelpanicsoft.relativity.mixin.client;

import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** F3 + a key as the game handles it (GameTests: a real F3 can't be held down). */
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerInvoker {
	@Invoker("handleDebugKeys")
	boolean create_relativity$handleDebugKeys(int key);
}
