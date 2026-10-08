package net.kernelpanicsoft.relativity.client.lens

import com.mojang.blaze3d.platform.GlStateManager
import foundry.veil.api.client.render.VeilLevelPerspectiveRenderer
import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.physics.lens.SchwarzschildImages
import net.kernelpanicsoft.relativity.spacetime.BlackHole
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.AABB
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.common.NeoForge
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL31
import org.lwjgl.opengl.GL41
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.util.concurrent.CompletableFuture
import kotlin.math.abs

/**
 * The black-hole lens: world geometry is lensed in the vertex shader (vertex_lens_core.glsl), injected into
 * vanilla's world shaders and Sodium's terrain shader through Veil, into Flywheel's (CompilationMixin) and into
 * shader packs' (TransformPatcherMixin). Each vertex moves to where its light appears, at its true distance, so depth
 * testing handles occlusion.
 * This feeds those shaders: a uniform block (the nearest lensing hole, the level's projection, the view's rotation)
 * and two textures: the Schwarzschild inverse image table ([SchwarzschildImages.forFiltering]) and, for a spinning
 * hole, this frame's Kerr path table ([KerrPaths]), which the shader inverts by Newton's method starting from the
 * Schwarzschild image. The post pass ([BlackHoleLens]) then draws the shadow, the disk and the bent sky.
 */
object VertexLens {
	private const val TABLE_U0 = 64
	private const val TABLE_UP = 64
	private const val TABLE_PHI = 128
	/** The table's texture unit: the last one, clear of what vanilla, Flywheel and shader packs use. */
	private val TEXTURE_UNIT: Int by lazy { GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS) - 1 }
	private const val BLOCK = "RelativityLens"
	private const val SAMPLER = "RelativityImages"
	private const val BLOCK_BYTES = 192 // 12 vec4s: see vertex_lens_core.glsl's RelativityLensData
	private const val KERR_SAMPLER = "RelativityKerrPaths"

	/** The Kerr image map's sampler (KerrImageMap), on the unit below the path table's. */
	private const val KERR_MAP_SAMPLER = "RelativityKerrMap"

	/** kerr.glsl's KERR_START: the least the table's paths reach from the hole (r_s). */
	private const val KERR_START = 80.0

	/** How far the Kerr path table's paths reach from [hole]: past everything drawn (the render distance round the camera). */
	private fun pathRadius(hole: BlackHole, cameraDistance: Double): Double =
		maxOf(KERR_START * hole.schwarzschildRadius, cameraDistance + 1.5 * Minecraft.getInstance().gameRenderer.renderDistance)

	/** Spin from which the hole is treated as Kerr (kerr.glsl's MIN_KERR_SPIN): images found on [KerrPaths]' table. */
	private const val MIN_KERR_SPIN = 0.05

	/** Boxes within this many r_s of the hole skip frustum culling: bent light can show them from anywhere. */
	private const val RELAX_RADIUS = 60.0

	private var table: CompletableFuture<FloatArray>? = null
	/** The Schwarzschild image table (GL_TEXTURE_3D); 0 until built. */
	var texture = 0
		private set
	private var buffer = 0
	private var binding = -1
	/** Shaders' programs as last linked (Veil recompiles programs in place, so ids change under a shader). */
	private val linkedPrograms = java.util.WeakHashMap<net.minecraft.client.renderer.ShaderInstance, Int>()

	/** The hole lensing this frame (world position), or null. */
	@Volatile private var hole: BlackHole? = null
	private var savedSmartCull: Boolean? = null

	/** True while vertex lensing is on for this frame's level render (the post pass then skips geometry). */
	var activeThisFrame = false
		private set

	fun init() {
		table = CompletableFuture.supplyAsync {
			SchwarzschildImages.forFiltering(SchwarzschildImages.buildTable(TABLE_U0, TABLE_UP, TABLE_PHI), TABLE_U0, TABLE_UP, TABLE_PHI)
		}
		NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent::class.java, ::onRenderStage)
	}

	/** Debug: draws every vertex at its flat-space direction, without the image table (GameTests). */
	@JvmField
	var skipTable = false

	/** Bends a spinning hole as if it didn't spin (debug; GameTests). */
	@JvmField
	var kerrOff = false

	/** Draws the Kerr path table but leaves the vertices' Kerr solve off (debug; GameTests: timing). */
	@JvmField
	var kerrSolveOff = false

	/**
	 * Debug (GameTests): 1 hides vertices a spinning hole's path table didn't place; 2 those it placed on paths going
	 * out, 3 coming in; 0 none.
	 */
	@JvmField
	var kerrDebug = 0

	/** Turns Flywheel's visualization off, so its blocks draw through vanilla renderers (GameTests compare the two). */
	@JvmField
	@Volatile
	var flywheelOff = false

	private val wanted: Boolean
		get() = RelativityConfig.Client.Render.lensing

	private fun onRenderStage(event: RenderLevelStageEvent) {
		when (event.stage) {
			RenderLevelStageEvent.Stage.AFTER_SKY -> beginLevel(event.projectionMatrix)
			RenderLevelStageEvent.Stage.AFTER_LEVEL -> setEnabled(false)
			else -> Unit
		}
	}

	/** Before terrain: pick the hole, upload the block, bind the table, link any new programs. */
	/** The level's projection last frame (debug; GameTests). */
	@JvmField
	var lastProjection = Matrix4f()

	/** GPU time of the Kerr path pass and the image map pass (debug; GameTests, with BlackHoleLens.timeGpu). */
	val pathsTimer = GpuTimer()
	val mapTimer = GpuTimer()

	/** Camera movement relative to the hole in one frame past which the Kerr tables are redrawn whole (blocks). */
	private const val JUMP_BLOCKS = 0.5f
	private val lastHoleGame = Vector3f(Float.MAX_VALUE)
	private var jump = false

	/** The hole the Kerr image map was last drawn for (a new one starts it over). */
	private var mapHole: Any? = null

	private fun beginLevel(projection: Matrix4f) {
		if (!VeilLevelPerspectiveRenderer.isRenderingPerspective()) lastProjection = Matrix4f(projection)
		if (!ensureResources()) return
		val minecraft = Minecraft.getInstance()
		val level = minecraft.level
		// Perspective renders (Veil level views, such as other mods' cameras) draw the level too, and stay unlensed.
		val perspective = VeilLevelPerspectiveRenderer.isRenderingPerspective()
		val camera = minecraft.gameRenderer.mainCamera.position
		val nearest = if (wanted && level != null && !perspective) {
			BlackHoleLens.visibleHoles(level).minByOrNull { it.center.distanceToSqr(camera) }
		} else null
		if (!perspective) {
			hole = nearest
			activeThisFrame = nearest != null
			relaxCaveCulling(nearest != null)
		}
		// The level's view rotation (as GameRenderer builds it): the shader works in view space.
		val view = Quaternionf(minecraft.gameRenderer.mainCamera.rotation()).conjugate()
		// A spinning hole: this frame's Kerr path table, which the shaders invert.
		var kerr = false
		if (nearest != null && abs(nearest.spin) >= MIN_KERR_SPIN && !kerrOff) {
			val holeGame = Vector3f((nearest.x - camera.x).toFloat(), (nearest.y - camera.y).toFloat(), (nearest.z - camera.z).toFloat())
			// A jump (a teleport, a fast flight) redraws the Kerr tables whole; walking catches them up a slice a frame.
			jump = lastHoleGame.distance(holeGame) > JUMP_BLOCKS
			lastHoleGame.set(holeGame)
			if (BlackHoleLens.timeGpu) pathsTimer.begin()
			kerr = KerrPaths.draw(
				holeGame, nearest.schwarzschildRadius.toFloat(), nearest.spin.toFloat(),
				pathRadius(nearest, holeGame.length().toDouble()).toFloat(), jump,
			)
			if (BlackHoleLens.timeGpu) pathsTimer.end()
		}
		upload(nearest, camera.x, camera.y, camera.z, view, projection, nearest != null, kerr && !kerrSolveOff)

		val previous = GlStateManager._getActiveTexture()
		GlStateManager._activeTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT)
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture)
		GlStateManager._activeTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT - 1)
		GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, KerrPaths.texture)
		GlStateManager._activeTexture(previous)
		// The image map the vertices read, solved on the path table (the block and the table bound for it).
		KerrImageMap.ensureTexture()
		if (!perspective) {
			if (!kerr || nearest?.id != mapHole) KerrImageMap.invalidate()
			mapHole = nearest?.id
			if (kerr && !kerrSolveOff) {
				if (BlackHoleLens.timeGpu) mapTimer.begin()
				KerrImageMap.draw(KerrPaths.changed, jump)
				if (BlackHoleLens.timeGpu) mapTimer.end()
			}
		}
		GlStateManager._activeTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT - 2)
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, KerrImageMap.texture)
		GlStateManager._activeTexture(previous)
	}

	/** Switches lensing in the shaders on or off without changing the hole (e.g. around gizmos drawn after the lens). */
	fun setEnabled(enabled: Boolean) {
		if (buffer == 0) return
		MemoryStack.stackPush().use { stack ->
			val data = stack.mallocFloat(1)
			data.put(0, if (enabled && hole != null) 1f else 0f)
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, buffer)
			GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 16L, data)
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0)
		}
	}

	private fun upload(hole: BlackHole?, camX: Double, camY: Double, camZ: Double, view: Quaternionf, projection: Matrix4f, enabled: Boolean, kerr: Boolean) {
		MemoryStack.stackPush().use { stack ->
			val data = stack.mallocFloat(BLOCK_BYTES / 4)
			if (hole != null) {
				val at = view.transform(Vector3f((hole.x - camX).toFloat(), (hole.y - camY).toFloat(), (hole.z - camZ).toFloat()))
				data.put(at.x).put(at.y).put(at.z).put(hole.schwarzschildRadius.toFloat())
			} else {
				data.put(0f).put(0f).put(0f).put(0f)
			}
			data.put(if (enabled) 1f else 0f).put(if (skipTable) 1f else 0f).put(if (kerr) 1f else 0f).put(kerrDebug.toFloat())
			projection.get(data.position(), data)
			data.position(data.position() + 16)
			if (hole != null && kerr) {
				val dx = hole.x - camX
				val dy = hole.y - camY
				val dz = hole.z - camZ
				data.put(dx.toFloat()).put(dy.toFloat()).put(dz.toFloat()).put(hole.spin.toFloat())
				data.put(0f).put(0f).put(KerrPaths.width.toFloat()).put(KerrPaths.height.toFloat())
				val viewToGame = org.joml.Matrix3f().set(Quaternionf(view).conjugate())
				for (column in 0..2) {
					val c = viewToGame.getColumn(column, Vector3f())
					data.put(c.x).put(c.y).put(c.z).put(0f)
				}
				// Boyer-Lindquist r of the camera (local axes: game z, x, y; spin about game y), capped at the start sphere.
				val rs = hole.schwarzschildRadius
				val a = hole.spin * rs / 2
				val w = dx * dx + dy * dy + dz * dz - a * a
				val camR = kotlin.math.sqrt(0.5 * w + kotlin.math.sqrt(0.25 * w * w + a * a * dy * dy))
				val reach = pathRadius(hole, kotlin.math.sqrt(dx * dx + dy * dy + dz * dz))
				// The image map: from just outside the horizon out to the paths' end.
				val horizon = rs / 2 + kotlin.math.sqrt(maxOf(rs * rs / 4 - a * a, 0.0))
				data.put(minOf(camR, reach).toFloat()).put(reach.toFloat()).put((1.05 * horizon).toFloat()).put(reach.toFloat())
			} else {
				repeat(24) { data.put(0f) }
			}
			data.position(0).limit(BLOCK_BYTES / 4)
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, buffer)
			GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0L, data)
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0)
		}
		GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, binding, buffer)
	}

	/** Creates the buffer and, once the table is built, its texture. False until both exist. */
	private fun ensureResources(): Boolean {
		if (buffer == 0) {
			binding = GL11.glGetInteger(GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS) - 1
			buffer = GL15.glGenBuffers()
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, buffer)
			GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, BLOCK_BYTES.toLong(), GL15.GL_DYNAMIC_DRAW)
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0)
		}
		if (texture == 0) {
			val data = table?.getNow(null) ?: return false
			texture = uploadTable(data)
			table = null
			CreateRelativity.LOGGER.info("Vertex lens image table ready ({}x{}x{})", TABLE_U0, TABLE_UP, TABLE_PHI)
		}
		return true
	}

	private fun uploadTable(data: FloatArray): Int {
		val id = GL11.glGenTextures()
		val previous = GlStateManager._getActiveTexture()
		GlStateManager._activeTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT)
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, id)
		GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0)
		GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0)
		GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0)
		GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4)
		GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0)
		GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0)
		val pixels = MemoryUtil.memAllocFloat(data.size)
		try {
			pixels.put(data).flip()
			GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_RG32F, TABLE_U0, TABLE_UP, TABLE_PHI, 0, GL30.GL_RG, GL11.GL_FLOAT, pixels)
		} finally {
			MemoryUtil.memFree(pixels)
		}
		GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
		GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
		GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
		GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
		GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE)
		GlStateManager._activeTexture(previous)
		return id
	}

	/** Links [shader]'s program (see [linkProgram]) unless it's the one already linked. */
	@JvmStatic
	fun ensureLinked(shader: net.minecraft.client.renderer.ShaderInstance) {
		val id = shader.id
		if (linkedPrograms[shader] == id) return
		linkedPrograms[shader] = id
		linkProgram(id)
	}

	/** Points [program]'s lens block at our binding and its table sampler at our texture unit (if it has them). */
	@JvmStatic
	fun linkProgram(program: Int) {
		if (binding < 0) binding = GL11.glGetInteger(GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS) - 1
		val block = GL31.glGetUniformBlockIndex(program, BLOCK)
		if (block != GL31.GL_INVALID_INDEX) GL31.glUniformBlockBinding(program, block, binding)
		for ((name, unit) in listOf(SAMPLER to TEXTURE_UNIT, KERR_SAMPLER to TEXTURE_UNIT - 1, KERR_MAP_SAMPLER to TEXTURE_UNIT - 2)) {
			val sampler = GL20.glGetUniformLocation(program, name)
			if (sampler < 0) continue
			if (GL.getCapabilities().OpenGL41) {
				GL41.glProgramUniform1i(program, sampler, unit)
			} else {
				val current = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
				GL20.glUseProgram(program)
				GL20.glUniform1i(sampler, unit)
				GL20.glUseProgram(current)
			}
		}
	}

	/** Flywheel's line placing each instanced vertex on screen (flywheel:internal/common.vert, both backends). */
	private const val FLYWHEEL_POSITION = "gl_Position = flw_viewProjection * flw_vertexPos;"

	/** The vertex lens's declarations and functions (vertex_lens_core.glsl), read once. */
	private val lensSource: String? by lazy {
		val location = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CreateRelativity.MOD_ID, "pinwheel/shaders/include/vertex_lens_core.glsl")
		val resource = Minecraft.getInstance().resourceManager.getResource(location).orElse(null) ?: return@lazy null
		resource.open().use { String(it.readAllBytes(), Charsets.UTF_8) }
	}

	/** Shader-pack programs not lensed: the shadow pass (drawn from the sun), the hand, sky, clouds, Distant Horizons. */
	private val IRIS_SKIPPED = listOf("shadow", "gbuffers_hand", "gbuffers_sky", "gbuffers_clouds", "dh_")

	/** A shader-pack program's `main`, renamed so the lens can run after it. */
	private val IRIS_MAIN = Regex("""\bvoid\s+main\s*\(\s*(void\s*)?\)""")

	/**
	 * A shader pack's vertex source for program [name] (as Iris patched it) with the lens applied to its final position:
	 * its `main` renamed, and a new one that runs it, then bends gl_Position. Unchanged for skipped programs, and for
	 * programs with later stages that set the position (geometry or tessellation; the lens would have to go there).
	 */
	@JvmStatic
	fun irisSource(name: String, source: String, laterStages: Boolean): String {
		val lens = lensSource ?: return source
		if (laterStages || IRIS_SKIPPED.any { name.startsWith(it) }) return source
		val main = IRIS_MAIN.find(source) ?: return source
		return source.substring(0, main.range.first) + "void relativity_packMain()" + source.substring(main.range.last + 1) +
			"\n" + lens + "\nvoid main() {\n    relativity_packMain();\n    gl_Position = relativityLensLevel(gl_Position);\n}\n"
	}

	/**
	 * A Flywheel shader's full source with the lens applied where it places vertices on screen (so Create's instanced
	 * parts bend like everything else, with Flywheel's instancing kept); other sources unchanged.
	 */
	@JvmStatic
	fun flywheelSource(source: CharSequence): CharSequence {
		val text = source.toString()
		val at = text.indexOf(FLYWHEEL_POSITION)
		val main = text.indexOf("void _flw_main(")
		val lens = lensSource
		if (at < 0 || main < 0 || main > at || lens == null) return source
		return text.substring(0, main) + "\n" + lens + "\n" + text.substring(main, at) +
			"gl_Position = relativityLens(flw_viewProjection * flw_vertexPos, flw_projection);" +
			text.substring(at + FLYWHEEL_POSITION.length)
	}

	/** Light bent round the hole shows sections a straight line of sight is blocked from: cave culling would drop them. */
	private fun relaxCaveCulling(relax: Boolean) {
		val minecraft = Minecraft.getInstance()
		if (relax && savedSmartCull == null) {
			savedSmartCull = minecraft.smartCull
			minecraft.smartCull = false
			minecraft.levelRenderer.needsUpdate()
		} else if (!relax && savedSmartCull != null) {
			minecraft.smartCull = savedSmartCull!!
			savedSmartCull = null
			minecraft.levelRenderer.needsUpdate()
		}
	}

	/** Frustum override: boxes near the lensing hole are always drawn. */
	@JvmStatic
	fun alwaysVisible(box: AABB): Boolean {
		if (!activeThisFrame) return false
		val hole = hole ?: return false
		val radius = minOf(RELAX_RADIUS * hole.schwarzschildRadius, 256.0)
		val dx = maxOf(box.minX - hole.x, 0.0, hole.x - box.maxX)
		val dy = maxOf(box.minY - hole.y, 0.0, hole.y - box.maxY)
		val dz = maxOf(box.minZ - hole.z, 0.0, hole.z - box.maxZ)
		return dx * dx + dy * dy + dz * dz < radius * radius
	}
}
