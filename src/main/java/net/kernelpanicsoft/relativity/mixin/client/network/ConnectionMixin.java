package net.kernelpanicsoft.relativity.mixin.client.network;

import net.kernelpanicsoft.relativity.client.light.LightDelay;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionMixin {
	/** Updates about distant things are held until their light reaches the player (see LightDelay). */
	@Inject(method = "genericsFtw", at = @At("HEAD"), cancellable = true)
	private static void create_relativity$lightDelay(Packet<?> packet, PacketListener listener, CallbackInfo ci) {
		if (LightDelay.intercept(packet, listener)) {
			ci.cancel();
		}
	}
}
