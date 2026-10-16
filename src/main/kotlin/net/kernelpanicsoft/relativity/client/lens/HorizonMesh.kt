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
// (Meanwhile the outline follows the camera every frame: kept as each edge ray's impact parameter at its angle round the
// camera-hole line, spin axis up, which turning round the hole doesn't change at all and moving to or from it barely
// does, and put back in view directions for where the camera is now. Negative: past a right angle from the hole.)
object HorizonMesh {
	@JvmField
	var enabled = true

	private const val COUNT = 160
	/** The camera may move this fraction of its distance from the hole before the outline is worked out again. */
	private const val MOVE = 0.005

	private val buffers by lazy { MultiBufferSource.immediate(ByteBufferBuilder(1 shl 16)) }
	private val TEXTURE: ResourceLocation = ResourceLocation.withDefaultNamespace("textures/misc/white.png")
	private val worker = Executors.newSingleThreadExecutor { Thread(it, "Relativity shadow outline").apply { isDaemon = true } }

	/** The outline in use (each edge ray's impact parameter, blocks, at angle 2 pi k / COUNT round the camera-hole line)
	 * and for what (camera from the hole; hole mass, spin). */
	private var outline: DoubleArray? = null
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
		val impacts = outline ?: return
		val directions = directions(impacts, doubleArrayOf(rx, ry, rz), rc, m)
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

	/** The camera-hole line from the camera (unit) and the two directions round it the outline's angles start from:
	 * KerrSlotBaker.shadowOutline's frame. */
	private fun frame(camera: DoubleArray, rc: Double): Triple<DoubleArray, DoubleArray, DoubleArray> {
		val axis = DoubleArray(3) { -camera[it] / rc }
		val up = if (kotlin.math.abs(axis[1]) < 0.99) doubleArrayOf(0.0, 1.0, 0.0) else doubleArrayOf(1.0, 0.0, 0.0)
		val dot = up[0] * axis[0] + up[1] * axis[1] + up[2] * axis[2]
		val z = DoubleArray(3) { up[it] - dot * axis[it] }
		val n = sqrt(z[0] * z[0] + z[1] * z[1] + z[2] * z[2])
		val zero = DoubleArray(3) { z[it] / n }
		val quarter = doubleArrayOf(axis[1] * zero[2] - axis[2] * zero[1], axis[2] * zero[0] - axis[0] * zero[2], axis[0] * zero[1] - axis[1] * zero[0])
		return Triple(axis, zero, quarter)
	}

	/** The outline's view directions (game axes) for the camera at [camera] from the hole (distance [rc]). */
	private fun directions(impacts: DoubleArray, camera: DoubleArray, rc: Double, m: Double): Array<DoubleArray> {
		val (axis, zero, quarter) = frame(camera, rc)
		val redshift = sqrt(maxOf(1.0 - 2.0 * m / rc, 1e-6))
		return Array(impacts.size) { k ->
			val azimuth = 2.0 * Math.PI * k / impacts.size
			val sine = (kotlin.math.abs(impacts[k]) * redshift / rc).coerceIn(0.0, 1.0)
			val cosine = sqrt(1.0 - sine * sine) * (if (impacts[k] < 0.0) -1.0 else 1.0)
			DoubleArray(3) { cosine * axis[it] + sine * (kotlin.math.cos(azimuth) * zero[it] + kotlin.math.sin(azimuth) * quarter[it]) }
		}
	}

	/** Takes in a finished outline; starts the next if the camera has moved far enough from the last one's place. */
	private fun update(key: DoubleArray, rc: Double) {
		job?.let { j ->
			if (j.isDone) {
				val (k, o) = j.get()
				outlineKey = k
				// Directions to impact parameters, in the frame of the camera the outline was worked out for.
				val rcThen = sqrt(k[0] * k[0] + k[1] * k[1] + k[2] * k[2])
				val (axis, _, _) = frame(doubleArrayOf(k[0], k[1], k[2]), rcThen)
				val redshift = sqrt(maxOf(1.0 - 2.0 * k[3] / rcThen, 1e-6))
				outline = DoubleArray(o.size) { n ->
					val d = o[n]
					val cosine = (d[0] * axis[0] + d[1] * axis[1] + d[2] * axis[2]).coerceIn(-1.0, 1.0)
					val b = rcThen * sqrt(1.0 - cosine * cosine) / redshift
					if (cosine < 0.0) -b else b
				}
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
