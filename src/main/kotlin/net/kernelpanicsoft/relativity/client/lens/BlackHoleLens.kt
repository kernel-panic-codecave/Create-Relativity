package net.kernelpanicsoft.relativity.client.lens

import foundry.veil.api.client.render.VeilLevelPerspectiveRenderer
import foundry.veil.api.client.render.VeilRenderSystem
import org.lwjgl.opengl.GL11
import foundry.veil.forge.event.ForgeVeilPostProcessingEvent
import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.physics.lens.SchwarzschildLens
import net.kernelpanicsoft.relativity.client.SpacetimeDebugRenderer
import net.kernelpanicsoft.relativity.spacetime.BlackHole
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.minecraft.client.Minecraft
import net.minecraft.world.level.Level
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.ViewportEvent
import net.neoforged.neoforge.common.NeoForge
import org.joml.Matrix4f
import org.joml.Vector4f
import java.util.concurrent.CompletableFuture

/**
 * Gravitational lensing of the rendered frame by every black hole, as a Veil post pipeline.
 *
 * Bending comes from [SchwarzschildLens]'s table, built once in the background at startup (it's normalised by
 * r_s, so one table serves every hole). Spin is ignored here; Kerr lensing is a ray marcher in M9.
 */
object BlackHoleLens {
	val PIPELINE = CreateRelativity.id("black_hole_lens")
	val LOOKUP_TEXTURE = CreateRelativity.id("lens_lookup")
	private val SHADER = CreateRelativity.id("black_hole_lens")

	/** Must match LUT_SIZE in black_hole_lens.fsh. */
	private const val LUT_WIDTH = 512
	private const val LUT_HEIGHT = 1024

	/**
	 * Holes bending light less than this (≈ r_s / distance, in radians: about a pixel) are skipped. With none left the
	 * lens, its capture and overscan all switch off.
	 */
	private const val MIN_BENDING = 1e-3

	/** Must match MAX_HOLES in black_hole_lens.fsh. */
	private const val MAX_HOLES = 8

	private var table: CompletableFuture<FloatArray>? = null
	private var textureReady = false
	private val holeUniforms = Array(MAX_HOLES) { Vector4f() }

	/**
	 * tan(½ vertical FOV) of what the player actually sees, while the level is being rendered wider than that
	 * (overscan) so lensed rays near the screen edges still find rendered pixels; 0 when not overscanning.
	 */
	private var displayTanHalfFov = 0.0

	fun init() {
		table = CompletableFuture.supplyAsync { SchwarzschildLens.buildTable(LUT_WIDTH, LUT_HEIGHT) }
		NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { onClientTick() }
		NeoForge.EVENT_BUS.addListener(ForgeVeilPostProcessingEvent.Pre::class.java, ::onPrePostProcessing)
		NeoForge.EVENT_BUS.addListener(ForgeVeilPostProcessingEvent.Post::class.java, ::onPostPostProcessing)
		NeoForge.EVENT_BUS.addListener(ViewportEvent.ComputeFov::class.java, ::onComputeFov)
	}

	/**
	 * Widens the level's field of view by the configured margin while the lens is active; the lens pass crops back
	 * to the real FOV. The hand (which doesn't use the configured FOV) and perspective captures are left alone.
	 */
	private fun onComputeFov(event: ViewportEvent.ComputeFov) {
		if (!event.usedConfiguredFov() || VeilLevelPerspectiveRenderer.isRenderingPerspective()) return
		val margin = RelativityConfig.Client.Render.overscan / 100.0
		if (margin <= 0.0 || !VeilRenderSystem.renderer().postProcessingManager.isActive(PIPELINE)) {
			displayTanHalfFov = 0.0
			return
		}
		val tanHalf = kotlin.math.tan(Math.toRadians(event.fov / 2.0))
		displayTanHalfFov = tanHalf
		event.fov = minOf(Math.toDegrees(2.0 * kotlin.math.atan(tanHalf * (1.0 + margin))), 170.0)
	}

	private fun onClientTick() {
		val minecraft = Minecraft.getInstance()
		if (!textureReady) {
			val data = table?.getNow(null) ?: return
			val texture = LensLookupTexture(LUT_WIDTH, LUT_HEIGHT, data)
			minecraft.textureManager.register(LOOKUP_TEXTURE, texture)
			texture.upload()
			textureReady = true
			table = null
			CreateRelativity.LOGGER.info("Black hole lens table ready ({}x{})", LUT_WIDTH, LUT_HEIGHT)
		}

		val level = minecraft.level
		val wanted = level != null && RelativityConfig.Client.Render.lensing && visibleHoles(level).isNotEmpty()
		val post = VeilRenderSystem.renderer().postProcessingManager
		if (wanted && !post.isActive(PIPELINE)) post.add(PIPELINE)
		else if (!wanted && post.isActive(PIPELINE)) post.remove(PIPELINE)
	}

	/** True while the lens pipeline is running this frame. */
	val active: Boolean
		get() = VeilRenderSystem.renderer().postProcessingManager.isActive(PIPELINE)

	/** Right after our pass: undistorted debug gizmos on top, with the screen's real projection. */
	private fun onPostPostProcessing(event: ForgeVeilPostProcessingEvent.Post) {
		if (event.name != PIPELINE || VeilLevelPerspectiveRenderer.isRenderingPerspective()) return
		val window = Minecraft.getInstance().window
		val aspect = window.width.toFloat() / window.height
		val projection = if (displayTanHalfFov > 0.0) {
			Matrix4f().setPerspective((2.0 * kotlin.math.atan(displayTanHalfFov)).toFloat(), aspect, 0.05f, 4096f)
		} else {
			Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix())
		}
		SpacetimeDebugRenderer.renderAfterLens(projection)
	}

	/**
	 * The nearest hole's accretion disk: brightness from its Eddington ratio (none if it isn't feeding), inner edge
	 * at its ISCO, outer edge four times that, which way it turns, and time for the disk's rotating turbulence.
	 */
	private fun uploadDisk(pipeline: foundry.veil.api.client.render.post.PostPipeline, level: Level, hole: BlackHole?, minecraft: Minecraft) {
		if (hole == null || hole.accretionRate <= 0.0) {
			pipeline.getUniformSafe("Disk").setVector(0f, 0f, 0f, 0f)
			return
		}
		val ratio = hole.accretionRate / RelativityConfig.eddingtonRate(hole.schwarzschildRadius)
		val inner = hole.equatorialExtent(hole.isco(prograde = true))
		pipeline.getUniformSafe("Disk").setVector(
			ratio.coerceIn(0.05, 2.0).toFloat(), inner.toFloat(), (4.0 * inner).toFloat(), if (hole.spin < 0.0) -1f else 1f,
		)
		val c = RelativityConfig.speedOfLight(level.dimension())
		val seconds = (level.gameTime % 72000L + minecraft.timer.getGameTimeDeltaPartialTick(false)) / 20.0
		pipeline.getUniformSafe("DiskTime").setFloat((seconds * c).toFloat())
	}

	/** Holes close enough to bend light noticeably as seen from the camera. */
	fun visibleHoles(level: Level): List<BlackHole> {
		val camera = Minecraft.getInstance().gameRenderer.mainCamera.position
		return SpacetimeField.blackHoles(level).filter { it.schwarzschildRadius > MIN_BENDING * it.center.distanceTo(camera) }
	}

	/**
	 * Uploads the nearest holes, relative to the camera, right before our pass runs. The pass runs at the end
	 * of level rendering ("after_level" in the pipeline), before the hand is drawn and while depth is intact.
	 */
	private fun onPrePostProcessing(event: ForgeVeilPostProcessingEvent.Pre) {
		if (event.name != PIPELINE) return
		if (VeilLevelPerspectiveRenderer.isRenderingPerspective()) {
			// Veil runs stage passes inside perspective renders too; the environment capture must stay unlensed.
			event.pipeline.getUniformSafe("HoleCount").setInt(0)
			event.pipeline.getUniformSafe("DisplayTanHalfFov").setFloat(0f)
			return
		}
		val minecraft = Minecraft.getInstance()
		val level = minecraft.level ?: return
		val camera = minecraft.gameRenderer.mainCamera.position
		val holes = visibleHoles(level).sortedBy { it.center.distanceToSqr(camera) }.take(MAX_HOLES)
		for ((i, hole) in holes.withIndex()) {
			holeUniforms[i].set(
				(hole.x - camera.x).toFloat(), (hole.y - camera.y).toFloat(), (hole.z - camera.z).toFloat(),
				hole.schwarzschildRadius.toFloat(),
			)
		}
		val pipeline = event.pipeline
		pipeline.getUniformSafe("DisplayTanHalfFov").setFloat(displayTanHalfFov.toFloat())
		uploadDisk(pipeline, level, holes.firstOrNull(), minecraft)
		pipeline.getUniformSafe("HoleCount").setInt(holes.size)
		pipeline.getUniformSafe("Holes").setVectors(*holeUniforms)
		val environmentReady = LensEnvironment.ready
		pipeline.getUniformSafe("EnvironmentReady").setInt(if (environmentReady) 1 else 0)
		if (environmentReady) {
			VeilRenderSystem.renderer().shaderManager.getShader(SHADER)?.let { shader ->
				shader.setTexture("Environment", GL11.GL_TEXTURE_2D, LensEnvironment.atlasTexture)
				shader.setTexture("EnvironmentDepth", GL11.GL_TEXTURE_2D, LensEnvironment.depthAtlasTexture)
			}
			val center = LensEnvironment.captureCenter
			pipeline.getUniformSafe("CaptureCenter").setVector(
				(center.x - camera.x).toFloat(), (center.y - camera.y).toFloat(), (center.z - camera.z).toFloat(),
			)
		}
	}
}
