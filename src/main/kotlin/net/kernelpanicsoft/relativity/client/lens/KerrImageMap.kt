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
 * of each solving for its image. Two textures, swapped each frame: the last frame's map starts this frame's solve.
 * Its own GL program and framebuffer; leaves all state as it found it.
 */
object KerrImageMap {
	const val WIDTH = 128 // azimuth (RELATIVITY_MAP_SIZE in vertex_lens_core.glsl)
	const val HEIGHT = 64 // cos of the polar angle
	const val DEPTH = 48  // radius

	private val textures = IntArray(2)

	/** Per texel, where along its path each image was (R32F; 99 none): the next frame's solve starts there. */
	private val paths = IntArray(2)
	private var current = 0
	private var previousValid = false
	private var program = 0
	private var framebuffer = 0
	private var vao = 0
	private var failed = false
	private val uniforms = HashMap<String, Int>()

	/** Layers redrawn per frame (debug; GameTests: timing); the rest keep last frame's. */
	@JvmField
	var layersPerFrame = DEPTH
	private var nextLayer = 0
	private var frame = 0

	/** Timing (GameTests): 1 the Schwarzschild images only, 2 no cold searches. */
	@JvmField
	var debugMode = 0

	/** The map the vertex shaders read (GL_TEXTURE_3D, RGBA32F); a texture always exists once [ensureTexture] ran. */
	val texture: Int
		get() = textures[current]

	/** Makes sure the textures exist (empty until drawn), for samplers that must always have one. */
	fun ensureTexture() {
		if (textures[0] != 0) return
		for (i in 0..1) {
			paths[i] = GL11.glGenTextures()
			GL11.glBindTexture(GL12.GL_TEXTURE_3D, paths[i])
			GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R32F, WIDTH, HEIGHT, DEPTH, 0, GL11.GL_RED, GL11.GL_FLOAT, null as java.nio.ByteBuffer?)
			GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
			GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
			textures[i] = GL11.glGenTextures()
			GL11.glBindTexture(GL12.GL_TEXTURE_3D, textures[i])
			GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_RGBA32F, WIDTH, HEIGHT, DEPTH, 0, GL11.GL_RGBA, GL11.GL_FLOAT, null as java.nio.ByteBuffer?)
			GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
			GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
			GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT) // azimuth wraps round
			GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
			GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE)
		}
		GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
	}

	/** Forgets the last map (a new hole, or none): the next one starts from the Schwarzschild images. */
	fun invalidate() {
		previousValid = false
	}

	/**
	 * Draws this frame's map for the hole the vertex lens's block describes (VertexLens uploads it first, with the
	 * Kerr path table drawn), the block and its samplers bound. False if it can't.
	 */
	fun draw(): Boolean {
		if (failed || !ensureProgram()) return false
		ensureTexture()
		if (framebuffer == 0) framebuffer = GL30.glGenFramebuffers()
		val target = 1 - current
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
			GL11.glBindTexture(GL12.GL_TEXTURE_3D, paths[current])
			GL20.glUniform1i(uniform("PreviousPath"), 1)
			GlStateManager._activeTexture(GL13.GL_TEXTURE0)
			val previousBound = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D)
			GL11.glBindTexture(GL12.GL_TEXTURE_3D, textures[current])
			GL20.glUniform1i(uniform("PreviousMap"), 0)
			val buffers = stack.mallocInt(2)
			buffers.put(0, GL30.GL_COLOR_ATTACHMENT0).put(1, GL30.GL_COLOR_ATTACHMENT1)
			GL20.glDrawBuffers(buffers)
			GL20.glUniform1i(uniform("PreviousValid"), if (previousValid) 1 else 0)
			GL20.glUniform1i(uniform("Frame"), frame++)
			GL20.glUniform1i(uniform("DebugMode"), debugMode)
			GL30.glBindVertexArray(vao)
			// Layers not redrawn this frame keep what the target held (the map from two frames back).
			val redraw = if (previousValid) layersPerFrame.coerceIn(1, DEPTH) else DEPTH
			for (layer in 0 until DEPTH) {
				if (((layer - nextLayer) % DEPTH + DEPTH) % DEPTH >= redraw) continue
				GL30.glFramebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, textures[target], 0, layer)
				GL30.glFramebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, paths[target], 0, layer)
				GL20.glUniform1i(uniform("Layer"), layer)
				GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3)
			}
			nextLayer = (nextLayer + redraw) % DEPTH
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
		current = target
		previousValid = true
		return true
	}

	private fun uniform(name: String) = uniforms.getOrPut(name) { GL20.glGetUniformLocation(program, name) }

	private fun ensureProgram(): Boolean {
		if (program != 0) return true
		val resources = Minecraft.getInstance().resourceManager
		fun text(path: String): String? = resources.getResource(ResourceLocation.fromNamespaceAndPath(CreateRelativity.MOD_ID, path))
			.orElse(null)?.open()?.use { String(it.readAllBytes(), Charsets.UTF_8) }
		val core = text("pinwheel/shaders/include/vertex_lens_core.glsl")
		val body = text("shaders/relativity/kerr_image_map.fsh")
		if (core == null || body == null) {
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
		val fragment = compile(GL20.GL_FRAGMENT_SHADER, "#version 330 core\n$core\n$body") ?: return false.also { failed = true }
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
