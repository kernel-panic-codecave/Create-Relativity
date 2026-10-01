package net.kernelpanicsoft.relativity.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.kernelpanicsoft.relativity.client.pick.BentPick;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(GameRenderer.class)
public abstract class GameRendererPickMixin {
	/** Near a hole the crosshair follows the bent light path, so you click what you see (see BentPick). */
	@WrapMethod(method = "pick(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;")
	private HitResult create_relativity$bentPick(Entity entity, double blockRange, double entityRange, float partialTick, Operation<HitResult> original) {
		HitResult bent = BentPick.pick(entity, blockRange, entityRange, partialTick);
		return bent != null ? bent : original.call(entity, blockRange, entityRange, partialTick);
	}
}
