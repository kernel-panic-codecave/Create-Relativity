package net.kernelpanicsoft.relativity

import dev.architectury.platform.Platform
import net.kernelpanicsoft.archie.events.gametest.AGametestEvents
import net.kernelpanicsoft.archie.gametest.platform.AGameTestPlatform
import net.kernelpanicsoft.archie.util.onClient
import net.kernelpanicsoft.relativity.accretion.Accretion
import net.kernelpanicsoft.relativity.client.RelativityClient
import net.kernelpanicsoft.relativity.command.RelativityCommands
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.create.ShaftTorsion
import net.kernelpanicsoft.relativity.entity.EntitySpacetime
import net.kernelpanicsoft.relativity.gametest.RelativityGameTest
import net.kernelpanicsoft.relativity.entity.RelativityEntities
import net.kernelpanicsoft.relativity.sable.RelativityForceGroups
import net.kernelpanicsoft.relativity.sable.SableSpacetimeForces
import net.kernelpanicsoft.relativity.spacetime.SpacetimeAttachments
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.kernelpanicsoft.relativity.spacetime.SpacetimeSync
import net.kernelpanicsoft.relativity.time.LocalClocks
import net.kernelpanicsoft.relativity.time.ReferenceFrame
import net.minecraft.resources.ResourceLocation
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLEnvironment
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
		SpacetimeAttachments.init()
		SpacetimeSync.init()
		net.kernelpanicsoft.relativity.content.KrasnikovSync.init()
		NeoForge.EVENT_BUS.addListener(net.kernelpanicsoft.relativity.content.KrasnikovSync::onLevelTick)
		net.kernelpanicsoft.relativity.content.WarpTrainSync.init()
		net.kernelpanicsoft.relativity.content.KrasnikovWarpControl.init()
		NeoForge.EVENT_BUS.addListener(net.kernelpanicsoft.relativity.content.KrasnikovWarpControl::onRightClick)
		NeoForge.EVENT_BUS.addListener(net.kernelpanicsoft.relativity.content.WarpTrainSync::onServerTick)
		NeoForge.EVENT_BUS.addListener(SpacetimeSync::onStartTracking)
		// (Spinning holes are off for players for now; the GameTests keep their paths tested.)
		if (System.getProperty("archie.gametest.side") != null) net.kernelpanicsoft.relativity.spacetime.BlackHole.spinning = true
		RelativityEntities.register()
		net.kernelpanicsoft.relativity.content.RelativityBlocks.register()
		net.kernelpanicsoft.relativity.world.PonderflatGenerator.register()
		NeoForge.EVENT_BUS.addListener(SpacetimeField::onEntityJoin)
		NeoForge.EVENT_BUS.addListener(SpacetimeField::onEntityLeave)
		RelativityForceGroups.register()
		NeoForge.EVENT_BUS.addListener(SableSpacetimeForces::onPrePhysicsTick)
		net.kernelpanicsoft.relativity.sable.ShipWarp.init()
		NeoForge.EVENT_BUS.addListener(net.kernelpanicsoft.relativity.sable.ShipWarp::onPrePhysicsTick)
		NeoForge.EVENT_BUS.addListener(SableSpacetimeForces::onLevelTick)
		NeoForge.EVENT_BUS.addListener(EntitySpacetime::onEntityTick)
		NeoForge.EVENT_BUS.addListener(LocalClocks::onLevelLoad)
		NeoForge.EVENT_BUS.addListener(ReferenceFrame::onServerTick)
		NeoForge.EVENT_BUS.addListener(ShaftTorsion::onLevelTick)
		Accretion.init()
		NeoForge.EVENT_BUS.addListener(Accretion::onLevelTick)
		NeoForge.EVENT_BUS.addListener(RelativityCommands::register)
		onClient { RelativityClient.init() }
		// Only under runGametest(Client): the gametest library isn't on the production classpath.
		if (AGameTestPlatform.isGameTest) {
			AGametestEvents += MOD
			RelativityGameTest.init()
		}
		LOGGER.info("Create: Relativity initialized")
	}
}
