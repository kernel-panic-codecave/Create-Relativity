package net.kernelpanicsoft.relativity.client.lens

import com.mojang.blaze3d.platform.GlStateManager
import net.kernelpanicsoft.relativity.CreateRelativity
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryStack

/**
 * The vertex lens's Kerr image map (kerr_image_map.fsh), drawn each frame after the Kerr path table: for points round a
 * spinning hole on a grid of azimuth, polar angle and (logarithmic) radius, how each one's image is turned from the
 * straight line of sight. The world's vertex shaders read it with one filtered lookup (vertex_lens_core.glsl) instead
 * of each solving for its image. A layer is solved into a scratch texture from the live one (its last answer starts the
 * solve), then copied back; while the camera moves only [layersPerFrame] layers a frame are, in turn.
 * Its own GL program and framebuffer; leaves all state as it found it.
 */
object KerrImageMap : ImageMapSolver() {
	const val WIDTH = ImageMapSolver.WIDTH
	const val HEIGHT = ImageMapSolver.HEIGHT
	const val DEPTH = ImageMapSolver.DEPTH
}

/**
 * The secondary-image map solved per frame the same way (the light the long way round the hole, by the Einstein ring:
 * each search starts from the Schwarzschild secondary image, and stays on that side of the hole). Read while no baked
 * slots blend one (KerrSlots).
 */
object KerrSecondImageMap : ImageMapSolver(second = true)

/**
 * An image map and its solver state ([KerrImageMap] is the live one the world reads; the slot baker runs its own for
 * cameras elsewhere). The GL program is shared. [second]: secondary images instead of primary ones.
 */
open class ImageMapSolver(private val second: Boolean = false) {
	/** The live map ([LIVE]) and a scratch one each layer is solved into before being copied back. */
	private val textures = IntArray(2)

	/** Per texel, where along its path each image was (R32F; 99 none): the next solve starts there. Live and scratch. */
	private val paths = IntArray(2)
	private var previousValid = false
	private var framebuffer = 0

	/** Layers solved per frame while the map catches up with the camera; the rest keep their last answer. */
	@JvmField
	var layersPerFrame = 2
	private var nextLayer = 0
	private var frame = 0

	/** Timing (GameTests): 1 the Schwarzschild images only, 2 no cold searches. */
	@JvmField
	var debugMode = 0

	/** The map the vertex shaders read (GL_TEXTURE_3D, RGBA32F); a texture always exists once [ensureTexture] ran. */
	val texture: Int
		get() = textures[LIVE]

	/** Makes sure the textures exist (empty until drawn), for samplers that must always have one. */
	fun ensureTexture() {
		if (textures[0] != 0) return
		for (i in 0..1) {
			paths[i] = GL11.glGenTextures()
			GL11.glBindTexture(GL12.GL_TEXTURE_3D, paths[i])
			GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R32F, WIDTH, HEIGHT, DEPTH, 0, GL11.GL_RED, GL11.GL_FLOAT, null as java.nio.ByteBuffer?)
			GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
			GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
			textures[i] = newMapTexture()
		}
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
	}

	/** Forgets the last map (a new hole, or none): the next one starts from the Schwarzschild images. */
	fun invalidate() {
		previousValid = false
	}

	/** Layers drawn since the path table last [changed][draw]: once all are, the map stands until it changes again. */
	private var drawnSinceChange = 0
	private var repairPending = false

	/** The most layers drawn in one frame, even for a whole redraw (the slot baker keeps it low: it's background work). */
	@JvmField
	var maxLayersPerFrame = 8
	private var repairLeft = 0

	/**
	 * Draws this frame's map for the hole the vertex lens's block describes (VertexLens uploads it first, with the
	 * Kerr path table drawn), the block and its samplers bound, if the table [changed] (the camera moved relative to the
	 * hole) or the map isn't whole yet. It doesn't depend on where the camera looks. False if it can't.
	 */
	fun draw(changed: Boolean, whole: Boolean = false, sources: Array<org.joml.Vector4f>? = null, sourceCount: Int = 0): Boolean {
		if (failed || !ensureProgram()) return false
		if (changed || !previousValid) {
			drawnSinceChange = 0
			repairPending = repairs
		}
		// Caught up: once more, every layer (a few a frame), with every earlier failure searched again against the
		// finished path table; then the map stands.
		var repair = false
		if (drawnSinceChange >= DEPTH) {
			if (!repairPending) return true
			if (repairLeft <= 0) repairLeft = DEPTH
			repair = true
			repairLeft -= maxLayersPerFrame
			if (repairLeft <= 0) repairPending = false
		}
		ensureTexture()
		if (framebuffer == 0) framebuffer = GL30.glGenFramebuffers()
		val previousFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
		val previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
		val previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)
		val previousTexture = GlStateManager._getActiveTexture()
		val blend = GL11.glIsEnabled(GL11.GL_BLEND)
		val depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
		val cull = GL11.glIsEnabled(GL11.GL_CULL_FACE)
		val scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
		MemoryStack.stackPush().use { stack ->
			val viewport = stack.mallocInt(4)
			GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport)
			GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			GlStateManager._viewport(0, 0, WIDTH, HEIGHT)
			GlStateManager._disableBlend()
			GlStateManager._disableDepthTest()
			GlStateManager._disableCull()
			GlStateManager._disableScissorTest()
			GlStateManager._glUseProgram(program)
			// The previous map on a unit of its own (the block's samplers keep theirs, set by VertexLens.linkProgram).
			GlStateManager._activeTexture(GL13.GL_TEXTURE1)
			val previousBound1 = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D)
			GL11.glBindTexture(GL12.GL_TEXTURE_3D, paths[LIVE])
			GL20.glUniform1i(uniform("PreviousPath"), 1)
			GlStateManager._activeTexture(GL13.GL_TEXTURE0)
			val previousBound = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D)
			GL11.glBindTexture(GL12.GL_TEXTURE_3D, textures[LIVE])
			GL20.glUniform1i(uniform("PreviousMap"), 0)
			GlStateManager._activeTexture(GL13.GL_TEXTURE2)
			val previousBound2 = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D)
			val buffers = stack.mallocInt(2)
			buffers.put(0, GL30.GL_COLOR_ATTACHMENT0).put(1, GL30.GL_COLOR_ATTACHMENT1)
			GL20.glDrawBuffers(buffers)
			GL20.glUniform1i(uniform("PreviousValid"), if (previousValid) 1 else 0)
			GL20.glUniform1i(uniform("Frame"), frame++)
			// Searches that failed against a table still catching up are tried again against the settled one.
			GL20.glUniform1i(uniform("RetryAll"), if ((changed && !whole && !repair) || !retryHidden) 0 else 1)
			GL20.glUniform1i(uniform("DebugMode"), debugMode)
			GL20.glUniform1i(uniform("Second"), if (second) 1 else 0)
			GL20.glUniform1i(uniform("SideCheck"), if (sideCheck) 1 else 0)
			GL20.glUniform1i(uniform("SourceCount"), if (sources == null) 0 else sourceCount)
			if (sources != null) {
				val packed = stack.mallocFloat(4 * sources.size)
				for (v in sources) packed.put(v.x).put(v.y).put(v.z).put(v.w)
				packed.flip()
				GL20.glUniform4fv(uniform("Sources"), packed)
			}
			GL30.glBindVertexArray(vao)
			// This frame's layers, in turn: each solved into the scratch textures, then copied into the live ones. Outermost
			// first: each layer starts its search from the one just outside it (kerr_image_map.fsh), already this frame's.
			// (Whole redraws too are spread over frames: all 48 layers at once can stall the GPU for seconds.)
			val redraw = if (previousValid && !whole && !repair) layersPerFrame.coerceIn(1, DEPTH) else maxLayersPerFrame
			for (layer in DEPTH - 1 downTo 0) {
				if (((layer - nextLayer) % DEPTH + DEPTH) % DEPTH >= redraw) continue
				GL30.glFramebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, textures[SCRATCH], 0, layer)
				GL30.glFramebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, paths[SCRATCH], 0, layer)
				GL20.glUniform1i(uniform("Layer"), layer)
				GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3)
				for ((attachment, live) in listOf(GL30.GL_COLOR_ATTACHMENT0 to textures[LIVE], GL30.GL_COLOR_ATTACHMENT1 to paths[LIVE])) {
					GL11.glReadBuffer(attachment)
					GL11.glBindTexture(GL12.GL_TEXTURE_3D, live)
					GL12.glCopyTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, layer, 0, 0, WIDTH, HEIGHT)
				}
			}
			GL11.glBindTexture(GL12.GL_TEXTURE_3D, previousBound2)
			GlStateManager._activeTexture(GL13.GL_TEXTURE0)
			nextLayer = (nextLayer + redraw) % DEPTH
			drawnSinceChange += redraw
			GL11.glBindTexture(GL12.GL_TEXTURE_3D, previousBound)
			GlStateManager._activeTexture(GL13.GL_TEXTURE1)
			GL11.glBindTexture(GL12.GL_TEXTURE_3D, previousBound1)
			GlStateManager._activeTexture(previousTexture)
			GL30.glBindVertexArray(previousVao)
			GlStateManager._glUseProgram(previousProgram) // the same program back, so ShaderInstance's cache stays right
			GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer)
			GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3])
		}
		if (blend) GlStateManager._enableBlend()
		if (depth) GlStateManager._enableDepthTest()
		if (cull) GlStateManager._enableCull()
		if (scissor) GlStateManager._enableScissorTest()
		previousValid = true
		return true
	}

	/** Whether the map is whole for the path table it was last drawn against (caught up, repaired). */
	val settled: Boolean
		get() = previousValid && drawnSinceChange >= DEPTH && !repairPending

	/**
	 * A copy of the live map (a baked slot keeps it); this solver keeps its own, so the next bake (a neighbouring camera,
	 * whose images are close) starts from these answers instead of from scratch.
	 */
	fun copyTexture(): Int {
		ensureTexture()
		val copy = newMapTexture()
		if (framebuffer == 0) framebuffer = GL30.glGenFramebuffers()
		val previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
		GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer)
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, copy)
		for (layer in 0 until DEPTH) {
			GL30.glFramebufferTextureLayer(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, textures[LIVE], 0, layer)
			GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0)
			GL12.glCopyTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, layer, 0, 0, WIDTH, HEIGHT)
		}
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
		GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead)
		return copy
	}

	/** Solves every layer again from its current answers (a new path table drawn whole: the slot baker's next slot). */
	fun restart() {
		drawnSinceChange = 0
		repairPending = repairs
	}

	/**
	 * Whether texels found hidden last time are searched again on a whole redraw (otherwise one in 8 a frame, staggered):
	 * the slot baker turns it off for a slot next to the last, whose hidden region is nearly the same and whose failed
	 * searches (every guess tried) are the costliest.
	 */
	@JvmField
	var retryHidden = true

	/** Whether primary images must lie on their point's own side of the hole (kerr_image_map.fsh ownSide()). */
	@JvmField
	var sideCheck = true

	/** Whether a map that has caught up gets the repair pass (every failure searched again); the slot baker skips it. */
	@JvmField
	var repairs = true

	/** The live map's texels (x fastest, then y, then layer; RGBA), read back from the GPU (debug; GameTests). */
	fun readBack(): FloatArray {
		val data = FloatArray(WIDTH * HEIGHT * DEPTH * 4)
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, textures[LIVE])
		GL11.glGetTexImage(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, data)
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
		return data
	}

	/** The live path positions (kerr_image_map.fsh's PathPosition), as [readBack] (debug; GameTests). */
	fun readBackPaths(): FloatArray {
		val data = FloatArray(WIDTH * HEIGHT * DEPTH)
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, paths[LIVE])
		GL11.glGetTexImage(GL12.GL_TEXTURE_3D, 0, GL11.GL_RED, GL11.GL_FLOAT, data)
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
		return data
	}

	companion object {
		const val WIDTH = 128 // azimuth (RELATIVITY_MAP_SIZE in vertex_lens_core.glsl)
		const val HEIGHT = 64 // cos of the polar angle
		const val DEPTH = 48  // radius
		private const val LIVE = 0
		private const val SCRATCH = 1


		private var program = 0
		private var vao = 0
		private var failed = false
		private val uniforms = HashMap<String, Int>()

		/** An empty map texture (RGBA32F, filtered, azimuth wrapping round). */
		fun newMapTexture(): Int {
			val id = GL11.glGenTextures()
			GL11.glBindTexture(GL12.GL_TEXTURE_3D, id)
			GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_RGBA32F, WIDTH, HEIGHT, DEPTH, 0, GL11.GL_RGBA, GL11.GL_FLOAT, null as java.nio.ByteBuffer?)
			GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
			GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
			GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT) // azimuth wraps round
			GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
			GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE)
			return id
		}

		private fun uniform(name: String) = uniforms.getOrPut(name) { GL20.glGetUniformLocation(program, name) }

		/**
		 * Compiles and links the program now (at startup: the driver takes a while over it, and the first frame with a hole
		 * in view shouldn't).
		 */
		fun warmUp() {
			ensureProgram()
		}

		private fun ensureProgram(): Boolean {
			if (program != 0) return true
			if (failed) return false
			val resources = Minecraft.getInstance().resourceManager
			fun text(path: String): String? = resources.getResource(ResourceLocation.fromNamespaceAndPath(CreateRelativity.MOD_ID, path))
				.orElse(null)?.open()?.use { String(it.readAllBytes(), Charsets.UTF_8) }
			val kerr = text("pinwheel/shaders/include/kerr.glsl") // the sources, for the first guess
			val core = text("pinwheel/shaders/include/vertex_lens_core.glsl")
			val body = text("shaders/relativity/kerr_image_map.fsh")
			if (kerr == null || core == null || body == null) {
				failed = true
				return false
			}
			val vertexSource = """
				#version 330 core
				void main() {
				    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
				    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
				}
			""".trimIndent()
			val vertex = compile(GL20.GL_VERTEX_SHADER, vertexSource) ?: return false.also { failed = true }
			val fragment = compile(GL20.GL_FRAGMENT_SHADER, "#version 330 core\n#define RELATIVITY_PATHS\n$kerr\n$core\n$body") ?: return false.also { failed = true }
			val id = GL20.glCreateProgram()
			GL20.glAttachShader(id, vertex)
			GL20.glAttachShader(id, fragment)
			GL20.glLinkProgram(id)
			GL20.glDeleteShader(vertex)
			GL20.glDeleteShader(fragment)
			if (GL20.glGetProgrami(id, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
				CreateRelativity.LOGGER.error("Kerr image map program didn't link: {}", GL20.glGetProgramInfoLog(id))
				GL20.glDeleteProgram(id)
				failed = true
				return false
			}
			program = id
			VertexLens.linkProgram(id)
			vao = GL30.glGenVertexArrays()
			return true
		}

		private fun compile(type: Int, source: String): Int? {
			val shader = GL20.glCreateShader(type)
			GL20.glShaderSource(shader, source)
			GL20.glCompileShader(shader)
			if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) {
				CreateRelativity.LOGGER.error("Kerr image map shader didn't compile: {}", GL20.glGetShaderInfoLog(shader))
				GL20.glDeleteShader(shader)
				return null
			}
			return shader
		}
	}
}
