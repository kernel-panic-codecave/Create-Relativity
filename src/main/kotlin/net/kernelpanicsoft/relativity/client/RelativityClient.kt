package net.kernelpanicsoft.relativity.client

import net.kernelpanicsoft.relativity.client.lens.BlackHoleLens
import net.kernelpanicsoft.relativity.client.lens.LensEnvironment
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.common.NeoForge

/** Client-only setup; never touched on a dedicated server. */
object RelativityClient {
	fun init() {
		NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent::class.java, SpacetimeDebugRenderer::render)
		BlackHoleLens.init()
		NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent::class.java, LensEnvironment::render)
		NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { SpacetimeField.clearClient() }
	}
}
