package net.kernelpanicsoft.relativity.client

import com.simibubi.create.content.trains.bogey.BogeyBlockEntityRenderer
import com.simibubi.create.content.trains.bogey.BogeyBlockEntityVisual
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
import dev.nyon.klf.MOD_BUS
import net.kernelpanicsoft.relativity.client.deform.NoodleRenderer
import net.kernelpanicsoft.relativity.client.light.LightDelay
import net.kernelpanicsoft.relativity.client.lens.BlackHoleLens
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
		NeoForge.EVENT_BUS.addListener(SpacetimeGrid::onClientTick)
		NeoForge.EVENT_BUS.addListener(net.kernelpanicsoft.relativity.client.lens.SceneCapture::onRenderFrame)
		BlackHoleLens.init()
		net.kernelpanicsoft.relativity.client.lens.VertexLens.init()
		net.kernelpanicsoft.relativity.client.lens.LensTwins.register()
		net.kernelpanicsoft.relativity.client.lens.LensStageProcessor.register()
		MOD_BUS.addListener(net.neoforged.neoforge.client.event.RegisterShadersEvent::class.java) { net.kernelpanicsoft.relativity.client.lens.LensTwins.clear() }
		NeoForge.EVENT_BUS.addListener(RenderLivingEvent.Pre::class.java, NoodleRenderer::onRenderLiving)
		NeoForge.EVENT_BUS.addListener(ClientClocks::onClientTick)
		NeoForge.EVENT_BUS.addListener(LightDelay::onClientTick)
		NeoForge.EVENT_BUS.addListener(LightDelay::onLoggingOut)
		NeoForge.EVENT_BUS.addListener(ProperTimeHud::onDebugText)
		ReferenceShot.init()
		MOD_BUS.addListener(net.neoforged.neoforge.client.event.RegisterPresetEditorsEvent::class.java, PonderflatScreen::onRegisterEditors)
		MOD_BUS.addListener(net.neoforged.neoforge.client.event.ModelEvent.ModifyBakingResult::class.java, net.kernelpanicsoft.relativity.client.maglev.MaglevTrackModel::onModifyBakingResult)
		MOD_BUS.addListener(net.neoforged.neoforge.client.event.ModelEvent.ModifyBakingResult::class.java, net.kernelpanicsoft.relativity.client.maglev.MaglevBogeyRender::onModifyBakingResult)
		MOD_BUS.addListener(EntityRenderersEvent.RegisterRenderers::class.java) {
			// Holes draw nothing themselves: the lens and disk passes show them.
			it.registerEntityRenderer(RelativityEntities.BLACK_HOLE.get(), ::NoopRenderer)
			// Maglev bogeys as Create's: its renderer, or its Flywheel visual where that's on.
			val bogey = net.kernelpanicsoft.relativity.content.RelativityBlocks.MAGLEV_BOGEY_ENTITY.get()
			it.registerBlockEntityRenderer(bogey) { context -> BogeyBlockEntityRenderer<net.kernelpanicsoft.relativity.content.MaglevBogeyBlockEntity>(context) }
			SimpleBlockEntityVisualizer.builder(bogey).factory { context, entity, partialTick -> BogeyBlockEntityVisual(context, entity, partialTick) }.skipVanillaRender { false }.apply()
		}
	}
}
