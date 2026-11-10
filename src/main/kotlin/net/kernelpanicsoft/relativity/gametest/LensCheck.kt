package net.kernelpanicsoft.relativity.gametest

import com.mojang.blaze3d.platform.GlStateManager
import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.client.lens.VertexLens
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix4f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryStack

/**
 * Debug (GameTests): runs the vertex lens's maths on the GPU for a list of view-space points (lens_check.fsh), so it
 * can be checked point by point. Uses the block and tables the vertex lens set up this frame.
 */
object LensCheck {
	private var program = 0

	/**
	 * Per point: 1 placed or -1 hidden, and its bent NDC x, y. With [why]: why it's hidden (lens_check.fsh); [tubes]:
	 * the tubes' aim; [sky]: where the field lens's light from the camera along it ends heading.
	 */
	fun run(points: List<FloatArray>, projection: Matrix4f, why: Boolean = false, tubes: Boolean = false, sky: Boolean = false, image: Boolean = false, table: Boolean = false, tableImage: Boolean = false, fieldForce: Boolean = false, field: Boolean = false, fieldImage: Boolean = false, fieldLens: Boolean = false, chainSecond: Boolean = false): List<FloatArray> {
		if (program == 0) program = link() ?: return emptyList()
		VertexLens.linkProgram(program)
		val width = points.size
		val input = GL11.glGenTextures()
		val output = GL11.glGenTextures()
		val framebuffer = GL30.glGenFramebuffers()
		val vao = GL30.glGenVertexArrays()
		val result = FloatArray(width * 4)
		MemoryStack.stackPush().use { stack ->
			val data = stack.mallocFloat(width * 4)
			for (p in points) data.put(p[0]).put(p[1]).put(p[2]).put(1f)
			data.flip()
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, input)
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, width, 1, 0, GL11.GL_RGBA, GL11.GL_FLOAT, data)
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, output)
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, width, 1, 0, GL11.GL_RGBA, GL11.GL_FLOAT, null as java.nio.FloatBuffer?)
			val previousFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
			val viewport = stack.mallocInt(4)
			GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport)
			GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, output, 0)
			GlStateManager._viewport(0, 0, width, 1)
			GlStateManager._disableBlend()
			GlStateManager._disableDepthTest()
			val previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
			GlStateManager._glUseProgram(program)
			GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0)
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, input)
			GL20.glUniform1i(GL20.glGetUniformLocation(program, "Points"), 0)
			val matrix = stack.mallocFloat(16)
			projection.get(matrix)
			GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "Projection"), false, matrix)
			GL20.glUniform1i(GL20.glGetUniformLocation(program, "Direct"), if (chainSecond) 16 else if (fieldLens) 15 else if (fieldImage) 14 else if (field) 13 else if (fieldForce) 12 else if (tableImage) 11 else if (table) 10 else if (image) 9 else if (sky) 8 else if (tubes) 7 else if (why) 4 else 0)
			GL30.glBindVertexArray(vao)
			GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3)
			val out = stack.mallocFloat(width * 4)
			GL11.glReadPixels(0, 0, width, 1, GL11.GL_RGBA, GL11.GL_FLOAT, out)
			out.get(result)
			GL30.glBindVertexArray(0)
			GlStateManager._glUseProgram(previousProgram)
			GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer)
			GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3])
		}
		GL11.glDeleteTextures(input)
		GL11.glDeleteTextures(output)
		GL30.glDeleteFramebuffers(framebuffer)
		GL30.glDeleteVertexArrays(vao)
		return List(width) { result.copyOfRange(it * 4, it * 4 + 4) }
	}

	private fun link(): Int? {
		val resources = Minecraft.getInstance().resourceManager
		fun text(path: String) = resources.getResource(ResourceLocation.fromNamespaceAndPath(CreateRelativity.MOD_ID, path)).orElse(null)
			?.open()?.use { String(it.readAllBytes(), Charsets.UTF_8) }
		val core = text("pinwheel/shaders/include/vertex_lens_core.glsl") ?: return null
		val body = text("shaders/relativity/lens_check.fsh") ?: return null
		fun compile(type: Int, source: String): Int {
			val shader = GL20.glCreateShader(type)
			GL20.glShaderSource(shader, source)
			GL20.glCompileShader(shader)
			if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) CreateRelativity.LOGGER.error("LensCheck: {}", GL20.glGetShaderInfoLog(shader))
			return shader
		}
		val vertex = compile(GL20.GL_VERTEX_SHADER, "#version 330 core\nvoid main() { vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2)); gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }")
		val fields = net.kernelpanicsoft.relativity.client.lens.field.LensFields.include
		val fragment = compile(GL20.GL_FRAGMENT_SHADER, "#version 330 core\n$core\n$fields\n$body")
		val id = GL20.glCreateProgram()
		GL20.glAttachShader(id, vertex)
		GL20.glAttachShader(id, fragment)
		GL20.glLinkProgram(id)
		if (GL20.glGetProgrami(id, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
			CreateRelativity.LOGGER.error("LensCheck link: {}", GL20.glGetProgramInfoLog(id))
			return null
		}
		return id
	}
}
