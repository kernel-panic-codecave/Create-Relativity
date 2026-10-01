package net.kernelpanicsoft.relativity.entity

import dev.nyon.klf.MOD_BUS
import net.kernelpanicsoft.relativity.CreateRelativity
import net.minecraft.core.registries.Registries
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

object RelativityEntities {
	private val REGISTER: DeferredRegister<EntityType<*>> = DeferredRegister.create(Registries.ENTITY_TYPE, CreateRelativity.MOD_ID)

	/** Tracked far out (32 chunks): big holes bend light and pull from well beyond normal entity range. */
	val BLACK_HOLE: DeferredHolder<EntityType<*>, EntityType<BlackHoleEntity>> = REGISTER.register("black_hole") { ->
		EntityType.Builder.of(::BlackHoleEntity, MobCategory.MISC)
			.sized(1f, 1f)
			.clientTrackingRange(32)
			.updateInterval(10)
			.fireImmune()
			.build("black_hole")
	}

	fun register() {
		REGISTER.register(MOD_BUS)
	}
}
