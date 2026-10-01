package net.kernelpanicsoft.relativity

import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent
import net.kernelpanicsoft.relativity.command.RelativityCommands
import net.kernelpanicsoft.relativity.sable.RelativityForceGroups
import net.kernelpanicsoft.relativity.sable.SpikeAttractor
import net.minecraft.resources.ResourceLocation
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@Mod(CreateRelativity.MOD_ID)
object CreateRelativity {
	const val MOD_ID = "create_relativity"

	val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

	fun id(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, path)

	init {
		RelativityForceGroups.register()
		// Explicit event classes: Kotlin compiles function references to indy lambdas, which
		// the type-inferring addListener(Consumer) overload can't resolve.
		NeoForge.EVENT_BUS.addListener(SpikeAttractor::onPrePhysicsTick)
		NeoForge.EVENT_BUS.addListener(RelativityCommands::register)
		LOGGER.info("Create: Relativity initialized")
	}
}
