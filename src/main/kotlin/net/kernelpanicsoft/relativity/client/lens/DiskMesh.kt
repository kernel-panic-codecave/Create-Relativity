package net.kernelpanicsoft.relativity.client.lens

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.PoseStack
import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.physics.lens.SchwarzschildImages
import net.kernelpanicsoft.relativity.spacetime.BlackHole
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The accretion disk as geometry: a ring mesh in the hole's equatorial plane, drawn through the vertex lens like the
 * world (so a shader pack shades it, as the emissive geometry it is, and the second-image draw gives the far side of the
 * disk arched over the shadow). The colours are the post pass's (black_hole_lens.fsh's diskColor: blackbody, Doppler
 * and gravitational shift, the turbulence turning with each ring), worked out per vertex here for the image each draw
 * shows: the shift depends on which way the light leaves the disk, so the primary and secondary images are coloured
 * apart, each from the Schwarzschild image tables (VertexLens.primaryImages / secondaryImages), as the vertex lens places
 * them round a hole without spin (round a spinning one, which feeding makes of any hole, the colours are that
 * approximation; the placement is the Kerr map's). While it's on, the post pass draws no disk.
 */
object DiskMesh {
	@JvmField
	var enabled = true

	/** Draws the secondary images too (debug: off to look at the primary alone). */
	@JvmField
	var secondaries = true

	private const val AZIMUTHS = 384
	private const val RINGS = 48
	private const val TEXTURE_WIDTH = 256
	private const val TEXTURE_HEIGHT = 64

	private val TEXTURE: ResourceLocation = CreateRelativity.id("dynamic/disk_turbulence")
	private var textureReady = false
	private val buffers by lazy { MultiBufferSource.immediate(ByteBufferBuilder(1 shl 18)) }

	/** Whether the mesh draws [hole]'s disk this frame (so the post pass leaves it out). */
	fun drawsDisk(hole: BlackHole?): Boolean = enabled && hole != null && hole.accretionRate > 0.0

	/** After the translucent blocks: the disk round the lensing hole, primary images, then secondary ones. */
	fun draw(hole: BlackHole?, modelView: org.joml.Matrix4f) {
		if (!drawsDisk(hole) || hole == null) return
		val primary = VertexLens.primaryImages ?: return
		val minecraft = Minecraft.getInstance()
		val level = minecraft.level ?: return
		ensureTexture(minecraft)
		val camera = minecraft.gameRenderer.mainCamera.position
		val ratio = (hole.accretionRate / RelativityConfig.eddingtonRate(hole.schwarzschildRadius)).coerceIn(0.05, 2.0)
		val rIn = hole.equatorialExtent(hole.isco(prograde = true))
		val rOut = 4.0 * rIn
		val sense = if (hole.spin < 0.0) -1.0 else 1.0
		val c = RelativityConfig.speedOfLight(level.dimension())
		val time = (level.gameTime % 72000L + minecraft.timer.getGameTimeDeltaPartialTick(false)) / 20.0 * c
		val geometry = Geometry(hole, camera.x, camera.y, camera.z, rIn, rOut, ratio, sense, time)
		val type = DiskRenderType.TYPE
		VertexLens.ownSecondImages = true
		// The fine near-axis cut even round a spinning hole: the mesh is fine and its shader has the pixel check, and the
		// far side of the disk, behind the hole, lies along that axis.
		VertexLens.fineAxisCut(true)
		// The level's view rotation, exactly once (vertices are camera-relative, world axes), whatever this stage left set.
		val stack = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack()
		stack.pushMatrix()
		stack.set(modelView)
		com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix()
		try {
			geometry.emit(buffers.getBuffer(type), primary, false)
			buffers.endBatch()
			val secondary = VertexLens.secondaryImages
			if (secondary != null && VertexLens.secondImagesThisFrame && secondaries) {
				VertexLens.drawSecondImages {
					geometry.emit(buffers.getBuffer(type), secondary, true)
					buffers.endBatch()
				}
			}
		} finally {
			VertexLens.ownSecondImages = false
			VertexLens.fineAxisCut(false)
			stack.popMatrix()
			com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix()
		}
	}

	/** The disk's mesh and colours for one camera position. */
	private class Geometry(
		val hole: BlackHole, val camX: Double, val camY: Double, val camZ: Double,
		val rIn: Double, val rOut: Double, val ratio: Double, val sense: Double, val time: Double,
	) {
		val rs = hole.schwarzschildRadius
		// The hole relative to the camera, and its unit vector toward the camera (e1, as the vertex lens has it).
		val hx = hole.x - camX
		val hy = hole.y - camY
		val hz = hole.z - camZ
		val rc = sqrt(hx * hx + hy * hy + hz * hz)

		fun radius(ring: Int) = rIn * (rOut / rIn).pow(ring / RINGS.toDouble())

		fun emit(consumer: com.mojang.blaze3d.vertex.VertexConsumer, table: FloatArray, secondary: Boolean) {
			if (rc <= rs) return
			val pose = PoseStack().last()
			val colors = colorsFor(this, table, secondary)
			for (ring in 0 until RINGS) for (a in 0 until AZIMUTHS) {
				val corners = arrayOf(ring to a, ring to a + 1, ring + 1 to a + 1, ring + 1 to a)
				// (One winding: the render type doesn't cull, so it's seen from above and below, and mirrored.)
				run {
					for ((k, j) in corners) {
						val r = radius(k)
						val phi = 2.0 * PI * j / AZIMUTHS
						val omega = sense * sqrt(0.5 * rs / (r * r * r))
						val u = ((phi - omega * time) / (2.0 * PI)).toFloat()
						val v = (k.toFloat() + 0.5f) / (RINGS + 1)
						val argb = colors[k][j]
						consumer.addVertex(pose, (hx + r * cos(phi)).toFloat(), hy.toFloat(), (hz + r * sin(phi)).toFloat())
							.setColor((argb shr 16) and 255, (argb shr 8) and 255, argb and 255, (argb ushr 24) and 255)
							.setUv(u, v)
							.setLight(0xF000F0)
							.setNormal(0f, 1f, 0f)
					}
				}
			}
		}

		/**
		 * The colour (ARGB, tonemapped, before the turbulence texture's factor) of the disk at radius [r], azimuth [phi]
		 * in the image [table] gives (the primary, or the secondary: light leaving the other way round the hole).
		 */
		fun color(r: Double, phi: Double, table: FloatArray, secondary: Boolean): Int {
			val px = hx + r * cos(phi)
			val py = hy
			val pz = hz + r * sin(phi)
			// The vertex lens's frame: e1 hole to camera, e2 toward the point's side of the camera-hole line.
			val e1x = -hx / rc
			val e1y = -hy / rc
			val e1z = -hz / rc
			val fx = px - hx
			val fy = py - hy
			val fz = pz - hz
			val rp = sqrt(fx * fx + fy * fy + fz * fz)
			val cosPhi = ((fx * e1x + fy * e1y + fz * e1z) / rp).coerceIn(-1.0, 1.0)
			var sx = fx / rp - cosPhi * e1x
			var sy = fy / rp - cosPhi * e1y
			var sz = fz / rp - cosPhi * e1z
			val side = sqrt(sx * sx + sy * sy + sz * sz)
			if (side < 1e-6) return 0
			sx /= side; sy /= side; sz /= side
			val u0 = rs / rc
			val uP = rs / rp
			val angle = acos(cosPhi)
			val launch = if (secondary) {
				SchwarzschildImages.sampleSecondary(table, VertexLens.IMAGES_U0, VertexLens.IMAGES_UP, VertexLens.IMAGES_PHI, u0, uP, angle)
			} else {
				SchwarzschildImages.sample(table, VertexLens.IMAGES_U0, VertexLens.IMAGES_UP, VertexLens.IMAGES_PHI, u0, uP, angle)
			}
			if (launch.isNaN()) return 0
			if (secondary) { sx = -sx; sy = -sy; sz = -sz }
			// The photon's angular momentum about the disk's axis (black_hole_lens.fsh's diskSample): impact parameter
			// from the launch angle (static frame), its sense from the plane it moves in.
			val impact = rc * sin(launch) / sqrt(1.0 - u0)
			val crossY = e1z * sx - e1x * sz // (e1 x e2).y
			val lz = -impact * crossY * sense
			val omega = sqrt(0.5 * rs / (r * r * r))
			val ut = 1.0 / sqrt(max(1.0 - 1.5 * rs / r, 1e-4))
			val g = 1.0 / (sqrt(1.0 - u0) * ut * (1.0 - omega * lz))
			// diskColor: flux, temperature, blackbody shifted by g, intensity by g^4; tonemapped (1 - e^-x).
			val x = rIn / r
			val flux = max((1.0 - sqrt(x)) * x * x * x, 0.0) / 0.05667
			val temperature = (2500.0 + (11000.0 - 2500.0) * (min(ratio, 1.5) / 1.5)) * flux.pow(0.25)
			val bb = blackbody(temperature * g)
			val intensity = flux * g * g * g * g * (1.5 + 2.0 * ratio)
			val red = 1.0 - exp(-bb[0] * intensity)
			val green = 1.0 - exp(-bb[1] * intensity)
			val blue = 1.0 - exp(-bb[2] * intensity)
			val alpha = (flux * 3.0).coerceIn(0.0, 1.0) * smoothstep(rOut, 0.8 * rOut, r)
			// (Times the texture's 1.45, which it stores divided by: its swirl then scales the colour round 1.)
			fun byte(v: Double) = (v.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
			return (byte(alpha) shl 24) or (byte(1.45 * red) shl 16) or (byte(1.45 * green) shl 8) or byte(1.45 * blue)
		}
	}

	/** The colours last worked out, per image kind, and for what (camera, hole, disk): reused while those stand. */
	private val cachedKeys = arrayOfNulls<DoubleArray>(2)
	private val cachedColors = arrayOfNulls<Array<IntArray>>(2)

	private fun colorsFor(geometry: Geometry, table: FloatArray, secondary: Boolean): Array<IntArray> {
		val kind = if (secondary) 1 else 0
		val key = doubleArrayOf(geometry.hx, geometry.hy, geometry.hz, geometry.rs, geometry.rIn, geometry.ratio, geometry.sense, System.identityHashCode(table).toDouble())
		val cached = cachedColors[kind]
		if (cached != null && cachedKeys[kind].contentEquals(key)) return cached
		val colors = Array(RINGS + 1) { ring -> IntArray(AZIMUTHS + 1) { a -> geometry.color(geometry.radius(ring), 2.0 * PI * a / AZIMUTHS, table, secondary) } }
		cachedKeys[kind] = key
		cachedColors[kind] = colors
		return colors
	}

	/**
	 * The disk's light where it's brightest (its flux peaks at 49/36 of the inner edge), for the photon ring (HorizonMesh):
	 * tonemapped RGB as [Geometry.color] works it out, its frequency shifted by [g].
	 */
	fun innerGlow(hole: BlackHole, g: Double): DoubleArray {
		val ratio = (hole.accretionRate / RelativityConfig.eddingtonRate(hole.schwarzschildRadius)).coerceIn(0.05, 2.0)
		val x = 36.0 / 49.0
		val flux = max((1.0 - sqrt(x)) * x * x * x, 0.0) / 0.05667
		val temperature = (2500.0 + (11000.0 - 2500.0) * (min(ratio, 1.5) / 1.5)) * flux.pow(0.25)
		val bb = blackbody(temperature * g)
		val intensity = flux * g * g * g * g * (1.5 + 2.0 * ratio)
		return DoubleArray(3) { 1.0 - exp(-bb[it] * intensity) }
	}

	private fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
		val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
		return t * t * (3.0 - 2.0 * t)
	}

	/** black_hole_lens.fsh's blackbody (Tanner Helland's fit): approximate sRGB, brightest channel about 1. */
	private fun blackbody(kelvin: Double): DoubleArray {
		val t = kelvin.coerceIn(1000.0, 40000.0) / 100.0
		var r = 1.0
		var g = (0.39008158 * ln(t) - 0.63184144).coerceIn(0.0, 1.0)
		var b = 0.0
		if (t > 66.0) {
			r = (1.29293619 * (t - 60.0).pow(-0.1332048)).coerceIn(0.0, 1.0)
			g = (1.12989086 * (t - 60.0).pow(-0.0755148)).coerceIn(0.0, 1.0)
		}
		if (t >= 66.0) b = 1.0 else if (t > 19.0) b = (0.54320679 * ln(t - 10.0) - 1.19625409).coerceIn(0.0, 1.0)
		return doubleArrayOf(r, g, b)
	}

	/**
	 * The turbulence (black_hole_lens.fsh's turbulence: four octaves of value noise, sampled round a circle whose radius
	 * grows with the ring's), as a texture: u round the disk (tiling), v out across the rings. Its values, 0.55 .. 1.45
	 * there, are stored over 1.45 (a texture holds at most 1).
	 */
	private fun ensureTexture(minecraft: Minecraft) {
		if (textureReady) return
		val image = NativeImage(TEXTURE_WIDTH, TEXTURE_HEIGHT, false)
		for (y in 0 until TEXTURE_HEIGHT) {
			val ring = (y + 0.5) / TEXTURE_HEIGHT * (RINGS + 1) - 0.5
			val r = 4.0.pow(ring.coerceIn(0.0, RINGS.toDouble()) / RINGS) // radius over the inner edge
			val scale = 2.0 + 6.0 * ln(r + 1.0)
			for (x in 0 until TEXTURE_WIDTH) {
				val angle = 2.0 * PI * (x + 0.5) / TEXTURE_WIDTH
				val swirl = 0.55 + 0.9 * turbulence(cos(angle) * scale, sin(angle) * scale)
				val value = (swirl / 1.45 * 255.0).toInt().coerceIn(0, 255)
				image.setPixelRGBA(x, y, (0xFF shl 24) or (value shl 16) or (value shl 8) or value)
			}
		}
		minecraft.textureManager.register(TEXTURE, DynamicTexture(image))
		textureReady = true
	}

	private fun hash(x: Double, y: Double): Double {
		val v = sin(x * 127.1 + y * 311.7) * 43758.5453
		return v - floor(v)
	}

	private fun valueNoise(x: Double, y: Double): Double {
		val ix = floor(x)
		val iy = floor(y)
		val fx = x - ix
		val fy = y - iy
		val wx = fx * fx * (3.0 - 2.0 * fx)
		val wy = fy * fy * (3.0 - 2.0 * fy)
		val a = hash(ix, iy) + (hash(ix + 1.0, iy) - hash(ix, iy)) * wx
		val b = hash(ix, iy + 1.0) + (hash(ix + 1.0, iy + 1.0) - hash(ix, iy + 1.0)) * wx
		return a + (b - a) * wy
	}

	private fun turbulence(x0: Double, y0: Double): Double {
		var sum = 0.0
		var amplitude = 0.5
		var x = x0
		var y = y0
		repeat(4) {
			sum += amplitude * valueNoise(x, y)
			x *= 2.03
			y *= 2.03
			amplitude *= 0.5
		}
		return sum
	}
}

/**
 * The disk's render type: the beacon beam's (unlit, emissive: what shader packs treat as glowing), translucent, but
 * writing depth, so the post pass takes the disk for drawn geometry and leaves it as drawn (as sky, it bent it again).
 * (A subclass, for RenderStateShard's protected states.)
 */
private class DiskRenderType private constructor() : net.minecraft.client.renderer.RenderStateShard("create_relativity_disk", {}, {}) {
	companion object {
		val TYPE: RenderType = RenderType.create(
			"create_relativity_disk", com.mojang.blaze3d.vertex.DefaultVertexFormat.BLOCK, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
			1 shl 16, false, true,
			RenderType.CompositeState.builder()
				.setShaderState(RENDERTYPE_BEACON_BEAM_SHADER)
				.setTextureState(TextureStateShard(ResourceLocation.fromNamespaceAndPath(CreateRelativity.MOD_ID, "dynamic/disk_turbulence"), false, false))
				.setTransparencyState(TRANSLUCENT_TRANSPARENCY)
				.setCullState(NO_CULL)
				.setWriteMaskState(COLOR_DEPTH_WRITE)
				.createCompositeState(false),
		)
	}
}
