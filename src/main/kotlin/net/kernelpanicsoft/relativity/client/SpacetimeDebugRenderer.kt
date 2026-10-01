package net.kernelpanicsoft.relativity.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import foundry.veil.api.client.render.VeilLevelPerspectiveRenderer
import net.kernelpanicsoft.archie.gui.util.extension.invoke
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.spacetime.BlackHole
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Wireframe gizmos for each black hole until the lensing shader (M3) exists: the horizon as three great
 * circles, and photon orbits and ISCOs as equatorial rings (prograde bright, retrograde dim).
 */
object SpacetimeDebugRenderer {
	private const val SEGMENTS = 96
	private const val HORIZON = 0x9b30ff
	private const val PHOTON = 0xffd23f
	private const val ISCO = 0x3fe0ff

	fun render(event: RenderLevelStageEvent) {
		if (event.stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return
		if (!RelativityConfig.Client.Debug.showRadii) return
		if (VeilLevelPerspectiveRenderer.isRenderingPerspective()) return // keep gizmos out of the lens capture
		val minecraft = Minecraft.getInstance()
		val level = minecraft.level ?: return
		val holes = SpacetimeField.blackHoles(level)
		if (holes.isEmpty()) return

		val camera = event.camera.position
		val buffers = minecraft.renderBuffers().bufferSource()
		val lines = buffers.getBuffer(RenderType.lines())
		val poseStack = event.poseStack
		for (hole in holes) poseStack {
			translate(hole.x - camera.x, hole.y - camera.y, hole.z - camera.z)
			val pose = last()
			val horizon = hole.equatorialExtent(hole.horizonRadius)
			ring(lines, pose, horizon, Plane.XZ, HORIZON, 255)
			ring(lines, pose, horizon, Plane.XY, HORIZON, 255)
			ring(lines, pose, horizon, Plane.ZY, HORIZON, 255)
			ring(lines, pose, hole.equatorialExtent(hole.photonOrbit(prograde = true)), Plane.XZ, PHOTON, 255)
			ring(lines, pose, hole.equatorialExtent(hole.isco(prograde = true)), Plane.XZ, ISCO, 255)
			if (hole.spin > 0.0) {
				ring(lines, pose, hole.equatorialExtent(hole.photonOrbit(prograde = false)), Plane.XZ, PHOTON, 110)
				ring(lines, pose, hole.equatorialExtent(hole.isco(prograde = false)), Plane.XZ, ISCO, 110)
			}
		}
		buffers.endBatch(RenderType.lines())
	}

	private enum class Plane { XZ, XY, ZY }

	private fun ring(lines: VertexConsumer, pose: PoseStack.Pose, radius: Double, plane: Plane, rgb: Int, alpha: Int) {
		val r = (rgb shr 16) and 0xff
		val g = (rgb shr 8) and 0xff
		val b = rgb and 0xff
		for (i in 0 until SEGMENTS) {
			val a0 = 2 * PI * i / SEGMENTS
			val a1 = 2 * PI * (i + 1) / SEGMENTS
			val p0 = point(radius, a0, plane)
			val p1 = point(radius, a1, plane)
			val nx = p1[0] - p0[0]
			val ny = p1[1] - p0[1]
			val nz = p1[2] - p0[2]
			val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
			lines.addVertex(pose, p0[0], p0[1], p0[2]).setColor(r, g, b, alpha).setNormal(pose, nx / len, ny / len, nz / len)
			lines.addVertex(pose, p1[0], p1[1], p1[2]).setColor(r, g, b, alpha).setNormal(pose, nx / len, ny / len, nz / len)
		}
	}

	private fun point(radius: Double, angle: Double, plane: Plane): FloatArray {
		val u = (radius * cos(angle)).toFloat()
		val v = (radius * sin(angle)).toFloat()
		return when (plane) {
			Plane.XZ -> floatArrayOf(u, 0f, v)
			Plane.XY -> floatArrayOf(u, v, 0f)
			Plane.ZY -> floatArrayOf(0f, v, u)
		}
	}
}
