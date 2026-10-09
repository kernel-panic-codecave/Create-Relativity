package net.kernelpanicsoft.relativity.client

import net.kernelpanicsoft.relativity.spacetime.SpacetimeAttachments
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent
import org.joml.Vector3d

/**
 * The chronometer, on the F3 screen's right side: how fast your clock runs, how far behind world time it is, how far
 * tides stretch you, and whether you're inside a horizon. Shown while spacetime round you isn't flat.
 */
object ProperTimeHud {
	fun onDebugText(event: CustomizeGuiOverlayEvent.DebugText) {
		val minecraft = Minecraft.getInstance()
		if (!minecraft.debugOverlay.showDebugScreen()) return
		val player = minecraft.player ?: return
		val level = minecraft.level ?: return
		if (SpacetimeField.isFlat(level)) return

		val center = Vector3d(player.x, player.y + player.bbHeight / 2.0, player.z)
		val motion = player.deltaMovement
		val rate = SpacetimeField.properTimeRate(level, center, Vector3d(motion.x, motion.y, motion.z).mul(20.0))
		val lag = SpacetimeAttachments.properTimeLag.get(player)
		val inside = SpacetimeField.insideHorizon(level, center) != null
		val stretch = SpacetimeAttachments.bodyStretch.get(player)
		val right = event.right
		right += ""
		right += "Relativity: clock rate %.4f, %.2f s behind world time".format(rate, lag)
		if (stretch >= 1.01) right += "Tidal stretch: %.2fx".format(stretch)
		if (inside) right += "§cInside an event horizon"
	}
}
