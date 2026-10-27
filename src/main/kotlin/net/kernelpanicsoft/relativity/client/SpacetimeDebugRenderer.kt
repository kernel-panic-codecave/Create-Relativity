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
import net.kernelpanicsoft.relativity.client.lens.VertexLens
import net.kernelpanicsoft.relativity.spacetime.BlackHole
import net.kernelpanicsoft.relativity.spacetime.FieldSource
import org.joml.Vector3d
import org.joml.Vector3dc
import kotlin.math.abs
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
import kotlin.math.sqrt

/**
 * F3 + Z's wireframe gizmos (with [SpacetimeGrid]'s grid), one kind per source. A black hole: the horizon (oblate when
 * spinning) and the ergosphere as meridians, with chevrons showing which way it drags, and photon orbits and ISCOs as
 * equatorial rings (prograde bright, retrograde dim). A mass: its softened core as three great circles and a cross at
 * its centre. A warp bubble (round or ellipsoidal): its wall (white) as three rings and an arrow along its push. A warp tube or
 * Krasnikov tube: its spine, rings of its radius along it and chevrons the way it's fast. They mark true positions,
 * so while the lens is active they're drawn after it, on top of everything ([renderAfterLens]), instead of into the
 * world where they'd be bent.
 */
object SpacetimeDebugRenderer {
	private const val SEGMENTS = 96
	private const val HORIZON = 0x9b30ff
	private const val PHOTON = 0xffd23f
	private const val ISCO = 0x3fe0ff
	private const val ERGOSPHERE = 0xff5a3f
	private const val MERIDIANS = 3
	private const val MASS = 0xffa040
	private const val BUBBLE = 0xffffff
	private const val TUBE = 0x40c0ff
	private const val KRASNIKOV = 0xff60c0

	/** Rings along a tube at most this far apart (blocks), and no more than [TUBE_RINGS] of them. */
	private const val RING_SPACING = 2.0
	private const val TUBE_RINGS = 96

	/** Hides the gizmos whatever the setting (GameTests comparing screenshots). */
	@JvmField
	var hidden = false

	fun render(event: RenderLevelStageEvent) {
		if (event.stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return
		if (hidden) return
		if (VeilLevelPerspectiveRenderer.isRenderingPerspective()) return // keep gizmos out of other level views
		if (BlackHoleLens.active) return // drawn undistorted after the lens instead
		val minecraft = Minecraft.getInstance()
		val level = minecraft.level ?: return
		if (!SpacetimeGrid.shown) return
		val sources = SpacetimeField.sources(level)
		if (sources.isEmpty()) return

		val buffers = minecraft.renderBuffers().bufferSource()
		val lines = buffers.getBuffer(RenderType.lines())
		drawSources(lines, event.poseStack, event.camera.position, sources)
		if (SpacetimeGrid.visible) SpacetimeGrid.draw(lines, event.poseStack.last(), event.camera.position)
		buffers.endBatch(RenderType.lines())
	}

	/**
	 * Draws the gizmos over the finished, lensed frame with [projection], depth testing off: they're markers, not
	 * scenery.
	 */
	fun renderAfterLens(projection: Matrix4f) {
		if (hidden) return
		val minecraft = Minecraft.getInstance()
		val level = minecraft.level ?: return
		if (!SpacetimeGrid.shown) return
		val sources = SpacetimeField.sources(level)
		if (sources.isEmpty()) return
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
		RenderSystem.lineWidth(2.5f)
		// Gizmos mark true positions: keep the vertex lens out of them.
		VertexLens.setEnabled(false)

		val poseStack = PoseStack()
		poseStack.mulPose(camera.rotation().conjugate(Quaternionf()))
		val buffer = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL)
		drawSources(buffer, poseStack, camera.position, sources)
		if (SpacetimeGrid.visible) SpacetimeGrid.draw(buffer, poseStack.last(), camera.position)
		buffer.build()?.let(BufferUploader::drawWithShader)

		RenderSystem.lineWidth(1f)
		RenderSystem.enableCull()
		RenderSystem.enableDepthTest()
		modelView.popMatrix()
		RenderSystem.applyModelViewMatrix()
		RenderSystem.restoreProjectionMatrix()
	}

	private fun drawSources(lines: VertexConsumer, poseStack: PoseStack, camera: Vec3, sources: List<FieldSource>) {
		drawHoles(lines, poseStack, camera, sources.filterIsInstance<BlackHole>())
		val pose = poseStack.last()
		val at = { v: Vector3dc -> Vector3d(v.x() - camera.x, v.y() - camera.y, v.z() - camera.z) }
		for (source in sources) when (source) {
			is BlackHole -> {}
			is FieldSource.Mass -> {
				val c = at(source.center)
				val r = maxOf(source.softening, 0.25)
				val axes = listOf(Vector3d(r, 0.0, 0.0), Vector3d(0.0, r, 0.0), Vector3d(0.0, 0.0, r))
				ellipse(lines, pose, c, axes[0], axes[2], MASS)
				ellipse(lines, pose, c, axes[0], axes[1], MASS)
				ellipse(lines, pose, c, axes[2], axes[1], MASS)
				for (a in axes) segment(lines, pose, Vector3d(c).sub(Vector3d(a).mul(0.3)), Vector3d(c).add(Vector3d(a).mul(0.3)), MASS, 255)
			}
			is FieldSource.Bubble -> {
				val c = at(source.center)
				val r = source.radius
				val axes = listOf(Vector3d(r, 0.0, 0.0), Vector3d(0.0, r, 0.0), Vector3d(0.0, 0.0, r))
				bubble(lines, pose, c, axes, source.direction, source.speed)
			}
			is FieldSource.Ellipsoid -> bubble(lines, pose, at(source.center), source.axes.map { Vector3d(it) }, source.direction, source.speed)
			is FieldSource.Tube -> tube(lines, pose, source.spine.map(at), source.radius, if (source.speed < 0.0) -1 else 1, TUBE)
			is FieldSource.Krasnikov -> tube(lines, pose, source.spine.map(at), source.radius, 1, KRASNIKOV)
		}
	}

	/** An ellipse round [c] with semi-axis vectors [u] and [v]. */
	private fun ellipse(lines: VertexConsumer, pose: PoseStack.Pose, c: Vector3dc, u: Vector3dc, v: Vector3dc, rgb: Int, alpha: Int = 255) {
		var previous: Vector3d? = null
		for (i in 0..SEGMENTS) {
			val angle = 2 * PI * i / SEGMENTS
			val p = Vector3d(c).add(Vector3d(u).mul(cos(angle))).add(Vector3d(v).mul(sin(angle)))
			previous?.let { segment(lines, pose, it, p, rgb, alpha) }
			previous = p
		}
	}

	/** A warp bubble's wall (semi-axes [axes]: a ring in each plane two of them span) and an arrow along its push. */
	private fun bubble(lines: VertexConsumer, pose: PoseStack.Pose, c: Vector3dc, axes: List<Vector3dc>, direction: Vector3dc, speed: Double) {
		ellipse(lines, pose, c, axes[0], axes[1], BUBBLE)
		ellipse(lines, pose, c, axes[1], axes[2], BUBBLE)
		ellipse(lines, pose, c, axes[0], axes[2], BUBBLE)
		if (speed == 0.0 || direction.lengthSquared() == 0.0) return
		val n = Vector3d(direction).normalize().mul(if (speed < 0.0) -1.0 else 1.0)
		// (As long as the wall's reach that way: the semi-axes are square to each other.)
		val reach = sqrt(axes.sumOf { it.dot(n) * it.dot(n) }).coerceAtLeast(0.5)
		val tip = Vector3d(c).add(Vector3d(n).mul(1.3 * reach))
		segment(lines, pose, c, tip, BUBBLE, 255)
		arrowHead(lines, pose, tip, n, 0.2 * reach, BUBBLE)
	}

	/**
	 * A tube round the polyline [spine]: the spine, rings of [radius] along it, and chevrons the way it's fast
	 * ([sign] 1: from the spine's first point on; -1 back).
	 */
	private fun tube(lines: VertexConsumer, pose: PoseStack.Pose, spine: List<Vector3d>, radius: Double, sign: Int, rgb: Int) {
		if (spine.size < 2) return
		val length = spine.zipWithNext().sumOf { (a, b) -> a.distance(b) }
		val spacing = maxOf(RING_SPACING, length / TUBE_RINGS)
		var carried = 0.0
		for ((a, b) in spine.zipWithNext()) {
			segment(lines, pose, a, b, rgb, 255)
			val l = a.distance(b)
			if (l < 1e-9) continue
			val t = Vector3d(b).sub(a).div(l)
			// (Two directions across it.)
			val across = Vector3d(if (abs(t.y) < 0.9) Vector3d(0.0, 1.0, 0.0) else Vector3d(1.0, 0.0, 0.0)).cross(t).normalize()
			val other = Vector3d(t).cross(across).normalize()
			var s = carried
			while (s <= l) {
				val p = Vector3d(a).add(Vector3d(t).mul(s))
				ellipse(lines, pose, p, Vector3d(across).mul(radius), Vector3d(other).mul(radius), rgb, 150)
				arrowHead(lines, pose, Vector3d(p).add(Vector3d(t).mul(0.25 * radius * sign)), Vector3d(t).mul(sign.toDouble()), 0.4 * radius, rgb)
				s += spacing
			}
			carried = s - l
		}
	}

	/** A chevron at [tip] pointing along unit [n], [size] across. */
	private fun arrowHead(lines: VertexConsumer, pose: PoseStack.Pose, tip: Vector3dc, n: Vector3dc, size: Double, rgb: Int) {
		val across = Vector3d(if (abs(n.y()) < 0.9) Vector3d(0.0, 1.0, 0.0) else Vector3d(1.0, 0.0, 0.0)).cross(n).normalize()
		val other = Vector3d(n).cross(across).normalize()
		for (side in listOf(across, Vector3d(across).negate(), other, Vector3d(other).negate())) {
			val back = Vector3d(tip).sub(Vector3d(n).mul(size)).add(Vector3d(side).mul(0.5 * size))
			segment(lines, pose, back, tip, rgb, 255)
		}
	}

	private fun segment(lines: VertexConsumer, pose: PoseStack.Pose, p0: Vector3dc, p1: Vector3dc, rgb: Int, alpha: Int) =
		segment(lines, pose, floatArrayOf(p0.x().toFloat(), p0.y().toFloat(), p0.z().toFloat()), floatArrayOf(p1.x().toFloat(), p1.y().toFloat(), p1.z().toFloat()), rgb, alpha)

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
		ring(lines, pose, sqrt(radius(0.0).let { it * it } + a * a), Plane.XZ, rgb, 255)
		for (m in 0 until MERIDIANS) {
			val azimuth = PI * m / MERIDIANS
			var previous: FloatArray? = null
			for (i in 0..SEGMENTS) {
				val theta = 2 * PI * i / SEGMENTS // full circle: both halves of the meridian
				val cosTheta = cos(theta)
				val r = radius(cosTheta)
				val rho = sqrt(r * r + a * a) * sin(theta)
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
		val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
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
			val len = sqrt(nx * nx + ny * ny + nz * nz)
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
