package net.kernelpanicsoft.relativity.client.deform

import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import org.joml.Vector3f

/** Hands out [NoodleConsumer]s that warp everything drawn through them with [warp]. */
class NoodleBuffers(private val delegate: MultiBufferSource, private val warp: NoodleWarp) : MultiBufferSource {
	override fun getBuffer(type: RenderType): VertexConsumer =
		NoodleConsumer(delegate.getBuffer(type), warp, type.mode() == VertexFormat.Mode.QUADS)
}

/**
 * Warps vertices on their way to [delegate]. Model quads (which arrive through the all-attributes [addVertex]) are
 * collected and subdivided so a box can bend into a curve; anything else is warped vertex by vertex.
 */
class NoodleConsumer(
	private val delegate: VertexConsumer,
	private val warp: NoodleWarp,
	private val quads: Boolean,
) : VertexConsumer {
	private class Vertex(
		var x: Float = 0f, var y: Float = 0f, var z: Float = 0f, var color: Int = 0, var u: Float = 0f, var v: Float = 0f,
		var overlay: Int = 0, var light: Int = 0, var nx: Float = 0f, var ny: Float = 0f, var nz: Float = 0f,
	)

	private val pending = Array(4) { Vertex() }
	private var count = 0
	private val position = Vector3f()
	private val normal = Vector3f()

	override fun addVertex(
		x: Float, y: Float, z: Float, color: Int, u: Float, v: Float, packedOverlay: Int, packedLight: Int,
		normalX: Float, normalY: Float, normalZ: Float,
	) {
		if (!quads) {
			emit(x, y, z, color, u, v, packedOverlay, packedLight, normalX, normalY, normalZ)
			return
		}
		pending[count].apply {
			this.x = x; this.y = y; this.z = z; this.color = color; this.u = u; this.v = v
			overlay = packedOverlay; light = packedLight; nx = normalX; ny = normalY; nz = normalZ
		}
		if (++count == 4) {
			count = 0
			subdivide()
		}
	}

	/** Splits the collected quad into n×n bilinear pieces, keeping the winding. */
	private fun subdivide() {
		val n = warp.subdivisions
		val (a, b, c, d) = pending
		for (j in 0 until n) for (i in 0 until n) {
			corner(a, b, c, d, i.toFloat() / n, j.toFloat() / n)
			corner(a, b, c, d, (i + 1f) / n, j.toFloat() / n)
			corner(a, b, c, d, (i + 1f) / n, (j + 1f) / n)
			corner(a, b, c, d, i.toFloat() / n, (j + 1f) / n)
		}
	}

	private fun corner(a: Vertex, b: Vertex, c: Vertex, d: Vertex, s: Float, t: Float) {
		fun mix(p: Float, q: Float, r: Float, w: Float) = (p + (q - p) * s) * (1f - t) + (w + (r - w) * s) * t
		emit(
			mix(a.x, b.x, c.x, d.x), mix(a.y, b.y, c.y, d.y), mix(a.z, b.z, c.z, d.z), a.color,
			mix(a.u, b.u, c.u, d.u), mix(a.v, b.v, c.v, d.v), a.overlay, a.light, a.nx, a.ny, a.nz,
		)
	}

	private fun emit(x: Float, y: Float, z: Float, color: Int, u: Float, v: Float, overlay: Int, light: Int, nx: Float, ny: Float, nz: Float) {
		warp.position(x, y, z, position)
		warp.normal(nx, ny, nz, normal)
		delegate.addVertex(position.x, position.y, position.z, color, u, v, overlay, light, normal.x, normal.y, normal.z)
	}

	// Attribute-by-attribute path (rare for entity models): warp positions, pass the rest through.
	override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer {
		warp.position(x, y, z, position)
		delegate.addVertex(position.x, position.y, position.z)
		return this
	}

	override fun setColor(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer = apply { delegate.setColor(red, green, blue, alpha) }
	override fun setUv(u: Float, v: Float): VertexConsumer = apply { delegate.setUv(u, v) }
	override fun setUv1(u: Int, v: Int): VertexConsumer = apply { delegate.setUv1(u, v) }
	override fun setUv2(u: Int, v: Int): VertexConsumer = apply { delegate.setUv2(u, v) }
	override fun setNormal(normalX: Float, normalY: Float, normalZ: Float): VertexConsumer = apply {
		warp.normal(normalX, normalY, normalZ, normal)
		delegate.setNormal(normal.x, normal.y, normal.z)
	}
}
