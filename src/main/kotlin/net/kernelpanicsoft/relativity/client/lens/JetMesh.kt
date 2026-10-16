package net.kernelpanicsoft.relativity.client.lens

import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.PoseStack
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.spacetime.BlackHole
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The polar jets of a spinning, feeding hole: two beams of plasma along its spin axis (world up), launched by the
 * spinning horizon's magnetic field (Blandford-Znajek: power ~ spin^2 x the feeding), drawn as geometry through the
 * vertex lens (bent with everything else) in the additive lightning render type: each jet a ribbon facing the camera,
 * bright along its core and soft at its edges, opening slowly with distance, its brightness falling off along it, with
 * knots streaming outward. Their plasma moves at [SPEED] of light: the jet coming toward the camera is beamed brighter,
 * the one going away dimmer (Doppler, D^2, softened so the far one still shows). Seen down the axis, a soft disc.
 */
object JetMesh {
	@JvmField
	var enabled = true

	/** Length of each jet (in r_s), launch point (r_s from the centre), and opening half-angle. */
	private const val LENGTH = 40.0
	private const val LAUNCH = 1.2
	private const val OPENING = 0.06

	/** The plasma's speed (of light's), and the knots' (they move with it, in blocks of light per second of game). */
	private const val SPEED = 0.9

	/** Overall brightness (the additive alpha at a side-on jet's core, launch, full power). */
	private const val BRIGHTNESS = 1.6

	/** Segments along a jet, and columns across its ribbon. */
	private const val SEGMENTS = 96
	private const val COLUMNS = 8

	private val buffers by lazy { MultiBufferSource.immediate(ByteBufferBuilder(1 shl 16)) }

	/** How bright [hole]'s jets are (0 for none): spin squared times the feeding (as Eddington's), capped. */
	fun power(hole: BlackHole): Double {
		val ratio = hole.accretionRate / RelativityConfig.eddingtonRate(hole.schwarzschildRadius)
		return min(hole.spin * hole.spin * ratio, 1.5)
	}

	fun draw(hole: BlackHole?, modelView: org.joml.Matrix4f) {
		if (!enabled || hole == null) return
		val power = power(hole)
		if (power < 0.01) return
		val minecraft = Minecraft.getInstance()
		val level = minecraft.level ?: return
		val camera = minecraft.gameRenderer.mainCamera.position
		val hx = hole.x - camera.x
		val hy = hole.y - camera.y
		val hz = hole.z - camera.z
		val rs = hole.schwarzschildRadius
		val c = RelativityConfig.speedOfLight(level.dimension())
		val time = (level.gameTime % 72000L + minecraft.timer.getGameTimeDeltaPartialTick(false)) / 20.0 * c
		val stack = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack()
		stack.pushMatrix()
		stack.set(modelView)
		com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix()
		VertexLens.ownSecondImages = true
		try {
			val consumer = buffers.getBuffer(JetRenderType.TYPE)
			val pose = PoseStack().last()
			for (sign in doubleArrayOf(1.0, -1.0)) emitJet(consumer, pose, hx, hy, hz, rs, sign, power, time)
			buffers.endBatch()
		} finally {
			VertexLens.ownSecondImages = false
			stack.popMatrix()
			com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix()
		}
	}

	/** One jet, up ([sign] 1) or down, from the hole at (hx, hy, hz) (camera-relative). */
	private fun emitJet(
		consumer: com.mojang.blaze3d.vertex.VertexConsumer, pose: PoseStack.Pose,
		hx: Double, hy: Double, hz: Double, rs: Double, sign: Double, power: Double, time: Double,
	) {
		// The ribbon's width direction: across the axis, facing the camera (the axis's direction cross the line of
		// sight); looking down the axis, any.
		val toCamera = doubleArrayOf(-hx, -hy, -hz).let { v -> val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-9); DoubleArray(3) { v[it] / n } }
		var wx = -toCamera[2]
		var wz = toCamera[0]
		val wn = sqrt(wx * wx + wz * wz)
		val endOn = wn < 0.05
		if (endOn) { wx = 1.0; wz = 0.0 } else { wx /= wn; wz /= wn }
		// Beaming: the plasma moves along +y x sign; toward the camera by cos(angle) = sign * toCamera.y.
		val cosine = sign * toCamera[1]
		// (D^2 over a side-on jet's: 1 / (1 - v cos)^2, 1 side on, softened at both ends.)
		val beaming = (1.0 / (1.0 - SPEED * cosine)).pow(2.0).coerceIn(0.2, 4.0)
		val brightness = BRIGHTNESS * power * beaming
		fun put(x: Double, y: Double, z: Double, r: Double, g: Double, b: Double, a: Double) {
			fun byte(v: Double) = (v.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
			consumer.addVertex(pose, x.toFloat(), y.toFloat(), z.toFloat()).setColor(byte(r), byte(g), byte(b), byte(a))
		}
		// The colour: hot synchrotron blue-white in the core, violet at the edges.
		fun colour(across: Double): DoubleArray = doubleArrayOf(0.62 + 0.38 * (1 - across), 0.66 + 0.34 * (1 - across), 1.0)
		// Brightness at distance s (r_s) along it at this moment: falling off along it, knots streaming outward.
		fun glow(s: Double): Double {
			val fade = exp(-s / (0.35 * LENGTH)) * (1.0 - exp(-(s - LAUNCH) * 2.0)).coerceAtLeast(0.0)
			val phase = s * rs - time * SPEED
			val knots = 0.55 + 0.45 * knot(phase / (3.0 * rs))
			return fade * knots
		}
		for (k in 0 until SEGMENTS) {
			val s0 = LAUNCH + (LENGTH - LAUNCH) * (k.toDouble() / SEGMENTS).pow(1.6)
			val s1 = LAUNCH + (LENGTH - LAUNCH) * ((k + 1.0) / SEGMENTS).pow(1.6)
			val g0 = glow(s0) * brightness
			val g1 = glow(s1) * brightness
			val w0 = (0.25 + OPENING * s0) * rs
			val w1 = (0.25 + OPENING * s1) * rs
			val y0 = hy + sign * s0 * rs
			val y1 = hy + sign * s1 * rs
			if (endOn) {
				// Down the axis: a soft disc at each step (rings of quads), dimmer for the overlap.
				for (j in 0 until 16) {
					val a0 = 2 * PI * j / 16
					val a1 = 2 * PI * (j + 1) / 16
					val core = colour(0.0)
					put(hx, y0, hz, core[0], core[1], core[2], g0 * 0.15)
					put(hx + w0 * cos(a0), y0, hz + w0 * sin(a0), core[0], core[1], core[2], 0.0)
					put(hx + w0 * cos(a1), y0, hz + w0 * sin(a1), core[0], core[1], core[2], 0.0)
					put(hx, y0, hz, core[0], core[1], core[2], g0 * 0.15)
				}
				continue
			}
			for (column in 0 until COLUMNS) {
				val u0 = -1.0 + 2.0 * column / COLUMNS
				val u1 = -1.0 + 2.0 * (column + 1) / COLUMNS
				val c0 = colour(abs(u0))
				val c1 = colour(abs(u1))
				// Across: gaussian, bright core.
				val p0 = exp(-3.0 * u0 * u0)
				val p1 = exp(-3.0 * u1 * u1)
				put(hx + u0 * w0 * wx, y0, hz + u0 * w0 * wz, c0[0], c0[1], c0[2], g0 * p0)
				put(hx + u1 * w0 * wx, y0, hz + u1 * w0 * wz, c1[0], c1[1], c1[2], g0 * p1)
				put(hx + u1 * w1 * wx, y1, hz + u1 * w1 * wz, c1[0], c1[1], c1[2], g1 * p1)
				put(hx + u0 * w1 * wx, y1, hz + u0 * w1 * wz, c0[0], c0[1], c0[2], g1 * p0)
			}
		}
	}

	/** Smooth 1D value noise in 0..1 (the knots along a jet). */
	private fun knot(x: Double): Double {
		val i = floor(x)
		val f = x - i
		val w = f * f * (3 - 2 * f)
		fun hash(n: Double): Double { val v = sin(n * 127.1) * 43758.5453; return v - floor(v) }
		val a = hash(i)
		val b = hash(i + 1)
		return (a + (b - a) * w).pow(2.0)
	}
}

/**
 * The jets' render type: the lightning one's (additive, position and colour, lensed like everything drawn in the level),
 * but writing depth, so the post pass takes them for drawn geometry and leaves them (as sky, it bent the sky over them).
 * Drawn last of the hole's own geometry, after the disk. (A subclass, for RenderStateShard's protected states.)
 */
private class JetRenderType private constructor() : net.minecraft.client.renderer.RenderStateShard("create_relativity_jet", {}, {}) {
	companion object {
		val TYPE: RenderType = RenderType.create(
			"create_relativity_jet", com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
			1 shl 16, false, true,
			RenderType.CompositeState.builder()
				.setShaderState(RENDERTYPE_LIGHTNING_SHADER)
				.setTransparencyState(LIGHTNING_TRANSPARENCY)
				.setCullState(NO_CULL)
				.setWriteMaskState(COLOR_DEPTH_WRITE)
				.createCompositeState(false),
		)
	}
}
