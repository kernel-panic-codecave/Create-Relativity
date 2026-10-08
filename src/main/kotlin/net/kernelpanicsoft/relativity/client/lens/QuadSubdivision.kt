package net.kernelpanicsoft.relativity.client.lens

import com.mojang.blaze3d.vertex.VertexConsumer
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.minecraft.client.Minecraft
import net.minecraft.core.SectionPos
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Finer block quads near black holes, for the vertex lens: it moves each vertex to where its light appears, and the GPU
 * draws straight edges between them, so a block face whose image curves (close to a hole) comes out as a straight-sided
 * slab, and a face with one hidden corner disappears whole. While a chunk section near a hole is built, each quad its
 * blocks and fluids draw is split into n x n (attributes interpolated), n growing toward the hole: [levelAt].
 *
 * It depends on where the holes are, not the camera (rebuilding meshes as the camera moves would cost far too much),
 * which is where the bending changes fastest. Sections are rebuilt when a hole moves or grows enough to change theirs.
 */
object QuadSubdivision {
	/** Quads are split into up to this many per side. */
	const val MAX_LEVEL = 6

	/** n = ceil(SCALE r_s / distance from the hole's centre): 6 at 1 r_s, 2 at 3 r_s, 1 past 6 r_s. */
	private const val SCALE = 6.0

	/** The holes' centres and radii (x, y, z, r_s; world), as the chunk builder threads see them. */
	@Volatile
	private var holes: List<DoubleArray> = emptyList()
	private var applied: List<DoubleArray> = emptyList()

	private val subdivider = ThreadLocal.withInitial { QuadSubdivider() }

	/** Quads split so far (debug). */
	@JvmField
	val split = java.util.concurrent.atomic.AtomicLong()

	/** Section builds read this: on for the level being drawn with lensing. */
	@Volatile
	private var active = false

	/** Quads split per side at world point (x, y, z). */
	fun levelAt(x: Double, y: Double, z: Double): Int {
		var level = 1
		for (hole in holes) {
			val dx = x - hole[0]
			val dy = y - hole[1]
			val dz = z - hole[2]
			val distance = sqrt(dx * dx + dy * dy + dz * dz)
			level = maxOf(level, ceil(SCALE * hole[3] / maxOf(distance, 1e-3)).toInt())
		}
		return level.coerceAtMost(MAX_LEVEL)
	}

	/** The consumer a section's blocks draw into: [target] itself, or a subdivider round it if the section is near a hole. */
	@JvmStatic
	fun wrap(target: VertexConsumer, section: SectionPos): VertexConsumer {
		if (!active || holes.isEmpty()) return target
		val origin = section.origin()
		// The section's nearest point to each hole decides whether anything in it is split.
		var near = false
		for (hole in holes) {
			val nx = hole[0].coerceIn(origin.x.toDouble(), origin.x + 16.0)
			val ny = hole[1].coerceIn(origin.y.toDouble(), origin.y + 16.0)
			val nz = hole[2].coerceIn(origin.z.toDouble(), origin.z + 16.0)
			if (levelAt(nx, ny, nz) > 1) near = true
		}
		if (!near) return target
		return subdivider.get().also { it.begin(target, origin.x, origin.y, origin.z) }
	}

	/** Each client tick: the holes the chunk builders split quads round; sections whose splitting changes are rebuilt. */
	fun tick() {
		val minecraft = Minecraft.getInstance()
		val level = minecraft.level
		active = level != null && RelativityConfig.Client.Render.lensing
		val now = if (level == null || !active) emptyList() else SpacetimeField.blackHoles(level).map { doubleArrayOf(it.x, it.y, it.z, it.schwarzschildRadius) }
		// (Turning the vertex lens on or off empties or fills the list, which counts as a change too.)
		if (!changed(now)) return
		holes = now
		val renderer = minecraft.levelRenderer
		for (hole in applied + now) {
			// Sections within reach of a level above 1: SCALE r_s.
			val reach = SCALE * hole[3] + 1.0
			val lo = SectionPos.of(floor(hole[0] - reach).toInt() shr 4, floor(hole[1] - reach).toInt() shr 4, floor(hole[2] - reach).toInt() shr 4)
			val hi = SectionPos.of(floor(hole[0] + reach).toInt() shr 4, floor(hole[1] + reach).toInt() shr 4, floor(hole[2] + reach).toInt() shr 4)
			// (While a level is being swapped the renderer has no sections to mark; its next build is fresh anyway.)
			try {
				for (sx in lo.x()..hi.x()) for (sy in lo.y()..hi.y()) for (sz in lo.z()..hi.z()) renderer.setSectionDirty(sx, sy, sz)
			} catch (_: NullPointerException) {
			}
		}
		applied = now
	}

	/** Whether [now] splits quads differently from what the built sections have: a hole added, gone, moved or resized. */
	private fun changed(now: List<DoubleArray>): Boolean {
		if (now.size != applied.size) return true
		for (i in now.indices) {
			val a = now[i]
			val b = applied[i]
			val moved = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
			if (moved > 0.25 || kotlin.math.abs(a[3] - b[3]) > 0.05 * b[3]) return true
		}
		return false
	}
}

/**
 * Splits each quad drawn into it (four vertices; a vertex ends with its normal, the block format's last element) into
 * n x n quads, n from [QuadSubdivision.levelAt] at the quad's centre, and passes them on to the section's buffer.
 */
class QuadSubdivider : VertexConsumer {
	private lateinit var target: VertexConsumer
	private var originX = 0.0
	private var originY = 0.0
	private var originZ = 0.0
	private var index = 0
	private val position = FloatArray(12)
	private val color = IntArray(16)
	private val uv = FloatArray(8)
	private val overlay = IntArray(8)
	private val light = IntArray(8)
	private val normal = FloatArray(12)

	fun begin(target: VertexConsumer, x: Int, y: Int, z: Int) {
		this.target = target
		originX = x.toDouble()
		originY = y.toDouble()
		originZ = z.toDouble()
		index = 0
	}

	override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer {
		position[index * 3] = x
		position[index * 3 + 1] = y
		position[index * 3 + 2] = z
		return this
	}

	override fun setColor(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer {
		color[index * 4] = red
		color[index * 4 + 1] = green
		color[index * 4 + 2] = blue
		color[index * 4 + 3] = alpha
		return this
	}

	override fun setUv(u: Float, v: Float): VertexConsumer {
		uv[index * 2] = u
		uv[index * 2 + 1] = v
		return this
	}

	override fun setUv1(u: Int, v: Int): VertexConsumer {
		overlay[index * 2] = u
		overlay[index * 2 + 1] = v
		return this
	}

	override fun setUv2(u: Int, v: Int): VertexConsumer {
		light[index * 2] = u
		light[index * 2 + 1] = v
		return this
	}

	override fun setNormal(normalX: Float, normalY: Float, normalZ: Float): VertexConsumer {
		normal[index * 3] = normalX
		normal[index * 3 + 1] = normalY
		normal[index * 3 + 2] = normalZ
		index += 1
		if (index == 4) {
			index = 0
			emit()
		}
		return this
	}

	private fun emit() {
		val cx = (position[0] + position[3] + position[6] + position[9]) / 4.0
		val cy = (position[1] + position[4] + position[7] + position[10]) / 4.0
		val cz = (position[2] + position[5] + position[8] + position[11]) / 4.0
		val n = QuadSubdivision.levelAt(originX + cx, originY + cy, originZ + cz)
		if (n <= 1) {
			for (k in 0..3) put(k)
			return
		}
		QuadSubdivision.split.incrementAndGet()
		// Bilinear over the quad: P(s, t) = (1-s)(1-t) v0 + (1-s) t v1 + s t v2 + s (1-t) v3, each sub-quad's corners in
		// the same order, so it keeps the quad's winding.
		for (i in 0 until n) for (j in 0 until n) {
			val s0 = i.toFloat() / n
			val s1 = (i + 1).toFloat() / n
			val t0 = j.toFloat() / n
			val t1 = (j + 1).toFloat() / n
			put(s0, t0)
			put(s0, t1)
			put(s1, t1)
			put(s1, t0)
		}
	}

	private fun put(k: Int) {
		target.addVertex(position[k * 3], position[k * 3 + 1], position[k * 3 + 2])
			.setColor(color[k * 4], color[k * 4 + 1], color[k * 4 + 2], color[k * 4 + 3])
			.setUv(uv[k * 2], uv[k * 2 + 1])
			.setUv1(overlay[k * 2], overlay[k * 2 + 1])
			.setUv2(light[k * 2], light[k * 2 + 1])
			.setNormal(normal[k * 3], normal[k * 3 + 1], normal[k * 3 + 2])
	}

	private fun put(s: Float, t: Float) {
		val w0 = (1 - s) * (1 - t)
		val w1 = (1 - s) * t
		val w2 = s * t
		val w3 = s * (1 - t)
		fun f(a: FloatArray, stride: Int, c: Int) = w0 * a[c] + w1 * a[stride + c] + w2 * a[2 * stride + c] + w3 * a[3 * stride + c]
		fun i(a: IntArray, stride: Int, c: Int) = Math.round(w0 * a[c] + w1 * a[stride + c] + w2 * a[2 * stride + c] + w3 * a[3 * stride + c])
		target.addVertex(f(position, 3, 0), f(position, 3, 1), f(position, 3, 2))
			.setColor(i(color, 4, 0), i(color, 4, 1), i(color, 4, 2), i(color, 4, 3))
			.setUv(f(uv, 2, 0), f(uv, 2, 1))
			.setUv1(overlay[0], overlay[1])
			.setUv2(i(light, 2, 0), i(light, 2, 1))
			.setNormal(f(normal, 3, 0), f(normal, 3, 1), f(normal, 3, 2))
	}
}
