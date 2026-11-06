package net.kernelpanicsoft.relativity.client.lens

import net.kernelpanicsoft.relativity.CreateRelativity
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix4f
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL42
import org.lwjgl.opengl.GL43

/**
 * The frame's light table (light_table.comp; vertex_lens_core.glsl's relativityTable*): for a grid of view directions a
 * little past the screen, where each one's light, marched through every source's field, first crosses each of [SHELLS]
 * spheres round the camera, and where it ends heading. The vertex lens inverts it for composed sources (several holes,
 * holes and masses) instead of solving each vertex through the field, and the post pass reads its sky from it. Built each
 * frame for that frame's camera (no lag), by a compute pass: OpenGL 4.3 (NeoForge asks for 4.6); without it the field
 * lens's chain stands in.
 */
object LightTable {
	const val WIDTH = 240
	const val HEIGHT = 136
	const val SHELLS = 32
	const val NEAR = 0.5f

	/** Signatures drawn (a geometry pass each: VertexLens.drawSecondImages): vertex_lens_core.glsl's RELATIVITY_TABLE_SLOTS. */
	const val SLOTS = 4

	/** The inverse map's cube faces' edge (texels): vertex_lens_core.glsl's RELATIVITY_TABLE_CUBE. */
	const val CUBE = 128

	/** How far past the screen's edges the table reaches (of its half-tangents). */
	private const val MARGIN = 1.15f

	/** Debug (GameTests): off, the chain stands in. */
	@JvmField
	var enabled = true

	/** The table (GL_TEXTURE_2D_ARRAY, 2 SHELLS + 1 layers), 0 until made; its inverse, the seeds (R32UI, 6 CUBE x CUBE: a
	 * cube map's faces side by side; SHELLS layers per slot, then the slots' signatures). */
	var texture = 0
		private set
	var seeds = 0
		private set
	private var program = 0
	private var pickProgram = 0
	private var seedsProgram = 0
	private var counts = 0

	/** Last frame's slots in use (read back a frame late, so nothing waits on the GPU): a pass each beyond slot 0. */
	var slotsInUse = BooleanArray(SLOTS) { it == 0 }
		private set
	/** Last frame's slots' signatures (-1: none). Debug. */
	var slotSignatures = IntArray(SLOTS) { if (it == 0) 0 else -1 }
		private set
	private var slotBuffer = 0
	private var slotPending = false
	private var failed = false

	/** Whether this frame has a table (the block's [7].w). */
	var on = false
		private set

	/** The half-tangents across and up, and the farthest sphere (blocks), this frame. */
	private var tanX = 1f
	private var tanY = 1f
	private var far = 512f

	val available: Boolean get() = !failed && GL.getCapabilities().OpenGL44

	/** Decides this frame's table ([wanted]: composed sources) and its view from [projection]; then [put] writes [6], [7]. */
	fun prepare(wanted: Boolean, projection: Matrix4f) {
		on = wanted && enabled && available && ensure()
		tanY = MARGIN / projection.m11()
		tanX = MARGIN / projection.m00()
		far = maxOf(Minecraft.getInstance().gameRenderer.renderDistance * 1.5f, 64f)
	}

	/** The block's [6] and [7]. */
	fun put(data: java.nio.FloatBuffer) {
		data.put(tanX).put(tanY).put(NEAR).put(far)
		data.put(WIDTH.toFloat()).put(HEIGHT.toFloat()).put(SHELLS.toFloat()).put(if (on) 1f else 0f)
	}

	/**
	 * Fills the table (the block uploaded and bound first; [link] points each program at it): the light marched and its
	 * signatures tallied (light_table.comp), the slots picked (light_table_pick.comp), the inverse map rasterized
	 * (light_table_seeds.comp).
	 */
	/** Debug (GameTests): times each build on the GPU, [buildTimer]. */
	@JvmField
	var timeBuild = false
	val buildTimer = GpuTimer()

	fun build(link: (Int) -> Unit) {
		if (!on) return
		if (timeBuild) buildTimer.begin()
		try {
			buildPasses(link)
		} finally {
			if (timeBuild) buildTimer.end()
		}
	}

	private fun buildPasses(link: (Int) -> Unit) {
		val previous = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
		org.lwjgl.opengl.GL44.glClearTexImage(seeds, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, intArrayOf(-1))
		org.lwjgl.opengl.GL44.glClearTexImage(counts, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, intArrayOf(0))
		GL42.glBindImageTexture(0, texture, 0, true, 0, GL15.GL_READ_WRITE, GL30.GL_RGBA32F)
		GL42.glBindImageTexture(1, seeds, 0, true, 0, GL15.GL_READ_WRITE, GL30.GL_R32UI)
		GL42.glBindImageTexture(2, counts, 0, false, 0, GL15.GL_READ_WRITE, GL30.GL_R32UI)
		link(program)
		GL20.glUseProgram(program)
		GL43.glDispatchCompute((WIDTH + 7) / 8, (HEIGHT + 7) / 8, 1)
		GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
		link(pickProgram)
		GL20.glUseProgram(pickProgram)
		GL43.glDispatchCompute(1, 1, 1)
		GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GL42.GL_PIXEL_BUFFER_BARRIER_BIT)
		readSlots()
		link(seedsProgram)
		GL20.glUseProgram(seedsProgram)
		GL43.glDispatchCompute((WIDTH + 6) / 8, (HEIGHT + 6) / 8, SHELLS)
		GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT or GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
		GL20.glUseProgram(previous)
	}

	/**
	 * Reads last frame's slot signatures (copied into a buffer then) and copies this frame's: so which slots are in use is
	 * known a frame late, without waiting on the GPU.
	 */
	private fun readSlots() {
		if (slotBuffer == 0) {
			slotBuffer = GL15.glGenBuffers()
			GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, slotBuffer)
			GL15.glBufferData(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, 4L * SLOTS, GL15.GL_STREAM_READ)
			GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, 0)
		}
		// (Only once the GPU has done the last copy, its fence signalled: mapping the buffer before then waits for every
		// frame queued ahead of it, a second and more at a few fps. Not done yet: last frame's slots kept, no new copy.)
		if (slotPending) {
			val fence = slotFence
			val done = fence == 0L || org.lwjgl.opengl.GL32.glClientWaitSync(fence, 0, 0L).let {
				it == org.lwjgl.opengl.GL32.GL_ALREADY_SIGNALED || it == org.lwjgl.opengl.GL32.GL_CONDITION_SATISFIED
			}
			if (!done) return
			if (fence != 0L) org.lwjgl.opengl.GL32.glDeleteSync(fence)
			slotFence = 0L
		}
		GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, slotBuffer)
		if (slotPending) {
			val data = GL15.glMapBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY)
			if (data != null) {
				val ints = data.asIntBuffer()
				slotsInUse = BooleanArray(SLOTS) { it == 0 || ints.get(it) != -1 }
				slotSignatures = IntArray(SLOTS) { ints.get(it) }
				GL15.glUnmapBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER)
			}
		}
		org.lwjgl.opengl.GL45.glGetTextureSubImage(seeds, 0, 0, 0, SLOTS * SHELLS, SLOTS, 1, 1, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, 4 * SLOTS, 0L)
		GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, 0)
		slotFence = org.lwjgl.opengl.GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
		slotPending = true
	}

	/** The GPU's fence after the slots' copy (0: none). */
	private var slotFence = 0L

	private fun ensure(): Boolean {
		if (texture != 0 && program != 0 && pickProgram != 0 && seedsProgram != 0) return true
		if (failed) return false
		texture = GL11.glGenTextures()
		GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texture)
		GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL30.GL_RGBA32F, WIDTH, HEIGHT, 2 * SHELLS + 1, 0, GL11.GL_RGBA, GL11.GL_FLOAT, null as java.nio.FloatBuffer?)
		GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
		GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
		GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
		GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
		GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0)
		seeds = GL11.glGenTextures()
		GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, seeds)
		GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL30.GL_R32UI, 6 * CUBE, CUBE, SLOTS * SHELLS + 1, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, null as java.nio.IntBuffer?)
		GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
		GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
		GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0)
		counts = GL11.glGenTextures()
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, counts)
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32UI, 256, 1, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, null as java.nio.IntBuffer?)
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
		program = compile("light_table") ?: 0
		pickProgram = compile("light_table_pick") ?: 0
		seedsProgram = compile("light_table_seeds") ?: 0
		if (program == 0 || pickProgram == 0 || seedsProgram == 0) {
			failed = true
			return false
		}
		return true
	}

	private fun compile(name: String): Int? {
		val resources = Minecraft.getInstance().resourceManager
		fun text(path: String) = resources.getResource(ResourceLocation.fromNamespaceAndPath(CreateRelativity.MOD_ID, path)).orElse(null)
			?.open()?.use { String(it.readAllBytes(), Charsets.UTF_8) }
		val core = text("pinwheel/shaders/include/vertex_lens_core.glsl") ?: return null
		val body = text("shaders/relativity/$name.comp") ?: return null
		val shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER)
		val fields = net.kernelpanicsoft.relativity.client.lens.field.LensFields.include
		GL20.glShaderSource(shader, "#version 430 core\n$core\n$fields\n$body")
		GL20.glCompileShader(shader)
		if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) {
			CreateRelativity.LOGGER.error("Light table ({}): {}", name, GL20.glGetShaderInfoLog(shader))
			return null
		}
		val id = GL20.glCreateProgram()
		GL20.glAttachShader(id, shader)
		GL20.glLinkProgram(id)
		if (GL20.glGetProgrami(id, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
			CreateRelativity.LOGGER.error("Light table link: {}", GL20.glGetProgramInfoLog(id))
			return null
		}
		return id
	}
}
