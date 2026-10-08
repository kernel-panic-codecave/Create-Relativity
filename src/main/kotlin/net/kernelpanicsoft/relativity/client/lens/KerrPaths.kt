package net.kernelpanicsoft.relativity.client.lens

import com.mojang.blaze3d.platform.GlStateManager
import net.kernelpanicsoft.relativity.CreateRelativity
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import org.joml.Vector3f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryStack

/**
 * The vertex lens's table of Kerr light paths (kerr_paths.fsh): for a grid of directions from the camera covering every
 * direction (relativityTableDir in vertex_lens_core.glsl: azimuth round the camera-hole line, angle from it), where each
 * ray goes as seen from the hole, in 8 layers of a float array texture that the image map inverts (KerrImageMap). It
 * depends only on where the camera is relative to the hole, not where it looks, so it's redrawn only when that (or the
 * hole) changes. Its own GL program and framebuffer: it runs in the middle of the level render, so it leaves all state
 * as it found it.
 */
object KerrPaths {
	/** Two groups of 8 (the most one draw writes), each drawn by its own pass (kerr_paths.fsh's Group). */
	const val LAYERS = 16

	/** The table's size: azimuth columns, angle rows. */
	const val WIDTH = 384
	const val HEIGHT = 512

	/** True if the last [draw] redrew any of the table (it stops once caught up with an unchanged camera and hole). */
	var changed = false
		private set
	private var lastKey = FloatArray(0)

	/** Rows redrawn per frame while the table catches up with a moving camera; the rest keep their last paths. */
	@JvmField
	var rowsPerFrame = HEIGHT / 4
	private var nextRow = 0
	private var drawnSinceChange = 0

	/** The table (GL_TEXTURE_2D_ARRAY, RGBA32F, [LAYERS] layers); 0 until first drawn. */
	var texture = 0
		private set
	var width = 0
		private set
	var height = 0
		private set

	private var program = 0
	private var framebuffer = 0
	private var framebuffer2 = 0
	private var vao = 0
	private var headCopy = 0
	private var headWidth = 0
	private var headHeight = 0
	private var failed = false
	private val uniforms = HashMap<String, Int>()

	/**
	 * Draws the table round the lens centre [holeGame] (camera-relative, game axes; the dominant source) of size [rs]
	 * (r_s of a hole), its paths through every source in [sources] (the first [sourceCount], packed as kerr.glsl's Sources:
	 * [BlackHoleLens.packSources]) followed out to [pathRadius] from the centre: while that changes (the camera moves),
	 * [rowsPerFrame] rows a frame in turn, so the table lags the camera by a few frames; whole when new or [whole] (a
	 * jump). False if it can't.
	 */
	fun draw(holeGame: Vector3f, rs: Float, pathRadius: Float, sources: Array<org.joml.Vector4f>, sourceCount: Int, whole: Boolean = false): Boolean {
		if (failed || !ensureProgram()) return false
		val width = WIDTH
		val height = HEIGHT
		val key = FloatArray(5 + 12 * sourceCount)
		key[0] = holeGame.x; key[1] = holeGame.y; key[2] = holeGame.z; key[3] = rs; key[4] = pathRadius
		for (i in 0 until 3 * sourceCount) {
			val v = sources[i]
			key[5 + 4 * i] = v.x; key[6 + 4 * i] = v.y; key[7 + 4 * i] = v.z; key[8 + 4 * i] = v.w
		}
		val fresh = texture == 0 || this.width != width || this.height != height
		if (fresh || !(key contentEquals lastKey)) drawnSinceChange = 0
		changed = drawnSinceChange < height
		if (!changed) return true
		lastKey = key
		ensureTarget(width, height)
		val rows = if (fresh || whole) height else rowsPerFrame.coerceIn(1, height)
		lastDraw = floatArrayOf(holeGame.x, holeGame.y, holeGame.z, rs, pathRadius)
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
			val scissorBox = stack.mallocInt(4)
			GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox)
			GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			GlStateManager._viewport(0, 0, width, height)
			GlStateManager._disableBlend()
			GlStateManager._disableDepthTest()
			GlStateManager._disableCull()
			GlStateManager._disableScissorTest()
			GlStateManager._glUseProgram(program)
			GL20.glUniform3f(uniform("CentreGame"), holeGame.x, holeGame.y, holeGame.z)
			GL20.glUniform1f(uniform("CentreScale"), rs)
			GL20.glUniform1i(uniform("SourceCount"), sourceCount)
			val packed = stack.mallocFloat(4 * sources.size)
			for (v in sources) packed.put(v.x).put(v.y).put(v.z).put(v.w)
			packed.flip()
			GL20.glUniform4fv(uniform("Sources"), packed)
			GL20.glUniform1f(uniform("PathRadius"), pathRadius)
			GL20.glUniform2f(uniform("TableSize"), width.toFloat(), height.toFloat())
			GL30.glBindVertexArray(vao)
			// This frame's rows (wrapping round), through the scissor; each group of layers in turn.
			GL11.glEnable(GL11.GL_SCISSOR_TEST)
			// (Unit 0's texture is put back after: GlStateManager caches it.)
			val previousActive = GlStateManager._getActiveTexture()
			GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0)
			val previousTexture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
			var start = nextRow
			for (group in 0..1) {
				GL20.glUniform1i(uniform("Group"), group)
				// Group 1 reads group 0's head (layer 0), copied out of the table first (a texture isn't read while drawn into).
				GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0)
				if (group == 1) {
					if (headCopy == 0) headCopy = GL11.glGenTextures()
					GL11.glBindTexture(GL11.GL_TEXTURE_2D, headCopy)
					if (headWidth != width || headHeight != height) {
						GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, width, height, 0, GL11.GL_RGBA, GL11.GL_FLOAT, null as java.nio.ByteBuffer?)
						GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
						GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
						headWidth = width
						headHeight = height
					}
					// (Attachment 0 still holds layer 0 from group 0.)
					GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0)
					GL11.glDisable(GL11.GL_SCISSOR_TEST)
					GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height)
					GL11.glEnable(GL11.GL_SCISSOR_TEST)
				} else {
					GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
				}
				GL20.glUniform1i(uniform("Head"), 0)
				GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, if (group == 0) framebuffer else framebuffer2)
				start = nextRow
				var left = rows
				while (left > 0) {
					val count = minOf(left, height - start)
					GL11.glScissor(0, start, width, count)
					GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3)
					left -= count
					start = (start + count) % height
				}
			}
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture0)
			GlStateManager._activeTexture(previousActive)
			nextRow = start
			drawnSinceChange += rows
			GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3])
			GL11.glDisable(GL11.GL_SCISSOR_TEST)
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

	/** The table's texels (layer-major, then rows, RGBA), read back from the GPU (debug; GameTests). */
	fun readBack(): FloatArray {
		val data = FloatArray(width * height * LAYERS * 4)
		GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texture)
		GL11.glGetTexImage(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA, GL11.GL_FLOAT, data)
		GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0)
		return data
	}

	/** The parameters of the last [draw] (debug; GameTests): centre (game, camera-relative), its size, path radius. */
	var lastDraw = FloatArray(0)
		private set

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
			framebuffer2 = GL30.glGenFramebuffers()
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
		// One framebuffer per group of 8 layers, attached once (re-attaching a layered float texture each frame is slow).
		var status = GL30.GL_FRAMEBUFFER_COMPLETE
		for ((group, target) in listOf(framebuffer, framebuffer2).withIndex()) {
			GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, target)
			MemoryStack.stackPush().use { stack ->
				val buffers = stack.mallocInt(8)
				for (layer in 0 until 8) {
					GL30.glFramebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + layer, texture, 0, 8 * group + layer)
					buffers.put(layer, GL30.GL_COLOR_ATTACHMENT0 + layer)
				}
				GL20.glDrawBuffers(buffers)
			}
			val groupStatus = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
			if (groupStatus != GL30.GL_FRAMEBUFFER_COMPLETE) status = groupStatus
		}
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
		val core = text("pinwheel/shaders/include/vertex_lens_core.glsl") // the table's directions
		val body = text("shaders/relativity/kerr_paths.fsh")
		if (kerr == null || core == null || body == null) {
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
		val fragmentSource = "#version 330 core\n#define RELATIVITY_PATHS\n$kerr\n$core\n$body"
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
