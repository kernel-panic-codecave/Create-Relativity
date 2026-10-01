package net.kernelpanicsoft.relativity.client

import dev.nyon.klf.MOD_BUS
import net.kernelpanicsoft.relativity.client.deform.NoodleRenderer
import net.kernelpanicsoft.relativity.client.light.LightDelay
import net.kernelpanicsoft.relativity.client.lens.BlackHoleLens
import net.kernelpanicsoft.relativity.client.lens.LensEnvironment
import net.kernelpanicsoft.relativity.entity.RelativityEntities
import net.minecraft.client.renderer.entity.NoopRenderer
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.client.event.RenderLivingEvent
import net.neoforged.neoforge.common.NeoForge

/** Client-only setup; never touched on a dedicated server. */
object RelativityClient {
	fun init() {
		NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent::class.java, SpacetimeDebugRenderer::render)
		NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent::class.java, LensEnvironment::render)
		BlackHoleLens.init()
		NeoForge.EVENT_BUS.addListener(RenderLivingEvent.Pre::class.java, NoodleRenderer::onRenderLiving)
		NeoForge.EVENT_BUS.addListener(ClientClocks::onClientTick)
		NeoForge.EVENT_BUS.addListener(LightDelay::onClientTick)
		NeoForge.EVENT_BUS.addListener(LightDelay::onLoggingOut)
		MOD_BUS.addListener(ProperTimeHud::register)
		MOD_BUS.addListener(EntityRenderersEvent.RegisterRenderers::class.java) {
			// Holes draw nothing themselves: the lens and disk passes show them.
			it.registerEntityRenderer(RelativityEntities.BLACK_HOLE.get(), ::NoopRenderer)
		}
	}
}
