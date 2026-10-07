package net.kernelpanicsoft.relativity.client.lens

import com.mojang.blaze3d.platform.GlStateManager
import net.kernelpanicsoft.relativity.CreateRelativity
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix3f
import org.joml.Vector3f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryStack

/**
 * The vertex lens's table of Kerr light paths, drawn each frame before the level (kerr_paths.fsh): for a grid of view
 * directions round the screen, where each ray goes as seen from the hole, in 8 layers of a float array texture that the
 * world's vertex shaders invert (vertex_lens_core.glsl). Its own GL program and framebuffer: it runs in the middle of
 * the level render, so it leaves all state as it found it.
 */
object KerrPaths {
	const val LAYERS = 8

	/** The table (GL_TEXTURE_2D_ARRAY, RGBA32F, [LAYERS] layers); 0 until first drawn. */
	var texture = 0
		private set
	var width = 0
		private set
	var height = 0
		private set

	private var program = 0
	private var framebuffer = 0
	private var vao = 0
	private var failed = false
	private val uniforms = HashMap<String, Int>()

	/**
	 * Draws the table for a hole at [holeGame] (camera-relative, game axes) with Schwarzschild radius [rs] and [spin],
	 * paths followed out to [pathRadius] from it, over view directions within [tanX], [tanY] of the view axis,
	 * [width] × [height] texels. False if it can't.
	 */
	fun draw(holeGame: Vector3f, rs: Float, spin: Float, pathRadius: Float, viewToGame: Matrix3f, tanX: Float, tanY: Float, width: Int, height: Int): Boolean {
		if (failed || !ensureProgram()) return false
		ensureTarget(width, height)
		val previousFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
		val previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
		val previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)
		val blend = GL11.glIsEnabled(GL11.GL_BLEND)
		val depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
		val cull = GL11.glIsEnabled(GL11.GL_CULL_FACE)
		val scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
		MemoryStack.stackPush().use { stack ->
			val viewport = stack.mallocInt(4)
			GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport)
			GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			GlStateManager._viewport(0, 0, width, height)
			GlStateManager._disableBlend()
			GlStateManager._disableDepthTest()
			GlStateManager._disableCull()
			GlStateManager._disableScissorTest()
			GlStateManager._glUseProgram(program)
			GL20.glUniform3f(uniform("HoleGame"), holeGame.x, holeGame.y, holeGame.z)
			GL20.glUniform1f(uniform("HoleRs"), rs)
			GL20.glUniform1f(uniform("HoleSpin"), spin)
			GL20.glUniform1f(uniform("PathRadius"), pathRadius)
			val matrix = stack.mallocFloat(9)
			viewToGame.get(matrix)
			GL20.glUniformMatrix3fv(uniform("ViewToGame"), false, matrix)
			GL20.glUniform2f(uniform("TableTan"), tanX, tanY)
			GL30.glBindVertexArray(vao)
			GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3)
			GL30.glBindVertexArray(previousVao)
			GlStateManager._glUseProgram(previousProgram) // the same program back, so ShaderInstance's cache stays right
			GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer)
			GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3])
		}
		if (blend) GlStateManager._enableBlend()
		if (depth) GlStateManager._enableDepthTest()
		if (cull) GlStateManager._enableCull()
		if (scissor) GlStateManager._enableScissorTest()
		return true
	}

	/** Makes sure the table texture exists (empty until drawn), for samplers that must always have one. */
	fun ensureTexture() {
		if (texture == 0) ensureTarget(16, 16)
	}

	private fun uniform(name: String) = uniforms.getOrPut(name) { GL20.glGetUniformLocation(program, name) }

	private fun ensureTarget(width: Int, height: Int) {
		if (texture != 0 && width == this.width && height == this.height) return
		if (texture == 0) {
			texture = GL11.glGenTextures()
			framebuffer = GL30.glGenFramebuffers()
		}
		this.width = width
		this.height = height
		GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texture)
		GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL30.GL_RGBA32F, width, height, LAYERS, 0, GL11.GL_RGBA, GL11.GL_FLOAT, null as java.nio.ByteBuffer?)
		GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
		GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
		GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
		GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
		GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0)
		val previous = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
		MemoryStack.stackPush().use { stack ->
			val buffers = stack.mallocInt(LAYERS)
			for (layer in 0 until LAYERS) {
				GL30.glFramebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + layer, texture, 0, layer)
				buffers.put(layer, GL30.GL_COLOR_ATTACHMENT0 + layer)
			}
			GL20.glDrawBuffers(buffers)
		}
		val status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous)
		if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
			CreateRelativity.LOGGER.error("Kerr path table framebuffer incomplete ({}); spinning holes bend as if they didn't spin", status)
			failed = true
		}
	}

	private fun ensureProgram(): Boolean {
		if (program != 0) return true
		val resources = Minecraft.getInstance().resourceManager
		fun text(path: String): String? = resources.getResource(ResourceLocation.fromNamespaceAndPath(CreateRelativity.MOD_ID, path))
			.orElse(null)?.open()?.use { String(it.readAllBytes(), Charsets.UTF_8) }
		val kerr = text("pinwheel/shaders/include/kerr.glsl")
		val body = text("shaders/relativity/kerr_paths.fsh")
		if (kerr == null || body == null) {
			failed = true
			return false
		}
		val vertexSource = """
			#version 330 core
			out vec2 texCoord;
			void main() {
			    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
			    texCoord = p;
			    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
			}
		""".trimIndent()
		val fragmentSource = "#version 330 core\n$kerr\n$body"
		val vertex = compile(GL20.GL_VERTEX_SHADER, vertexSource) ?: return false.also { failed = true }
		val fragment = compile(GL20.GL_FRAGMENT_SHADER, fragmentSource) ?: return false.also { failed = true }
		val id = GL20.glCreateProgram()
		GL20.glAttachShader(id, vertex)
		GL20.glAttachShader(id, fragment)
		GL20.glLinkProgram(id)
		GL20.glDeleteShader(vertex)
		GL20.glDeleteShader(fragment)
		if (GL20.glGetProgrami(id, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
			CreateRelativity.LOGGER.error("Kerr path table program didn't link: {}", GL20.glGetProgramInfoLog(id))
			GL20.glDeleteProgram(id)
			failed = true
			return false
		}
		program = id
		vao = GL30.glGenVertexArrays()
		return true
	}

	private fun compile(type: Int, source: String): Int? {
		val shader = GL20.glCreateShader(type)
		GL20.glShaderSource(shader, source)
		GL20.glCompileShader(shader)
		if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) {
			CreateRelativity.LOGGER.error("Kerr path table shader didn't compile: {}", GL20.glGetShaderInfoLog(shader))
			GL20.glDeleteShader(shader)
			return null
		}
		return shader
	}
}
