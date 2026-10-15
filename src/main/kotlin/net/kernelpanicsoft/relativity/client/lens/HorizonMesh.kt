package net.kernelpanicsoft.relativity.client.lens

import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.PoseStack
import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.spacetime.BlackHole
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The shadow as geometry: a black sphere just outside the horizon, drawn through the vertex lens. Every path of light
 * that falls in crosses it on the way, so its image is the shadow, edge and shape (a spinning hole's) included, as
 * geometry: in front of what lies beyond, behind what doesn't, shaded by nothing (black).
 */
object HorizonMesh {
	@JvmField
	var enabled = true

	private const val RINGS = 48
	private const val AZIMUTHS = 96
	/** The sphere's radius over the horizon's equatorial one: past the Kerr image map's inner edge (1.05). */
	private const val SCALE = 1.1

	private val buffers by lazy { MultiBufferSource.immediate(ByteBufferBuilder(1 shl 18)) }
	private val TEXTURE: ResourceLocation = ResourceLocation.withDefaultNamespace("textures/misc/white.png")

	fun draw(hole: BlackHole?, modelView: org.joml.Matrix4f) {
		if (!enabled || hole == null) return
		val camera = Minecraft.getInstance().gameRenderer.mainCamera.position
		val hx = hole.x - camera.x
		val hy = hole.y - camera.y
		val hz = hole.z - camera.z
		val radius = SCALE * hole.equatorialExtent(hole.horizonRadius)
		if (hx * hx + hy * hy + hz * hz <= radius * radius) return
		val stack = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack()
		stack.pushMatrix()
		stack.set(modelView)
		com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix()
		// (Its secondary images fall inside the shadow too: not drawn.)
		VertexLens.ownSecondImages = true
		try {
			val consumer = buffers.getBuffer(RenderType.beaconBeam(TEXTURE, false))
			val pose = PoseStack().last()
			for (ring in 0 until RINGS) for (a in 0 until AZIMUTHS) {
				for ((k, j) in arrayOf(ring to a, ring to a + 1, ring + 1 to a + 1, ring + 1 to a)) {
					val theta = PI * k / RINGS
					val phi = 2.0 * PI * j / AZIMUTHS
					consumer.addVertex(pose, (hx + radius * sin(theta) * cos(phi)).toFloat(), (hy + radius * cos(theta)).toFloat(), (hz + radius * sin(theta) * sin(phi)).toFloat())
						.setColor(0, 0, 0, 255)
						.setUv(0f, 0f)
						.setLight(0xF000F0)
						.setNormal(0f, 1f, 0f)
				}
			}
			buffers.endBatch()
		} finally {
			VertexLens.ownSecondImages = false
			stack.popMatrix()
			com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix()
		}
	}
}
