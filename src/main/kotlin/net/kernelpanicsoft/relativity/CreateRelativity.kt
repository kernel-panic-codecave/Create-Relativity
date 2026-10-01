package net.kernelpanicsoft.relativity

import dev.architectury.platform.Platform
import net.kernelpanicsoft.relativity.command.RelativityCommands
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.sable.RelativityForceGroups
import net.kernelpanicsoft.relativity.sable.SpikeAttractor
import net.minecraft.resources.ResourceLocation
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import dev.architectury.platform.Mod as ArchitecturyMod

@Mod(CreateRelativity.MOD_ID)
object CreateRelativity {
	const val MOD_ID = "create_relativity"

	val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

	/** Architectury handle Archie keys configs and channels on. */
	val MOD: ArchitecturyMod by lazy { Platform.getMod(MOD_ID) }

	fun id(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, path)

	init {
		RelativityConfig.init()
		RelativityForceGroups.register()
		// Explicit event classes: Kotlin compiles function references to indy lambdas, which
		// the type-inferring addListener(Consumer) overload can't resolve.
		NeoForge.EVENT_BUS.addListener(SpikeAttractor::onPrePhysicsTick)
		NeoForge.EVENT_BUS.addListener(RelativityCommands::register)
		LOGGER.info("Create: Relativity initialized")
	}
}
