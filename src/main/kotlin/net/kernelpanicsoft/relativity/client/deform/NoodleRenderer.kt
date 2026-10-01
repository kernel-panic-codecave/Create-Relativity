package net.kernelpanicsoft.relativity.client.deform

import net.kernelpanicsoft.relativity.spacetime.SpacetimeAttachments
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.neoforged.neoforge.client.event.RenderLivingEvent
import org.joml.Vector3f

/**
 * Draws tidally stretched living entities as noodles: the normal render is cancelled and redone through
 * [NoodleBuffers], which warps every vertex with a [NoodleWarp] built from the synced stretch and the nearest hole.
 */
object NoodleRenderer {
	private var redrawing = false

	fun onRenderLiving(event: RenderLivingEvent.Pre<*, *>) {
		if (redrawing) return
		val entity = event.entity
		val stretch = SpacetimeAttachments.bodyStretch.get(entity).toFloat()
		if (stretch <= 1.001f) return
		val level = entity.level()
		val hole = SpacetimeField.blackHoles(level).minByOrNull { it.center.distanceToSqr(entity.position()) } ?: return

		val partial = event.partialTick
		val camera = Minecraft.getInstance().gameRenderer.mainCamera.position
		val x = Mth.lerp(partial.toDouble(), entity.xo, entity.x)
		val y = Mth.lerp(partial.toDouble(), entity.yo, entity.y) + entity.bbHeight / 2.0
		val z = Mth.lerp(partial.toDouble(), entity.zo, entity.z)
		val center = Vector3f((x - camera.x).toFloat(), (y - camera.y).toFloat(), (z - camera.z).toFloat())
		val radial = Vector3f((x - hole.x).toFloat(), (y - hole.y).toFloat(), (z - hole.z).toFloat()).normalize()
		val motion = entity.deltaMovement
		val lag = Vector3f(-motion.x.toFloat(), -motion.y.toFloat(), -motion.z.toFloat())
		val warp = NoodleWarp(center, Vector3f(0f, 1f, 0f), entity.bbHeight, radial, stretch, lag)
		if (!warp.active) return

		event.isCanceled = true
		redrawing = true
		try {
			redraw(event, entity, partial, NoodleBuffers(event.multiBufferSource, warp))
		} finally {
			redrawing = false
		}
	}

	@Suppress("UNCHECKED_CAST")
	private fun <T : LivingEntity> redraw(event: RenderLivingEvent.Pre<*, *>, entity: T, partial: Float, buffers: NoodleBuffers) {
		val renderer = event.renderer as net.minecraft.client.renderer.entity.LivingEntityRenderer<T, *>
		val yaw = Mth.lerp(partial, entity.yRotO, entity.yRot)
		renderer.render(entity, yaw, partial, event.poseStack, buffers, event.packedLight)
	}
}
