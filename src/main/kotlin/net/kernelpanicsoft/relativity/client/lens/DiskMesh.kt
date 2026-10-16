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

	/**
	 * Volumetric: the disk drawn as this many thin translucent layers through its thickness (a height growing with
	 * radius: [ASPECT] of it, more as it's fed harder), each taking a share of its opacity by a gaussian across the
	 * thickness (so face on it's as bright as one sheet, edge on thick and glowing) and its own phase of the turbulence,
	 * drifting against the others as it turns ([DRIFT]): the gas churns, not only rotates.
	 */
	@JvmField
	var layers = 7
	private const val ASPECT = 0.05
	private const val DRIFT = 0.08
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
		/** The disk's half-thickness over radius. */
		val aspect = ASPECT * (0.6 + 0.6 * min(ratio, 1.5))
		val rs = hole.schwarzschildRadius
		// The hole relative to the camera, and its unit vector toward the camera (e1, as the vertex lens has it).
		val hx = hole.x - camX
		val hy = hole.y - camY
		val hz = hole.z - camZ
		val rc = sqrt(hx * hx + hy * hy + hz * hz)

		fun radius(ring: Int) = rIn * (rOut / rIn).pow(ring / RINGS.toDouble())

		fun emit(consumer: com.mojang.blaze3d.vertex.VertexConsumer, table: FloatArray, secondary: Boolean) {
			if (rc <= rs) return
			val count = layers.coerceAtLeast(1)
			// Back to front: from the side away from the camera.
			val order = (0 until count).let { if (hy < 0.0) it.toList() else it.reversed() }
			// (The gaussian's weights, summing to 1: each layer's opacity is 1 - (1 - a)^w.)
			val offsets = DoubleArray(count) { if (count == 1) 0.0 else -1.0 + 2.0 * it / (count - 1) }
			val weights = DoubleArray(count) { exp(-offsets[it] * offsets[it] / 0.35) }.let { w -> val total = w.sum(); DoubleArray(count) { w[it] / total } }
			for (layer in order) emitLayer(consumer, table, secondary, offsets[layer], weights[layer], layer)
		}

		// Per ring and per azimuth, worked out once (not per quad corner): radius, orbital angular speed, the ring's
		// texture row; each azimuth's cosine and sine.
		private val radii = DoubleArray(RINGS + 1) { radius(it) }
		private val omegas = DoubleArray(RINGS + 1) { sense * sqrt(0.5 * rs / (radii[it] * radii[it] * radii[it])) }
		private val rows = FloatArray(RINGS + 1) { (it + 0.5f) / (RINGS + 1) }
		private val cosines = DoubleArray(AZIMUTHS + 1) { cos(2.0 * PI * it / AZIMUTHS) }
		private val sines = DoubleArray(AZIMUTHS + 1) { sin(2.0 * PI * it / AZIMUTHS) }

		private fun emitLayer(consumer: com.mojang.blaze3d.vertex.VertexConsumer, table: FloatArray, secondary: Boolean, offset: Double, weight: Double, layer: Int) {
			val pose = PoseStack().last()
			val colors = colorsFor(this, table, secondary)
			// The layer's opacity per vertex alpha (0-255), and its phase in the turbulence.
			val alphaOf = IntArray(256) { a -> (255.0 * (1.0 - Math.pow(1.0 - min(a / 255.0, 0.999), weight))).toInt().coerceIn(0, 255) }
			val phase = 0.137 * layer
			val turn = time * (1.0 + DRIFT * offset)
			// (The faint layers off the midplane at half the resolution each way: a quarter of the vertices.)
			val step = if (offset == 0.0) 1 else 2
			fun put(k: Int, j: Int) {
				val r = radii[k]
				val argb = colors[k][j]
				consumer.addVertex(pose, (hx + r * cosines[j]).toFloat(), (hy + offset * aspect * r).toFloat(), (hz + r * sines[j]).toFloat())
					.setColor((argb shr 16) and 255, (argb shr 8) and 255, argb and 255, alphaOf[(argb ushr 24) and 255])
					.setUv(((2.0 * PI * j / AZIMUTHS - omegas[k] * turn) / (2.0 * PI) + phase).toFloat(), rows[k])
					.setLight(0xF000F0)
					.setNormal(0f, 1f, 0f)
			}
			// (One winding: the render type doesn't cull, so it's seen from above and below, and mirrored.)
			for (ring in 0 until RINGS step step) for (a in 0 until AZIMUTHS step step) {
				put(ring, a)
				put(ring, a + step)
				put(ring + step, a + step)
				put(ring + step, a)
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
			val x = rIn / r
			val flux = max((1.0 - sqrt(x)) * x * x * x, 0.0) / 0.05667
			val (red, green, blue) = shade(flux, g, ratio)
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
		val key = doubleArrayOf(geometry.hx, geometry.hy, geometry.hz, geometry.rs, geometry.rIn, geometry.ratio, geometry.sense, System.identityHashCode(table).toDouble(),
			RelativityConfig.Client.Render.diskScheme.ordinal.toDouble(), RelativityConfig.Client.Render.diskGradient.hashCode().toDouble())
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
		return shade(flux, g, ratio)
	}

	/**
	 * The disk's colour (tonemapped RGB, 1 - e^-x) where its flux is [flux] (1 at the brightest ring), its light shifted
	 * by [g] (Doppler and gravitational), fed at [ratio] of Eddington: by the config's scheme (RelativityConfig.DiskScheme).
	 */
	fun shade(flux: Double, g: Double, ratio: Double): DoubleArray {
		// Heat across the disk, 0 .. 1 (the temperature's profile: flux to the quarter).
		val heat = flux.coerceAtLeast(0.0).pow(0.25)
		val rgb: DoubleArray
		val intensity: Double
		when (RelativityConfig.Client.Render.diskScheme) {
			RelativityConfig.DiskScheme.REALISTIC -> {
				// Blackbody at the temperature, shifted by g; brightness by g^4 (beaming).
				val temperature = (2500.0 + (11000.0 - 2500.0) * (min(ratio, 1.5) / 1.5)) * heat
				rgb = blackbody(temperature * g)
				intensity = flux * g * g * g * g * (1.5 + 2.0 * ratio)
			}
			RelativityConfig.DiskScheme.MOVIE -> {
				// Warm by heat alone; a little brighter on the approaching side (g, not g^4).
				rgb = gradient(MOVIE_GRADIENT, 1.0 - heat)
				intensity = MOVIE_BRIGHTNESS * flux * g * (1.5 + 2.0 * ratio)
			}
			RelativityConfig.DiskScheme.CUSTOM -> {
				// The player's gradient, hot inner edge first, the shift moving along it; beamed as the realistic one.
				rgb = gradient(schemeGradient(), 1.0 - (heat * g).coerceIn(0.0, 1.0))
				intensity = flux * g * g * g * g * (1.5 + 2.0 * ratio)
			}
		}
		return DoubleArray(3) { 1.0 - exp(-rgb[it] * intensity) }
	}

	/** The film look's brightness (lower than the realistic one's: its colours are kept, not burned to white). */
	private const val MOVIE_BRIGHTNESS = 0.55

	/** The current scheme's colours, hottest first (RGB 0-1): the post pass gets them too (BlackHoleLens). */
	fun schemeGradient(): List<DoubleArray> = when (RelativityConfig.Client.Render.diskScheme) {
		RelativityConfig.DiskScheme.CUSTOM -> RelativityConfig.Client.Render.diskGradient.map { doubleArrayOf(it.red / 255.0, it.green / 255.0, it.blue / 255.0) }.ifEmpty { MOVIE_GRADIENT }
		else -> MOVIE_GRADIENT
	}

	/** The film look's colours, hottest first: white-hot, gold, orange, ember. */
	private val MOVIE_GRADIENT = listOf(0xFFF3DC, 0xFFC56A, 0xFF7A1C, 0x6A1E04).map { c ->
		doubleArrayOf(((c shr 16) and 255) / 255.0, ((c shr 8) and 255) / 255.0, (c and 255) / 255.0)
	}

	/** [stops] (evenly spaced) at [t] in 0 .. 1, linearly between them. */
	private fun gradient(stops: List<DoubleArray>, t: Double): DoubleArray {
		if (stops.size == 1) return stops[0]
		val f = t.coerceIn(0.0, 1.0) * (stops.size - 1)
		val i = min(floor(f).toInt(), stops.size - 2)
		val w = f - i
		return DoubleArray(3) { stops[i][it] + w * (stops[i + 1][it] - stops[i][it]) }
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
