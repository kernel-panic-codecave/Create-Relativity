package net.kernelpanicsoft.relativity.mixin.create;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.simibubi.create.content.kinetics.KineticNetwork;
import net.kernelpanicsoft.relativity.create.ShaftTorsion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = KineticNetwork.class, remap = false)
public abstract class KineticNetworkMixin {
	/** Networks spanning different clock rates twist (see ShaftTorsion). */
	@ModifyReturnValue(method = "calculateStress", at = @At("RETURN"))
	private float create_relativity$torsion(float stress) {
		return stress + ShaftTorsion.extraStress((KineticNetwork) (Object) this);
	}
}
