package net.kernelpanicsoft.relativity.mixin.create;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.TorquePropagator;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(value = TorquePropagator.class, remap = false)
public interface TorquePropagatorAccessor {
	@Accessor("networks")
	static Map<LevelAccessor, Map<Long, KineticNetwork>> create_relativity$getNetworks() {
		throw new AssertionError();
	}
}
