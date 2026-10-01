package net.kernelpanicsoft.relativity.client

import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.spacetime.SpacetimeAttachments
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.gui.VanillaGuiLayers
import org.joml.Vector3d

/** Chronometer in the top-right corner: how fast your clock runs, how far behind world time it is, and the horizon. */
object ProperTimeHud {
	private val LAYER = CreateRelativity.id("proper_time")

	fun register(event: RegisterGuiLayersEvent) = event.registerAbove(VanillaGuiLayers.DEBUG_OVERLAY, LAYER, ::render)

	private fun render(graphics: GuiGraphics, delta: DeltaTracker) {
		val minecraft = Minecraft.getInstance()
		val player = minecraft.player ?: return
		val level = minecraft.level ?: return
		if (minecraft.options.hideGui || SpacetimeField.blackHoles(level).isEmpty()) return

		val center = Vector3d(player.x, player.y + player.bbHeight / 2.0, player.z)
		val motion = player.deltaMovement
		val rate = SpacetimeField.properTimeRate(level, center, Vector3d(motion.x, motion.y, motion.z).mul(20.0))
		val lag = SpacetimeAttachments.properTimeLag.get(player)
		val inside = SpacetimeField.insideHorizon(level, center) != null
		val stretch = SpacetimeAttachments.bodyStretch.get(player)
		if (rate > 0.9995 && lag < 0.05 && !inside && stretch < 1.01) return

		val font = minecraft.font
		val lines = buildList {
			add(Component.translatable("hud.create_relativity.clock_rate", "%.4f".format(rate)))
			add(Component.translatable("hud.create_relativity.clock_lag", "%.2f".format(lag)))
			if (stretch >= 1.01) add(Component.translatable("hud.create_relativity.body_stretch", "%.2f".format(stretch)))
			if (inside) add(Component.translatable("hud.create_relativity.inside_horizon"))
		}
		val right = graphics.guiWidth() - 4
		for ((i, line) in lines.withIndex()) {
			val color = if (inside && i == lines.lastIndex) 0xFF5555 else 0xE0E0FF
			graphics.drawString(font, line, right - font.width(line), 4 + i * (font.lineHeight + 2), color)
		}
	}
}
