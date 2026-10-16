package net.kernelpanicsoft.relativity.client.lens

import com.mojang.blaze3d.vertex.VertexConsumer
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder
import net.minecraft.core.SectionPos

/**
 * [QuadSubdivision] on Sodium's chunk meshing (which never goes through vanilla's SectionCompiler): each quad a section
 * near a hole pushes into its mesh (ChunkMeshBufferBuilder.push, via compat mixins) is split the same way and its pieces
 * pushed instead. Sodium's vertex carries ambient occlusion, which a vanilla vertex hasn't: it rides in the overlay
 * coordinate (interpolated like the rest). Translucent quads are left whole: Sodium records each for sorting before it
 * pushes it, and pieces it didn't record would put the two out of step.
 */
object SodiumQuads {
	/** The section being meshed on this thread (its origin, world blocks), set by the meshing task. */
	private val origin = ThreadLocal<IntArray?>()

	/** Set when Sodium records the next quad for translucent sorting: that quad is pushed whole. */
	private val translucentNext = ThreadLocal.withInitial { false }
	private val pushing = ThreadLocal.withInitial { false }
	private val collector = ThreadLocal.withInitial { Collector() }

	@JvmStatic
	fun beginSection(x: Int, y: Int, z: Int) {
		origin.set(intArrayOf(x, y, z))
		translucentNext.set(false)
	}

	@JvmStatic
	fun endSection() = origin.remove()

	@JvmStatic
	fun translucentQuad() = translucentNext.set(true)

	/** Splits [quad] into [builder] if its section is near a hole; whether it did (the push is then skipped). */
	@JvmStatic
	fun push(builder: ChunkMeshBufferBuilder, quad: Array<ChunkVertexEncoder.Vertex>, material: Int): Boolean {
		if (pushing.get()) return false
		if (translucentNext.get()) {
			translucentNext.set(false)
			return false
		}
		val o = origin.get() ?: return false
		val out = collector.get()
		val target = QuadSubdivision.wrap(out, SectionPos.of(o[0] shr 4, o[1] shr 4, o[2] shr 4))
		if (target === out) return false
		out.begin(builder, material)
		pushing.set(true)
		try {
			for (v in quad) {
				val c = v.color
				target.addVertex(v.x, v.y, v.z)
					.setColor(c and 255, (c shr 8) and 255, (c shr 16) and 255, c ushr 24)
					.setUv(v.u, v.v)
					.setUv1(Math.round(v.ao * 65535f), 0)
					.setUv2(v.light and 0xFFFF, v.light ushr 16)
					.setNormal(0f, 1f, 0f)
			}
		} finally {
			pushing.set(false)
		}
		return true
	}

	/** Gathers the pieces' vertices back into Sodium's, four at a time, and pushes each quad. */
	private class Collector : VertexConsumer {
		private lateinit var builder: ChunkMeshBufferBuilder
		private var material = 0
		private val quad = ChunkVertexEncoder.Vertex.uninitializedQuad()
		private var index = 0

		fun begin(builder: ChunkMeshBufferBuilder, material: Int) {
			this.builder = builder
			this.material = material
			index = 0
		}

		override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer {
			quad[index].x = x; quad[index].y = y; quad[index].z = z
			return this
		}

		override fun setColor(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer {
			quad[index].color = (red and 255) or ((green and 255) shl 8) or ((blue and 255) shl 16) or ((alpha and 255) shl 24)
			return this
		}

		override fun setUv(u: Float, v: Float): VertexConsumer {
			quad[index].u = u; quad[index].v = v
			return this
		}

		override fun setUv1(u: Int, v: Int): VertexConsumer {
			quad[index].ao = u / 65535f
			return this
		}

		override fun setUv2(u: Int, v: Int): VertexConsumer {
			quad[index].light = (u and 0xFFFF) or (v shl 16)
			return this
		}

		override fun setNormal(normalX: Float, normalY: Float, normalZ: Float): VertexConsumer {
			index += 1
			if (index == 4) {
				index = 0
				builder.push(quad, material)
			}
			return this
		}
	}
}
