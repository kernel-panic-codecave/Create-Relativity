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
 * The post pass of the black-hole lens, as a Veil post pipeline (black_hole_lens.json), after the vertex lens
 * ([VertexLens]) has drawn the world where its light appears: the shadow over what lies beyond the hole, the
 * accretion disk, and in the sky (where nothing was drawn) each view ray's bent path (lens_scene.glsl; the nearest
 * spinning hole through Kerr) to the geometry round the hole ([LensVoxels]) or the sky: the Einstein ring and the
 * images round it. The paths come from quarter-resolution tables (kerr_table.fsh, scene_table.fsh), which the lens
 * pass interpolates, marching per pixel only where neighbouring entries disagree. [SchwarzschildLens]' table (built
 * once at startup, normalised by r_s) gives the shadow's edge.
 */
object BlackHoleLens {
	val PIPELINE = CreateRelativity.id("black_hole_lens")
	val LOOKUP_TEXTURE = CreateRelativity.id("lens_lookup")
	private val SHADER = CreateRelativity.id("black_hole_lens")
	private val TABLE_SHADERS = listOf(CreateRelativity.id("kerr_table"), CreateRelativity.id("scene_table"), CreateRelativity.id("scene_refine"), CreateRelativity.id("scene_refine_march"))

	/** Must match LUT_SIZE in black_hole_lens.fsh. */
	private const val LUT_WIDTH = 512
	private const val LUT_HEIGHT = 1024

	/**
	 * Holes bending light less than this (≈ r_s / distance, in radians: about a pixel) are skipped. With none left the
	 * lens switches off.
	 */
	private const val MIN_BENDING = 1e-3

	/** Must match MAX_HOLES in black_hole_lens.fsh. */
	private const val MAX_HOLES = 8

	private var table: CompletableFuture<FloatArray>? = null
	private var textureReady = false
	private val holeUniforms = Array(MAX_HOLES) { Vector4f() }
	private val spinUniforms = Array(MAX_HOLES) { Vector4f() }

	/** Must match MAX_SOURCES in kerr.glsl. */
	const val MAX_SOURCES = 8
	private val sourceUniforms = Array(3 * MAX_SOURCES) { Vector4f() }

	/** Paints each pixel by how its bent path ended, every pixel marched (black_hole_lens.fsh, LensMode 1); only GameTests change it. */
	@JvmField
	var marchDebug = false

	/** Paints each pixel by its full-resolution march's step count (LensMode 2, white at 64); only GameTests change it. */
	@JvmField
	var marchStepsDebug = false

	/** Marches every pixel instead of using the scene table (the reference); only GameTests change it. */
	@JvmField
	var marchEveryPixel = false

	/** Passes the frame through untouched (to see what vertex lensing drew); only GameTests change it. */
	@JvmField
	var rawFrame = false

	/** Paints pixels re-marched at full resolution (LensMode 6): red scene, blue Kerr; only GameTests change it. */
	@JvmField
	var remarchDebug = false

	/** Disables parts of the march for profiling (lens_scene.glsl's ProfileFlags); only GameTests change it. */
	@JvmField
	var profileFlags = 0

	/** With [rawFrame], also skips the tables (LensMode 5): the pipeline's fixed cost, for profiling. */
	@JvmField
	var skipTables = false

	/** What the sky's bent paths can meet (lens_scene.glsl's UnseenSource). */
	enum class Unseen {
		/** Nothing: those paths show the sky. */
		NONE,
		/** The block grid round the hole (LensVoxels). */
		VOXELS,
	}

	/** Voxels by default; NONE is for comparison (GameTests). */
	@JvmField
	var unseen = Unseen.VOXELS

	/** How the nearest spinning hole's rays are found; only GameTests change it. */
	@JvmField
	var kerrMode = KerrMode.TABLE

	enum class KerrMode {
		/** Every pixel marched at full resolution (the reference). */
		FULL_MARCH,
		TABLE,
		/** [TABLE], with the pixels marched at full resolution painted red. */
		TABLE_DEBUG,
		/** The table's error against a full march, per pixel, as colour (see black_hole_lens.fsh). */
		TABLE_ERROR,
	}

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
		if (hole == null || hole.accretionRate <= 0.0) {
			uniform("Disk") { setVector(0f, 0f, 0f, 0f) }
			return
		}
		val ratio = hole.accretionRate / RelativityConfig.eddingtonRate(hole.schwarzschildRadius)
		val inner = hole.equatorialExtent(hole.isco(prograde = true))
		uniform("Disk") {
			setVector(ratio.coerceIn(0.05, 2.0).toFloat(), inner.toFloat(), (4.0 * inner).toFloat(), if (hole.spin < 0.0) -1f else 1f)
		}
		val c = RelativityConfig.speedOfLight(level.dimension())
		val seconds = (level.gameTime % 72000L + minecraft.timer.getGameTimeDeltaPartialTick(false)) / 20.0
		uniform("DiskTime") { setFloat((seconds * c).toFloat()) }
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

	/** Holes close enough to bend light noticeably as seen from the camera. */
	fun visibleHoles(level: Level): List<BlackHole> {
		val camera = Minecraft.getInstance().gameRenderer.mainCamera.position
		return SpacetimeField.blackHoles(level).filter { it.schwarzschildRadius > MIN_BENDING * it.center.distanceTo(camera) }
	}

	/** Sources other than holes that bend light noticeably as seen from the camera (as holes: r_s 2|m| for a mass). */
	fun visibleSources(level: Level): List<FieldSource> {
		val camera = Minecraft.getInstance().gameRenderer.mainCamera.position
		val at = org.joml.Vector3d(camera.x, camera.y, camera.z)
		return SpacetimeField.fieldSources(level).filter { source ->
			when (source) {
				is FieldSource.Mass -> 2.0 * kotlin.math.abs(source.mass) > MIN_BENDING * source.center.distance(at)
				is FieldSource.Bubble -> source.center.distance(at) < 4.0 * Minecraft.getInstance().gameRenderer.renderDistance + source.radius
			}
		}
	}

	/**
	 * Every source as kerr.glsl's Sources (camera-relative, game axes): [holes] first, then [others]. Whether light
	 * then needs the general tracer (a spinning hole, or anything but holes).
	 */
	fun packSources(holes: List<BlackHole>, others: List<FieldSource>, camera: net.minecraft.world.phys.Vec3, into: Array<Vector4f>): Pair<Int, Boolean> {
		var count = 0
		var general = false
		fun put(center: org.joml.Vector3dc, type: Float, a: Vector4f.() -> Unit, b: Vector4f.() -> Unit = { set(0f) }) {
			if (count >= MAX_SOURCES) return
			into[3 * count].set((center.x() - camera.x).toFloat(), (center.y() - camera.y).toFloat(), (center.z() - camera.z).toFloat(), type)
			into[3 * count + 1].a()
			into[3 * count + 2].b()
			count += 1
		}
		for (hole in holes) {
			val m = hole.mass
			put(org.joml.Vector3d(hole.x, hole.y, hole.z), 1f, { set(m.toFloat(), (hole.spin * m).toFloat(), 0f, 0f) })
			if (kotlin.math.abs(hole.spin) >= 0.05) general = true // kerr.glsl MIN_KERR_SPIN
		}
		for (source in others) {
			general = true
			when (source) {
				is FieldSource.Mass -> put(source.center, 2f, { set(source.mass.toFloat(), source.softening.toFloat(), 0f, 0f) })
				is FieldSource.Bubble -> {
					val n = org.joml.Vector3d(source.direction).normalize()
					put(source.center, 3f, { set(source.radius.toFloat(), (8.0 / source.radius).toFloat(), source.speed.toFloat(), 0f) }, { set(n.x.toFloat(), n.y.toFloat(), n.z.toFloat(), 0f) })
				}
			}
		}
		return count to general
	}

	/**
	 * Uploads the nearest holes, relative to the camera, right before our pass runs. The pass runs at the end
	 * of level rendering ("after_level" in the pipeline), before the hand is drawn and while depth is intact.
	 */
	private fun onPrePostProcessing(event: ForgeVeilPostProcessingEvent.Pre) {
		if (event.name != PIPELINE) return
		if (timeGpu && !VeilLevelPerspectiveRenderer.isRenderingPerspective()) beginTimer()
		val pipeline = event.pipeline
		// Uniforms set through the pipeline reach the lens stage but not reliably the table stages (the Kerr table
		// kept a growing hole's old radius), so the tables' are also set on their shaders directly.
		val tables = TABLE_SHADERS.mapNotNull { VeilRenderSystem.renderer().shaderManager.getShader(it) }
		fun uniform(name: String, set: ShaderUniformAccess.() -> Unit) {
			pipeline.getUniformSafe(name).set()
			for (table in tables) table.getUniform(name)?.set()
		}
		if (VeilLevelPerspectiveRenderer.isRenderingPerspective()) {
			// Veil runs stage passes inside perspective renders (other level views) too; they stay unlensed.
			uniform("HoleCount") { setInt(0) }
			uniform("SourceCount") { setInt(0) }
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
			spinUniforms[i].set(hole.spin.toFloat(), 0f, 0f, 0f)
		}
		uploadDisk(::uniform, level, holes.firstOrNull(), minecraft)
		uniform("HoleCount") { setInt(holes.size) }
		uniform("Holes") { setVectors(*holeUniforms) }
		uniform("HoleSpins") { setVectors(*spinUniforms) }
		val (sourceCount, general) = packSources(holes, visibleSources(level), camera, sourceUniforms)
		uniform("SourceCount") { setInt(sourceCount) }
		uniform("Sources") { setVectors(*sourceUniforms) }
		uniform("GeneralSpacetime") { setInt(if (general) 1 else 0) }
		uniform("KerrTableEnabled") { setInt(kerrMode.ordinal) }
		uniform("LensMode") { setInt(if (remarchDebug) 6 else if (rawFrame && skipTables) 5 else if (rawFrame) 4 else if (marchEveryPixel) 3 else if (marchStepsDebug) 2 else if (marchDebug) 1 else 0) }
		val sky = level.getSkyColor(camera, minecraft.timer.getGameTimeDeltaPartialTick(false))
		pipeline.getUniformSafe("SkyColor").setVector(sky.x.toFloat(), sky.y.toFloat(), sky.z.toFloat())
		uniform("LensFogColor") { setVector(fogColor[0], fogColor[1], fogColor[2], fogColor[3]) }
		uniform("LensFogStart") { setFloat(fogStart) }
		uniform("LensFogEnd") { setFloat(fogEnd) }
		uniform("LensFogShape") { setInt(fogShape) }
		uniform("ProfileFlags") { setInt(profileFlags) }
		LensVoxels.waterSurface.let { w -> pipeline.getUniformSafe("WaterSurface").setVector(w[0], w[1], w[2], w[3]) }
		uniform("RenderDistance") { setFloat(minecraft.gameRenderer.renderDistance) }
		uniform("ViewChunks") { setInt(minecraft.options.effectiveRenderDistance) }
		uniform("CameraInChunk") { setVector((camera.x - 16.0 * kotlin.math.floor(camera.x / 16.0)).toFloat(), (camera.z - 16.0 * kotlin.math.floor(camera.z / 16.0)).toFloat()) }

		// Every sampler gets a real texture, whatever the mode: Veil binds missing ones to a placeholder on unit 0 and
		// renumbers the rest, and a mismatch there (the voxels' integer 3D sampler) makes the whole draw invalid.
		LensVoxels.ensureTextures()
		val lens = VeilRenderSystem.renderer().shaderManager.getShader(SHADER)
		// The lens pass runs the vertex lens's maths (where the world drew a traced point): its block, and its tables
		// through Veil (which numbers this program's samplers itself).
		lens?.let {
			VertexLens.linkProgram(it.program)
			KerrPaths.ensureTexture()
			it.setTexture("RelativityImages", GL12.GL_TEXTURE_3D, VertexLens.texture.takeIf { id -> id != 0 } ?: LensVoxels.main.light)
			it.setTexture("RelativityKerrPaths", org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, KerrPaths.texture)
			KerrImageMap.ensureTexture()
			it.setTexture("RelativityKerrMap", GL12.GL_TEXTURE_3D, KerrImageMap.texture)
		}
		val atlas = minecraft.textureManager.getTexture(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS).id
		for (shader in listOfNotNull(lens) + tables) {
			for ((suffix, grid) in listOf("" to LensVoxels.main, "Behind" to LensVoxels.behind)) {
				shader.setTexture("Voxels$suffix", GL12.GL_TEXTURE_3D, grid.cells)
				shader.setTexture("VoxelLight$suffix", GL12.GL_TEXTURE_3D, grid.light)
				shader.setTexture("VoxelTint$suffix", GL12.GL_TEXTURE_3D, grid.tint)
				shader.setTexture("VoxelBricks$suffix", GL12.GL_TEXTURE_3D, grid.bricks)
				shader.setTexture("VoxelSmallBricks$suffix", GL12.GL_TEXTURE_3D, grid.smallBricks)
			}
			shader.setTexture("VoxelQuads", org.lwjgl.opengl.GL31.GL_TEXTURE_BUFFER, LensVoxels.quadTexture)
			for (slot in 0 until LensVoxels.TEXTURE_SLOTS) {
				shader.setTexture("VoxelTex$slot", GL11.GL_TEXTURE_2D, LensVoxels.textureSlots[slot].takeIf { it != 0 } ?: atlas)
			}
		}
		val lightmap = (minecraft.gameRenderer.lightTexture() as net.kernelpanicsoft.relativity.mixin.client.LightTextureAccessor).`create_relativity$location`()
		lens?.setTexture("Lightmap", GL11.GL_TEXTURE_2D, minecraft.textureManager.getTexture(lightmap).id)
		var source = if (RelativityConfig.Client.Render.traceBlocks) unseen else Unseen.NONE
		if (source == Unseen.VOXELS) {
			LensVoxels.update(level, holes.firstOrNull(), camera, minecraft.timer.getGameTimeDeltaPartialTick(false))
			for ((suffix, grid) in listOf("" to LensVoxels.main, "Behind" to LensVoxels.behind)) {
				val origin = grid.origin ?: continue
				uniform("VoxelOrigin$suffix") {
					setVector((origin.x - camera.x).toFloat(), (origin.y - camera.y).toFloat(), (origin.z - camera.z).toFloat())
				}
			}
			uniform("VoxelBehindReady") { setInt(if (LensVoxels.behind.ready) 1 else 0) }
			if (!LensVoxels.ready) source = Unseen.NONE
		}
		uniform("UnseenSource") { setInt(source.ordinal) }
	}
}
