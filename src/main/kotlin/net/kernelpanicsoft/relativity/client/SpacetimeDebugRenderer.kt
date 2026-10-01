package net.kernelpanicsoft.relativity.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexSorting
import net.kernelpanicsoft.relativity.client.lens.BlackHoleLens
import com.mojang.blaze3d.vertex.VertexConsumer
import foundry.veil.api.client.render.VeilLevelPerspectiveRenderer
import net.kernelpanicsoft.archie.gui.util.extension.invoke
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.spacetime.BlackHole
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Quaternionf
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Wireframe gizmos for each black hole: the horizon (oblate when spinning) and the ergosphere as meridians, with
 * chevrons showing which way it drags, and photon orbits and ISCOs as equatorial rings (prograde bright, retrograde dim). They mark true positions, so while the lens is active they're
 * drawn after it, on top of everything ([renderAfterLens]), instead of into the world where they'd be bent.
 */
object SpacetimeDebugRenderer {
	private const val SEGMENTS = 96
	private const val HORIZON = 0x9b30ff
	private const val PHOTON = 0xffd23f
	private const val ISCO = 0x3fe0ff
	private const val ERGOSPHERE = 0xff5a3f
	private const val MERIDIANS = 3

	fun render(event: RenderLevelStageEvent) {
		if (event.stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return
		if (!RelativityConfig.Client.Debug.showRadii) return
		if (VeilLevelPerspectiveRenderer.isRenderingPerspective()) return // keep gizmos out of the lens capture
		if (BlackHoleLens.active) return // drawn undistorted after the lens instead
		val minecraft = Minecraft.getInstance()
		val level = minecraft.level ?: return
		val holes = SpacetimeField.blackHoles(level)
		if (holes.isEmpty()) return

		val buffers = minecraft.renderBuffers().bufferSource()
		drawHoles(buffers.getBuffer(RenderType.lines()), event.poseStack, event.camera.position, holes)
		buffers.endBatch(RenderType.lines())
	}

	/**
	 * Draws the gizmos over the finished, lensed frame with [projection] (the screen's own, not the overscanned
	 * one), depth testing off: they're markers, not scenery.
	 */
	fun renderAfterLens(projection: Matrix4f) {
		if (!RelativityConfig.Client.Debug.showRadii) return
		val minecraft = Minecraft.getInstance()
		val level = minecraft.level ?: return
		val holes = SpacetimeField.blackHoles(level)
		if (holes.isEmpty()) return
		val camera = minecraft.gameRenderer.mainCamera

		minecraft.mainRenderTarget.bindWrite(false)
		RenderSystem.backupProjectionMatrix()
		RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN)
		val modelView = RenderSystem.getModelViewStack()
		modelView.pushMatrix()
		modelView.identity()
		RenderSystem.applyModelViewMatrix()
		RenderSystem.disableDepthTest()
		// Lines are drawn as screen-facing quads; with culling on, every segment winding the other way vanishes.
		RenderSystem.disableCull()
		RenderSystem.enableBlend()
		RenderSystem.defaultBlendFunc()
		RenderSystem.setShader(GameRenderer::getRendertypeLinesShader)
		RenderSystem.lineWidth(2f)

		val poseStack = PoseStack()
		poseStack.mulPose(camera.rotation().conjugate(Quaternionf()))
		val buffer = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL)
		drawHoles(buffer, poseStack, camera.position, holes)
		buffer.build()?.let(BufferUploader::drawWithShader)

		RenderSystem.lineWidth(1f)
		RenderSystem.enableCull()
		RenderSystem.enableDepthTest()
		modelView.popMatrix()
		RenderSystem.applyModelViewMatrix()
		RenderSystem.restoreProjectionMatrix()
	}

	private fun drawHoles(lines: VertexConsumer, poseStack: PoseStack, camera: Vec3, holes: List<BlackHole>) {
		for (hole in holes) poseStack {
			translate(hole.x - camera.x, hole.y - camera.y, hole.z - camera.z)
			val pose = last()
			val a = hole.spin * hole.mass
			val horizon = hole.horizonRadius
			surface(lines, pose, a, HORIZON) { horizon }
			if (hole.spin != 0.0) {
				surface(lines, pose, a, ERGOSPHERE, hole::ergosphereRadius)
				spinChevrons(lines, pose, hole.equatorialExtent(hole.ergosphereRadius(0.0)), hole.spin)
			}
			ring(lines, pose, hole.equatorialExtent(hole.photonOrbit(prograde = true)), Plane.XZ, PHOTON, 255)
			ring(lines, pose, hole.equatorialExtent(hole.isco(prograde = true)), Plane.XZ, ISCO, 255)
			if (hole.spin != 0.0) {
				ring(lines, pose, hole.equatorialExtent(hole.photonOrbit(prograde = false)), Plane.XZ, PHOTON, 110)
				ring(lines, pose, hole.equatorialExtent(hole.isco(prograde = false)), Plane.XZ, ISCO, 110)
			}
		}
	}

	private enum class Plane { XZ, XY, ZY }

	/**
	 * A spheroidal surface r(cos θ) (Kerr–Schild: ρ = √(r² + a²) sin θ, height r cos θ, spin axis up): its equator and
	 * [MERIDIANS] meridians. A Kerr horizon (constant r) comes out oblate; the ergosphere touches it at the poles.
	 */
	private fun surface(lines: VertexConsumer, pose: PoseStack.Pose, a: Double, rgb: Int, radius: (Double) -> Double) {
		ring(lines, pose, kotlin.math.sqrt(radius(0.0).let { it * it } + a * a), Plane.XZ, rgb, 255)
		for (m in 0 until MERIDIANS) {
			val azimuth = PI * m / MERIDIANS
			var previous: FloatArray? = null
			for (i in 0..SEGMENTS) {
				val theta = 2 * PI * i / SEGMENTS // full circle: both halves of the meridian
				val cosTheta = cos(theta)
				val r = radius(cosTheta)
				val rho = kotlin.math.sqrt(r * r + a * a) * sin(theta)
				val point = floatArrayOf((rho * cos(azimuth)).toFloat(), (r * cosTheta).toFloat(), (rho * sin(azimuth)).toFloat())
				previous?.let { segment(lines, pose, it, point, rgb, 255) }
				previous = point
			}
		}
	}

	/** Chevrons around the ergosphere's equator pointing the way it drags things (prograde). */
	private fun spinChevrons(lines: VertexConsumer, pose: PoseStack.Pose, radius: Double, spin: Double) {
		val sign = if (spin > 0.0) 1.0 else -1.0
		val size = 0.15 * radius
		for (k in 0 until 8) {
			val angle = 2 * PI * k / 8
			val x = radius * cos(angle)
			val z = radius * sin(angle)
			// Positive spin turns game +z toward +x (counter-clockwise about physics +z, which is game up).
			val tx = sign * sin(angle)
			val tz = -sign * cos(angle)
			val tip = floatArrayOf((x + tx * size).toFloat(), 0f, (z + tz * size).toFloat())
			for (side in listOf(-1.0, 1.0)) {
				val back = floatArrayOf(
					(x - tx * size * 0.2 + side * cos(angle) * size * 0.5).toFloat(), 0f,
					(z - tz * size * 0.2 + side * sin(angle) * size * 0.5).toFloat(),
				)
				segment(lines, pose, back, tip, ERGOSPHERE, 255)
			}
		}
	}

	private fun segment(lines: VertexConsumer, pose: PoseStack.Pose, p0: FloatArray, p1: FloatArray, rgb: Int, alpha: Int) {
		val nx = p1[0] - p0[0]
		val ny = p1[1] - p0[1]
		val nz = p1[2] - p0[2]
		val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
		val r = (rgb shr 16) and 0xff
		val g = (rgb shr 8) and 0xff
		val b = rgb and 0xff
		lines.addVertex(pose, p0[0], p0[1], p0[2]).setColor(r, g, b, alpha).setNormal(pose, nx / len, ny / len, nz / len)
		lines.addVertex(pose, p1[0], p1[1], p1[2]).setColor(r, g, b, alpha).setNormal(pose, nx / len, ny / len, nz / len)
	}

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
