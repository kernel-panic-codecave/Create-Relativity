package net.kernelpanicsoft.relativity.client.lens

import foundry.veil.api.client.render.VeilLevelPerspectiveRenderer
import foundry.veil.api.client.render.VeilRenderSystem
import foundry.veil.api.client.render.shader.uniform.ShaderUniformAccess
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL33
import foundry.veil.forge.event.ForgeVeilPostProcessingEvent
import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.physics.lens.SchwarzschildLens
import net.kernelpanicsoft.relativity.client.SpacetimeDebugRenderer
import net.kernelpanicsoft.relativity.spacetime.BlackHole
import net.kernelpanicsoft.relativity.spacetime.FieldSource
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.minecraft.client.Minecraft
import net.minecraft.world.level.Level
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.NeoForge
import org.joml.Matrix4f
import org.joml.Vector4f
import java.util.concurrent.CompletableFuture

/**
 * The post pass of the lens, as a Veil post pipeline (black_hole_lens.json), after the vertex lens ([VertexLens]) has
 * drawn the world where its light appears: in every pixel it left empty, the light followed back past every source by
 * the field lens, black where a hole captures it (the shadow), else looked up in [SceneCapture] or the sky; then the
 * nearest hole's accretion disk. [SchwarzschildLens]' escape table (built once at startup, normalised by r_s) is the
 * field lens's for holes. Every hole is lensed as a non-spinning one.
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
	 * lens switches off.
	 */
	private const val MIN_BENDING = 1e-3

	/** Must match MAX_HOLES in lens_scene.glsl. */
	private const val MAX_HOLES = 8

	private var table: CompletableFuture<FloatArray>? = null
	private var textureReady = false
	private val holeUniforms = Array(MAX_HOLES) { Vector4f() }

	/** Passes the frame through untouched (to see what vertex lensing drew); only GameTests change it. */
	@JvmField
	var rawFrame = false

	/** Debug (GameTests): empty pixels coloured by how the post pass's fill walk ended (black_hole_lens.fsh, LensMode 5). */
	@JvmField
	var fillDebug = false

	/** Leaves parts of the pass out for profiling (lens_scene.glsl's ProfileFlags); only GameTests change it. */
	@JvmField
	var profileFlags = 0

	/** GPU time of the whole lens pipeline in the latest finished frame (ms), measured while [timeGpu] is set; else NaN. */
	@Volatile
	var gpuMillis = Double.NaN
		private set

	@JvmField
	var timeGpu = false
	private val timerQueries = IntArray(2)
	private var timerFrame = 0
	private var timerRunning = false

	/** The level's terrain fog this frame, as set for the translucent blocks (the lens fogs traced surfaces the same). */
	private var fogStart = 0f
	private var fogEnd = 1e9f
	private var fogShape = 1
	private val fogColor = floatArrayOf(0f, 0f, 0f, 0f)

	private fun onRenderStage(event: net.neoforged.neoforge.client.event.RenderLevelStageEvent) {
		if (event.stage != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return
		if (VeilLevelPerspectiveRenderer.isRenderingPerspective()) return
		fogStart = com.mojang.blaze3d.systems.RenderSystem.getShaderFogStart()
		fogEnd = com.mojang.blaze3d.systems.RenderSystem.getShaderFogEnd()
		fogShape = com.mojang.blaze3d.systems.RenderSystem.getShaderFogShape().index
		com.mojang.blaze3d.systems.RenderSystem.getShaderFogColor().copyInto(fogColor)
	}

	fun init() {
		NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.RenderLevelStageEvent::class.java, ::onRenderStage)
		table = CompletableFuture.supplyAsync { SchwarzschildLens.buildTable(LUT_WIDTH, LUT_HEIGHT) }
		NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { onClientTick() }
		NeoForge.EVENT_BUS.addListener(ForgeVeilPostProcessingEvent.Pre::class.java, ::onPrePostProcessing)
		NeoForge.EVENT_BUS.addListener(ForgeVeilPostProcessingEvent.Post::class.java, ::onPostPostProcessing)
	}

	private fun onClientTick() {
		QuadSubdivision.tick()
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
		val wanted = level != null && RelativityConfig.Client.Render.lensing && (visibleHoles(level).isNotEmpty() || visibleSources(level).isNotEmpty())
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
		if (timerRunning) {
			GL15.glEndQuery(GL33.GL_TIME_ELAPSED)
			timerRunning = false
		}
		SpacetimeDebugRenderer.renderAfterLens(Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix()))
	}

	/**
	 * The nearest hole's accretion disk: brightness from its Eddington ratio (none if it isn't feeding), inner edge
	 * at its ISCO, outer edge four times that, which way it turns, and time for the disk's rotating turbulence.
	 */
	private fun uploadDisk(uniform: (String, ShaderUniformAccess.() -> Unit) -> Unit, level: Level, hole: BlackHole?, minecraft: Minecraft) {
		uniform("DiskScheme") { setInt(RelativityConfig.Client.Render.diskScheme.ordinal) }
		val stops = schemeGradient().take(8)
		uniform("DiskGradient") { setVectors(*Array(8) { k -> stops.getOrNull(k)?.let { Vector4f(it[0].toFloat(), it[1].toFloat(), it[2].toFloat(), 1f) } ?: Vector4f(0f) }) }
		if (hole == null || hole.accretionRate <= 0.0) {
			uniform("Disk") { setVector(0f, 0f, 0f, 0f) }
			return
		}
		val ratio = hole.accretionRate / RelativityConfig.eddingtonRate(hole.schwarzschildRadius)
		// (Lensed as non-spinning: the ISCO at 3 r_s.)
		val inner = 3.0 * hole.schwarzschildRadius
		uniform("Disk") {
			setVector(ratio.coerceIn(0.05, 2.0).toFloat(), inner.toFloat(), (4.0 * inner).toFloat(), if (hole.spin < 0.0) -1f else 1f)
		}
		val c = RelativityConfig.speedOfLight(level.dimension())
		val seconds = (level.gameTime % 72000L + minecraft.timer.getGameTimeDeltaPartialTick(false)) / 20.0
		uniform("DiskTime") { setFloat((seconds * c).toFloat()) }
	}

	/** The disk's colour scheme's colours, hottest first (RGB 0-1): the custom gradient sampled evenly, else the film look's. */
	private fun schemeGradient(): List<DoubleArray> = when (RelativityConfig.Client.Render.diskScheme) {
		// (Its stops can sit anywhere.)
		RelativityConfig.DiskScheme.CUSTOM -> RelativityConfig.Client.Render.diskGradient.let { g -> List(8) { k -> g.sample(k / 7f).let { c -> doubleArrayOf(c.red / 255.0, c.green / 255.0, c.blue / 255.0) } } }
		else -> MOVIE_GRADIENT
	}

	/** The film look's colours, hottest first: white-hot, gold, orange, ember. */
	private val MOVIE_GRADIENT = listOf(0xFFF3DC, 0xFFC56A, 0xFF7A1C, 0x6A1E04).map { c ->
		doubleArrayOf(((c shr 16) and 255) / 255.0, ((c shr 8) and 255) / 255.0, (c and 255) / 255.0)
	}

	/** A 1 x 1 x 1 3D texture for 3D samplers with nothing to read yet (one left unbound invalidates the draw). */
	private val placeholder3d: Int by lazy {
		val id = GL11.glGenTextures()
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, id)
		GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA8, 1, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as java.nio.ByteBuffer?)
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
		id
	}

	/** A 1 x 1 x 1 2D array texture for the light table's sampler while there's none. */
	private val placeholderArray: Int by lazy {
		val id = GL11.glGenTextures()
		GL11.glBindTexture(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, id)
		GL12.glTexImage3D(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA8, 1, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as java.nio.ByteBuffer?)
		GL11.glBindTexture(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, 0)
		id
	}

	/** A 1 x 1 x 1 unsigned-integer 2D array texture for the light table's seeds while there are none. */
	private val placeholderSeeds: Int by lazy {
		val id = GL11.glGenTextures()
		GL11.glBindTexture(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, id)
		GL12.glTexImage3D(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, 0, org.lwjgl.opengl.GL30.GL_R32UI, 1, 1, 1, 0, org.lwjgl.opengl.GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, null as java.nio.IntBuffer?)
		GL11.glTexParameteri(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
		GL11.glTexParameteri(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
		GL11.glBindTexture(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, 0)
		id
	}

	/** Starts this frame's GPU timer query, first reading the one from two frames ago (long finished by now). */
	private fun beginTimer() {
		if (timerRunning) return
		if (timerQueries[0] == 0) GL15.glGenQueries(timerQueries)
		val query = timerQueries[timerFrame % 2]
		if (timerFrame >= 2) gpuMillis = GL33.glGetQueryObjecti64(query, GL15.GL_QUERY_RESULT) / 1e6
		GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, query)
		timerRunning = true
		timerFrame += 1
	}

	/** The escape table's texture (GL_TEXTURE_2D: SchwarzschildLens.buildTable), or 0 until it's built. */
	fun escapeTexture(): Int = if (textureReady) Minecraft.getInstance().textureManager.getTexture(LOOKUP_TEXTURE).id else 0

	/** Holes close enough to bend light noticeably as seen from the camera. */
	fun visibleHoles(level: Level): List<BlackHole> {
		val camera = Minecraft.getInstance().gameRenderer.mainCamera.position
		return SpacetimeField.blackHoles(level).filter { it.schwarzschildRadius > MIN_BENDING * it.center.distanceTo(camera) }
	}

	/**
	 * Sources other than holes that bend light noticeably as seen from the camera (as holes: r_s 2|m| for a mass), and
	 * that the field lens has modules for (a round bubble it hasn't: left out).
	 */
	fun visibleSources(level: Level): List<FieldSource> {
		val camera = Minecraft.getInstance().gameRenderer.mainCamera.position
		val at = org.joml.Vector3d(camera.x, camera.y, camera.z)
		return SpacetimeField.fieldSources(level).filter { source ->
			when (source) {
				is FieldSource.Mass -> 2.0 * kotlin.math.abs(source.mass) > MIN_BENDING * source.center.distance(at)
				is FieldSource.Bubble -> false
				is BlackHole -> source.schwarzschildRadius > MIN_BENDING * source.center.distanceTo(camera)
				// (Tubes: the field lens's tube module, in view out to the render distance.)
				is FieldSource.Tube -> source.distance(at) < Minecraft.getInstance().gameRenderer.renderDistance + source.radius
				is FieldSource.Krasnikov -> source.distance(at) < Minecraft.getInstance().gameRenderer.renderDistance + source.radius
				is FieldSource.Ellipsoid -> source.center.distance(at) < Minecraft.getInstance().gameRenderer.renderDistance + source.axes.maxOf { it.length() }
			}
		}
	}

	/**
	 * Uploads the nearest holes, relative to the camera, right before our pass runs. The pass runs at the end
	 * of level rendering ("after_level" in the pipeline), before the hand is drawn and while depth is intact.
	 */
	private fun onPrePostProcessing(event: ForgeVeilPostProcessingEvent.Pre) {
		if (event.name != PIPELINE) return
		if (timeGpu && !VeilLevelPerspectiveRenderer.isRenderingPerspective()) beginTimer()
		val pipeline = event.pipeline
		fun uniform(name: String, set: ShaderUniformAccess.() -> Unit) = pipeline.getUniformSafe(name).set()
		if (VeilLevelPerspectiveRenderer.isRenderingPerspective()) {
			// Veil runs stage passes inside perspective renders (other level views) too; they stay unlensed.
			uniform("HoleCount") { setInt(0) }
			uniform("LensMode") { setInt(4) }
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
		uploadDisk(::uniform, level, holes.firstOrNull(), minecraft)
		uniform("HoleCount") { setInt(holes.size) }
		uniform("Holes") { setVectors(*holeUniforms) }
		uniform("SceneCaptureReady") { setInt(SceneCapture.ready) }
		uniform("SceneCaptureFaces") { setVectors(*SceneCapture.columns()) }
		uniform("SceneCaptureOrigins") { setVectors(*SceneCapture.origins(camera)) }
		uniform("SceneCaptureCells") { setVectors(*SceneCapture.cells()) }
		uniform("SceneCaptureRange") { setVector(SceneCapture.NEAR, SceneCapture.far) }
		uniform("LensMode") { setInt(if (rawFrame) 4 else if (fillDebug) 5 else 0) }
		val sky = level.getSkyColor(camera, minecraft.timer.getGameTimeDeltaPartialTick(false))
		pipeline.getUniformSafe("SkyColor").setVector(sky.x.toFloat(), sky.y.toFloat(), sky.z.toFloat())
		uniform("LensFogColor") { setVector(fogColor[0], fogColor[1], fogColor[2], fogColor[3]) }
		uniform("LensFogStart") { setFloat(fogStart) }
		uniform("LensFogEnd") { setFloat(fogEnd) }
		uniform("LensFogShape") { setInt(fogShape) }
		uniform("ProfileFlags") { setInt(profileFlags) }
		// The last pass's touch-up: a light sharpen.
		uniform("AaSharpen") { setFloat(0.25f) }

		// Every sampler gets a real texture, whatever the mode: Veil binds missing ones to a placeholder on unit 0 and
		// renumbers the rest, and a mismatch there (a 3D sampler on a 2D texture) makes the whole draw invalid.
		val lens = VeilRenderSystem.renderer().shaderManager.getShader(SHADER)
		// The lens pass runs the vertex lens's maths (the field lens): its block, and its tables through Veil (which
		// numbers this program's samplers itself).
		lens?.let {
			VertexLens.linkProgram(it.program)
			it.setTexture("RelativityImages", GL12.GL_TEXTURE_3D, VertexLens.texture.takeIf { id -> id != 0 } ?: placeholder3d)
			// (The field lens's holes: the escape table, here as the vertex shaders read it.)
			escapeTexture().takeIf { id -> id != 0 }?.let { id -> it.setTexture("RelativityEscape", GL11.GL_TEXTURE_2D, id) }
			it.setTexture("RelativitySecondImages", GL12.GL_TEXTURE_3D, VertexLens.secondTexture.takeIf { id -> id != 0 } ?: placeholder3d)
			it.setTexture("RelativityLightTable", org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, LightTable.texture.takeIf { id -> id != 0 } ?: placeholderArray)
			it.setTexture("RelativityLightSeeds", org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, LightTable.seeds.takeIf { id -> id != 0 } ?: placeholderSeeds)
		}
	}
}
