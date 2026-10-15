package net.kernelpanicsoft.relativity.client.lens

import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.PoseStack
import net.kernelpanicsoft.relativity.physics.lens.KerrSlotBaker
import net.kernelpanicsoft.relativity.spacetime.BlackHole
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * The shadow as geometry: the view directions whose light comes from the horizon, outlined on the CPU (each of [COUNT]
 * directions round the hole's bisected between falling in and escaping, through the hole's own metric:
 * KerrSlotBaker.shadowOutline, so a spinning hole's flattened, shifted shadow too), and drawn as a black fan at the
 * hole's distance with the vertex lens off (its directions are already the bent ones). In front of what lies beyond the
 * hole, behind what doesn't. The outline depends only on where the camera is from the hole: worked out in the
 * background, again when the camera has moved.
 */
object HorizonMesh {
	@JvmField
	var enabled = true

	private const val COUNT = 160
	/** The camera may move this fraction of its distance from the hole before the outline is worked out again. */
	private const val MOVE = 0.005

	private val buffers by lazy { MultiBufferSource.immediate(ByteBufferBuilder(1 shl 16)) }
	private val TEXTURE: ResourceLocation = ResourceLocation.withDefaultNamespace("textures/misc/white.png")
	private val worker = Executors.newSingleThreadExecutor { Thread(it, "Relativity shadow outline").apply { isDaemon = true } }

	/** The outline in use (directions, game axes) and for what (camera from the hole; hole mass, spin). */
	private var outline: Array<DoubleArray>? = null
	private var outlineKey: DoubleArray? = null
	private var job: CompletableFuture<Pair<DoubleArray, Array<DoubleArray>>>? = null

	fun draw(hole: BlackHole?, modelView: org.joml.Matrix4f) {
		// (A lone hole: the outline follows its light alone. Among other sources the post pass draws the shadow.)
		if (!enabled || hole == null || !VertexLens.loneHole) return
		val camera = Minecraft.getInstance().gameRenderer.mainCamera.position
		val rx = camera.x - hole.x
		val ry = camera.y - hole.y
		val rz = camera.z - hole.z
		val rc = sqrt(rx * rx + ry * ry + rz * rz)
		val m = hole.mass
		val a = hole.spin * m
		if (rc <= 1.05 * hole.equatorialExtent(hole.horizonRadius)) return
		update(doubleArrayOf(rx, ry, rz, m, a), rc)
		val directions = outline ?: return
		val stack = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack()
		stack.pushMatrix()
		stack.set(modelView)
		com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix()
		VertexLens.ownSecondImages = true
		VertexLens.setEnabled(false)
		try {
			val consumer = buffers.getBuffer(RenderType.beaconBeam(TEXTURE, false))
			val pose = PoseStack().last()
			// A fan of quads from the hole's centre (each a degenerate quad: centre, two outline points, centre).
			val cx = (-rx).toFloat()
			val cy = (-ry).toFloat()
			val cz = (-rz).toFloat()
			for (k in directions.indices) {
				val d0 = directions[k]
				val d1 = directions[(k + 1) % directions.size]
				val quad = listOf(Triple(cx, cy, cz), Triple(d0[0] * rc, d0[1] * rc, d0[2] * rc), Triple(d1[0] * rc, d1[1] * rc, d1[2] * rc), Triple(cx, cy, cz))
				// (Both windings: whichever way the render type culls.)
				for ((x, y, z) in quad + quad.reversed()) {
					consumer.addVertex(pose, x.toFloat(), y.toFloat(), z.toFloat()).setColor(0, 0, 0, 255).setUv(0f, 0f).setLight(0xF000F0).setNormal(0f, 1f, 0f)
				}
			}
			buffers.endBatch()
		} finally {
			VertexLens.setEnabled(true)
			VertexLens.ownSecondImages = false
			stack.popMatrix()
			com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix()
		}
	}

	/** Takes in a finished outline; starts the next if the camera has moved far enough from the last one's place. */
	private fun update(key: DoubleArray, rc: Double) {
		job?.let { j ->
			if (j.isDone) {
				val (k, o) = j.get()
				outlineKey = k
				outline = o
				job = null
			}
		}
		if (job != null) return
		val last = outlineKey
		if (last != null) {
			val moved = sqrt((0..2).sumOf { (key[it] - last[it]) * (key[it] - last[it]) })
			if (moved < MOVE * rc && key[3] == last[3] && key[4] == last[4]) return
		}
		job = CompletableFuture.supplyAsync({
			val baker = KerrSlotBaker(key[3], key[4], doubleArrayOf(key[0], key[1], key[2]), 1.0, 2.0)
			key to baker.shadowOutline(COUNT)
		}, worker)
	}
}
