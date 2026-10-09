package net.kernelpanicsoft.relativity.client.lens

import com.mojang.blaze3d.platform.GlStateManager
import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.spacetime.BlackHole
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryStack
import net.kernelpanicsoft.relativity.physics.lens.KerrSlotBaker
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Baked camera slots for a lone spinning hole: what the vertex lens inverts depends only on where the camera is relative
 * to the hole (its distance and height angle; round the spin axis everything just turns), so the image map is baked for
 * a grid of camera positions, a few at a time in the background as the camera nears them, and the live map
 * ([KerrImageMap]) is the blend of the four slots round the camera, turned to its azimuth (kerr_map_blend.fsh): no
 * solving while the camera moves among baked slots, and no catching up. Until they're baked, the per-frame solver stands
 * in. Slots: camera distance [R0] r_s to [R_MAX] r_s in steps of [Q] times, height angle from the spin axis in steps of
 * [THETA_STEP] (both measured: blending neighbours errs by a quarter pixel at the median, 2-3 px at the 95th percentile,
 * and loses about 1% of images). A spin or size change starts the cache over; [MAX_SLOTS] are kept, least recently used
 * dropped.
 */
object KerrSlots {
	const val R0 = 1.5
	const val Q = 1.09
	const val R_MAX = 40.0
	const val THETA_STEP = 0.05
	val R_SLOTS = floor(ln(R_MAX / R0) / ln(Q)).toInt() + 1
	val THETA_SLOTS = floor(Math.PI / THETA_STEP).toInt() + 1
	const val MAX_SLOTS = 40

	/** Baked slots (key: r index * 1000 + theta index): their map textures, last used frame. */
	private class Slot(val texture: Int, val second: Int, var used: Long) {
		fun delete() = GL11.glDeleteTextures(intArrayOf(texture, second))
	}

	/** The live secondary-image map (the blend of the slots' secondary maps; VertexLens.drawSecondImages), and whether
	 * this frame's is one. */
	var secondTexture = 0
		private set
	var secondBlending = false
		private set
	private val slots = HashMap<Int, Slot>()
	private var cacheKey = ""
	private var frame = 0L

	/** The slot being baked (key), or -1; its bake, on the CPU (KerrSlotBaker: spare cores, a second or so), and for which
	 * cache key (a bake finishing after the hole changed is dropped). */
	private var baking = -1
	private var bakeJob: CompletableFuture<Array<FloatArray>>? = null
	private var bakeKey = ""
	private val baker = Executors.newSingleThreadExecutor { Thread(it, "Relativity slot baker").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

	/** Debug (GameTests): slots baked so far, and whether this frame's live map is a blend. */
	val bakedSlots: Int get() = slots.size
	var blending = false
		private set

	/** GPU time of the last frame's baking and blend (debug; GameTests, with BlackHoleLens.timeGpu). */
	val bakeTimer = GpuTimer()
	val blendTimer = GpuTimer()

	/** Debug (GameTests: timing): each bake call's wall time with the GPU finished before and after (ms), by phase. */
	@JvmField
	var measureBakes = false
	val bakeCosts = HashMap<String, ArrayList<Double>>()

	/** Debug (GameTests: timing): false bakes no further slots (blends what's baked). */
	@JvmField
	var bakeNew = true

	/** How far apart (radians) the four slots' images of a point may be before it's left hidden (kerr_map_blend.fsh). */
	@JvmField
	var spreadLimit = 0.01

	/** Turns slot baking off (debug; GameTests: comparison with the per-frame solver). */
	@JvmField
	var enabled = true

	private fun key(i: Int, j: Int) = i * 1000 + j
	private fun slotDistance(i: Int) = R0 * Q.pow(i)
	private fun slotAngle(j: Int) = j * THETA_STEP

	/** How far a hole's maps reach (blocks): past everything drawn from any slot. */
	fun outer(hole: BlackHole): Double =
		maxOf(80.0 * hole.schwarzschildRadius, R_MAX * hole.schwarzschildRadius + 1.5 * Minecraft.getInstance().gameRenderer.renderDistance)

	/**
	 * Each frame, before the level renders (lens block and tables bound by [VertexLens] after): bakes a bit of the next
	 * slot near the camera, and if the four round it are baked, blends them into the live map. True if the live map now
	 * holds the blend (the per-frame solver can rest), false if the caller must solve as before.
	 */
	fun update(hole: BlackHole, camera: Vec3): Boolean {
		blending = false
		secondBlending = false
		if (!enabled) return false
		frame += 1
		val keyNow = "${hole.id}/${hole.schwarzschildRadius}/${hole.spin}"
		if (keyNow != cacheKey) clear(keyNow)
		val rel = camera.subtract(hole.center)
		val r = rel.length() / hole.schwarzschildRadius
		if (r < R0 || r > R_MAX * Q) return false
		val u = ln(r / R0) / ln(Q)
		val v = acos((rel.y / rel.length()).coerceIn(-1.0, 1.0)) / THETA_STEP
		val i0 = floor(u).toInt().coerceIn(0, R_SLOTS - 2)
		val j0 = floor(v).toInt().coerceIn(0, THETA_SLOTS - 2)
		if (BlackHoleLens.timeGpu) bakeTimer.begin()
		if (baking >= 0 || bakeNew) {
			val start = System.nanoTime()
			bake(hole, i0, j0)
			if (measureBakes) bakeCosts.getOrPut("frame") { ArrayList() } += (System.nanoTime() - start) / 1e6
		}
		if (BlackHoleLens.timeGpu) bakeTimer.end()
		val needed = listOf(key(i0, j0), key(i0 + 1, j0), key(i0, j0 + 1), key(i0 + 1, j0 + 1))
		if (needed.any { it !in slots }) return false
		for (k in needed) slots[k]!!.used = frame
		val fu = (u - i0).coerceIn(0.0, 1.0)
		val fv = (v - j0).coerceIn(0.0, 1.0)
		val weights = Vector4f(((1 - fu) * (1 - fv)).toFloat(), (fu * (1 - fv)).toFloat(), ((1 - fu) * fv).toFloat(), (fu * fv).toFloat())
		val azimuth = atan2(rel.z, rel.x)
		// The camera turned back into the slots' frame, and each slot's camera there.
		val cameraInSlots = Vector3d(rel.x * kotlin.math.cos(-azimuth) - rel.z * kotlin.math.sin(-azimuth), rel.y, rel.x * kotlin.math.sin(-azimuth) + rel.z * kotlin.math.cos(-azimuth))
		val slotCameras = needed.map { k -> slotCamera(k / 1000, k % 1000, hole) }
		if (BlackHoleLens.timeGpu) blendTimer.begin()
		blending = blend(needed.map { slots[it]!!.texture }, weights, azimuth.toFloat(), hole, cameraInSlots, slotCameras, KerrImageMap.texture)
		if (secondTexture == 0) secondTexture = ImageMapSolver.newMapTexture()
		secondBlending = blending && blend(needed.map { slots[it]!!.second }, weights, azimuth.toFloat(), hole, cameraInSlots, slotCameras, secondTexture)
		if (BlackHoleLens.timeGpu) blendTimer.end()
		return blending
	}

	private fun clear(keyNow: String) {
		for (slot in slots.values) slot.delete()
		slots.clear()
		baking = -1
		bakeJob = null
		cacheKey = keyNow
	}

	/**
	 * Picks the nearest unbaked slot to (i0, j0) (the four round the camera first, then the ring round them) and starts its
	 * bake in the background; takes in a finished one (uploaded as the slot's map).
	 */
	private fun bake(hole: BlackHole, i0: Int, j0: Int) {
		val job = bakeJob
		if (job != null) {
			if (!job.isDone) return
			bakeJob = null
			val maps = try { job.get() } catch (e: Exception) { CreateRelativity.LOGGER.error("Kerr slot bake failed", e); null }
			if (maps != null && bakeKey == cacheKey) {
				slots[baking] = Slot(upload(maps[0]), upload(maps[1]), frame)
				if (slots.size > MAX_SLOTS) {
					val oldest = slots.entries.minBy { it.value.used }
					oldest.value.delete()
					slots.remove(oldest.key)
				}
			}
			baking = -1
		}
		if (!bakeNew) return
		var best = -1
		var bestDistance = Double.MAX_VALUE
		for (di in -1..2) for (dj in -1..2) {
			val i = i0 + di
			val j = j0 + dj
			if (i !in 0 until R_SLOTS || j !in 0 until THETA_SLOTS || key(i, j) in slots) continue
			val distance = (di - 0.5) * (di - 0.5) + (dj - 0.5) * (dj - 0.5)
			if (distance < bestDistance) {
				bestDistance = distance
				best = key(i, j)
			}
		}
		if (best < 0) return
		baking = best
		bakeKey = cacheKey
		// The virtual camera, at azimuth 0 (the hole's +x side).
		val rel = slotCamera(best / 1000, best % 1000, hole)
		val m = hole.mass
		val a = hole.spin * m
		val inner = 1.05 * (m + sqrt(maxOf(m * m - a * a, 0.0)))
		val baker = KerrSlotBaker(m, a, doubleArrayOf(rel.x, rel.y, rel.z), inner, outer(hole), ImageMapSolver.WIDTH, ImageMapSolver.HEIGHT, ImageMapSolver.DEPTH)
		val started = System.nanoTime()
		bakeJob = CompletableFuture.supplyAsync({
			baker.bakeBoth().also { if (measureBakes) synchronized(bakeCosts) { bakeCosts.getOrPut("slot") { ArrayList() } += (System.nanoTime() - started) / 1e6 } }
		}, this.baker)
	}

	/** A new map texture holding [map] (KerrSlotBaker's layout: x fastest, then y, then layer; RGBA). */
	private fun upload(map: FloatArray): Int {
		val texture = ImageMapSolver.newMapTexture()
		val buffer = org.lwjgl.system.MemoryUtil.memAllocFloat(map.size)
		try {
			buffer.put(map).flip()
			val previous = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D)
			GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture)
			// (Unpacking as Minecraft may have left it set for its own uploads: plain rows here.)
			GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0)
			GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0)
			GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0)
			GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4)
			GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0)
			GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0)
			GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, 0, ImageMapSolver.WIDTH, ImageMapSolver.HEIGHT, ImageMapSolver.DEPTH, GL11.GL_RGBA, GL11.GL_FLOAT, buffer)
			GL11.glBindTexture(GL12.GL_TEXTURE_3D, previous)
		} finally {
			org.lwjgl.system.MemoryUtil.memFree(buffer)
		}
		return texture
	}

	/** Slot (i, j)'s camera from the hole (blocks): azimuth 0, the hole's +x side. */
	private fun slotCamera(i: Int, j: Int, hole: BlackHole): Vector3d {
		val rBlocks = slotDistance(i) * hole.schwarzschildRadius
		val theta = slotAngle(j)
		return Vector3d(rBlocks * kotlin.math.sin(theta), rBlocks * kotlin.math.cos(theta), 0.0)
	}

	private var program = 0
	private var framebuffer = 0
	private var vao = 0
	private var failed = false

	/** The blend pass into [KerrImageMap]'s live texture, layer by layer. */
	private fun blend(textures: List<Int>, weights: Vector4f, azimuth: Float, hole: BlackHole, camera: Vector3d, slotCameras: List<Vector3d>, target: Int): Boolean {
		if (failed || !ensureProgram()) return false
		KerrImageMap.ensureTexture()
		if (framebuffer == 0) framebuffer = GL30.glGenFramebuffers()
		val previousFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
		val previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
		val previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)
		val previousActive = GlStateManager._getActiveTexture()
		val blendOn = GL11.glIsEnabled(GL11.GL_BLEND)
		val depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
		val cull = GL11.glIsEnabled(GL11.GL_CULL_FACE)
		val scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
		MemoryStack.stackPush().use { stack ->
			val viewport = stack.mallocInt(4)
			GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport)
			GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			GlStateManager._viewport(0, 0, ImageMapSolver.WIDTH, ImageMapSolver.HEIGHT)
			GlStateManager._disableBlend()
			GlStateManager._disableDepthTest()
			GlStateManager._disableCull()
			GlStateManager._disableScissorTest()
			GlStateManager._glUseProgram(program)
			val previousBound = IntArray(4)
			for (k in 0..3) {
				GlStateManager._activeTexture(GL13.GL_TEXTURE0 + k)
				previousBound[k] = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D)
				GL11.glBindTexture(GL12.GL_TEXTURE_3D, textures[k])
				GL20.glUniform1i(GL20.glGetUniformLocation(program, "Slot$k"), k)
			}
			GL20.glUniform4f(GL20.glGetUniformLocation(program, "Weights"), weights.x, weights.y, weights.z, weights.w)
			GL20.glUniform1f(GL20.glGetUniformLocation(program, "Azimuth"), azimuth)
			GL20.glUniform3f(GL20.glGetUniformLocation(program, "Camera"), camera.x.toFloat(), camera.y.toFloat(), camera.z.toFloat())
			for ((k, c) in slotCameras.withIndex()) {
				GL20.glUniform3f(GL20.glGetUniformLocation(program, "SlotCamera[$k]"), c.x.toFloat(), c.y.toFloat(), c.z.toFloat())
			}
			val m = hole.mass
			val a = hole.spin * m
			val inner = 1.05 * (m + sqrt(maxOf(m * m - a * a, 0.0)))
			GL20.glUniform2f(GL20.glGetUniformLocation(program, "Radii"), inner.toFloat(), outer(hole).toFloat())
			GL20.glUniform1f(GL20.glGetUniformLocation(program, "SpreadLimit"), spreadLimit.toFloat())
			GL30.glBindVertexArray(vao)
			val layerUniform = GL20.glGetUniformLocation(program, "Layer")
			for (layer in 0 until ImageMapSolver.DEPTH) {
				GL30.glFramebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, target, 0, layer)
				GL20.glUniform1i(layerUniform, layer)
				GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3)
			}
			for (k in 3 downTo 0) {
				GlStateManager._activeTexture(GL13.GL_TEXTURE0 + k)
				GL11.glBindTexture(GL12.GL_TEXTURE_3D, previousBound[k])
			}
			GlStateManager._activeTexture(previousActive)
			GL30.glBindVertexArray(previousVao)
			GlStateManager._glUseProgram(previousProgram)
			GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer)
			GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3])
		}
		if (blendOn) GlStateManager._enableBlend()
		if (depth) GlStateManager._enableDepthTest()
		if (cull) GlStateManager._enableCull()
		if (scissor) GlStateManager._enableScissorTest()
		return true
	}

	private fun ensureProgram(): Boolean {
		if (program != 0) return true
		if (failed) return false
		val body = Minecraft.getInstance().resourceManager.getResource(ResourceLocation.fromNamespaceAndPath(CreateRelativity.MOD_ID, "shaders/relativity/kerr_map_blend.fsh"))
			.orElse(null)?.open()?.use { String(it.readAllBytes(), Charsets.UTF_8) }
		if (body == null) {
			failed = true
			return false
		}
		fun compile(type: Int, source: String): Int? {
			val shader = GL20.glCreateShader(type)
			GL20.glShaderSource(shader, source)
			GL20.glCompileShader(shader)
			if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) {
				CreateRelativity.LOGGER.error("Kerr slot blend shader didn't compile: {}", GL20.glGetShaderInfoLog(shader))
				GL20.glDeleteShader(shader)
				return null
			}
			return shader
		}
		val vertex = compile(GL20.GL_VERTEX_SHADER, "#version 330 core\nvoid main() { vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2)); gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }")
		val fragment = compile(GL20.GL_FRAGMENT_SHADER, "#version 330 core\n$body")
		if (vertex == null || fragment == null) {
			failed = true
			return false
		}
		val id = GL20.glCreateProgram()
		GL20.glAttachShader(id, vertex)
		GL20.glAttachShader(id, fragment)
		GL20.glLinkProgram(id)
		GL20.glDeleteShader(vertex)
		GL20.glDeleteShader(fragment)
		if (GL20.glGetProgrami(id, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
			CreateRelativity.LOGGER.error("Kerr slot blend program didn't link: {}", GL20.glGetProgramInfoLog(id))
			GL20.glDeleteProgram(id)
			failed = true
			return false
		}
		program = id
		vao = GL30.glGenVertexArrays()
		return true
	}
}
