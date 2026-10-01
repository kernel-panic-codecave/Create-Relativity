package net.kernelpanicsoft.relativity.sable

import dev.nyon.klf.MOD_BUS
import dev.ryanhcode.sable.api.physics.force.ForceGroup
import dev.ryanhcode.sable.api.physics.force.ForceGroups
import net.kernelpanicsoft.relativity.CreateRelativity
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

/**
 * Force groups shown in Sable's force debug view.
 *
 * Registered with a plain [DeferredRegister] rather than Veil's `RegistrationProvider`, which requires
 * an `FMLModContainer` and rejects KotlinLangForge's container. Sable creates the registry itself.
 */
object RelativityForceGroups {
	private val REGISTER: DeferredRegister<ForceGroup> = DeferredRegister.create(ForceGroups.REGISTRY_KEY, CreateRelativity.MOD_ID)

	val SPACETIME_CURVATURE: DeferredHolder<ForceGroup, ForceGroup> = REGISTER.register("spacetime_curvature") { ->
		ForceGroup(
			Component.translatable("force_group.create_relativity.spacetime_curvature"),
			Component.translatable("force_group.create_relativity.spacetime_curvature.description"),
			0x2b1055,
			true,
		)
	}

	fun register() {
		REGISTER.register(MOD_BUS)
	}
}
