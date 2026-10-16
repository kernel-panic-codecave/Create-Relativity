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
		// (Round an approximated spinning hole the lens pulls what it draws by the edge onto this same outline: edges.)
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
			if (ring) drawRing(hole, directions, rc, cx, cy, cz)
		} finally {
			VertexLens.setEnabled(true)
			VertexLens.ownSecondImages = false
			stack.popMatrix()
			com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix()
		}
	}

	/** Draws the photon ring round the shadow. */
	@JvmField
	var ring = true

	/**
	 * Its brightness across it, from the shadow's edge outward (fractions of the shadow's radius): light that has wound
	 * round the hole, once and again, packed ever closer to the edge. (It also covers the sliver there the approximated
	 * spinning lens can't follow: vertex_lens_core.glsl relativityKerrApprox.)
	 */
	private val RING_OFFSETS = doubleArrayOf(-0.004, 0.006, 0.018, 0.04)
	private val RING_GLOW = doubleArrayOf(1.0, 0.6, 0.22, 0.0)

	/**
	 * The photon ring: additive bands just outside the shadow's outline ([directions], from the camera; the hole's centre
	 * at (cx, cy, cz) from it, [rc] away), at the hole's distance. Lit by the disk's inner edge when it has one (brighter
	 * where the disk comes toward the camera), else by the sky.
	 */
	private fun drawRing(hole: BlackHole, directions: Array<DoubleArray>, rc: Double, cx: Float, cy: Float, cz: Float) {
		val minecraft = Minecraft.getInstance()
		val level = minecraft.level ?: return
		val e = doubleArrayOf(cx / rc, cy / rc, cz / rc)
		val disk = DiskMesh.drawsDisk(hole)
		val sky = level.getSkyColor(minecraft.gameRenderer.mainCamera.position, minecraft.timer.getGameTimeDeltaPartialTick(false))
		val sense = if (hole.spin < 0.0) -1.0 else 1.0
		// The disk's speed by its inner edge (a fair half of light's), for the beaming.
		val speed = 0.45
		val consumer = buffers.getBuffer(RenderType.lightning())
		val pose = PoseStack().last()
		fun colour(d: DoubleArray): DoubleArray {
			if (!disk) return doubleArrayOf(sky.x * 0.55, sky.y * 0.55, sky.z * 0.55)
			// The side of the hole d lies on (across the line of sight): disk material there moves along side x up for
			// positive spin (DiskMesh's turbulence turns that way); its speed toward the camera (-e) shifts and beams it.
			val along = d[0] * e[0] + d[1] * e[1] + d[2] * e[2]
			val side = DoubleArray(3) { d[it] - along * e[it] }
			val sl = sqrt(side[0] * side[0] + side[1] * side[1] + side[2] * side[2]).coerceAtLeast(1e-9)
			// (side x up = (-side.z, 0, side.x)) . (-e)
			val toward = sense * (side[2] * e[0] - side[0] * e[2]) / sl
			val g = 0.85 * sqrt(1 - speed * speed) / (1 - speed * toward.coerceIn(-1.0, 1.0))
			return DiskMesh.innerGlow(hole, g)
		}
		for (k in directions.indices) {
			val ds = arrayOf(directions[k], directions[(k + 1) % directions.size])
			val colours = ds.map { colour(it) }
			// Each direction pushed out from the hole's by a fraction of its angle (on the plane tangent to the sky at e).
			fun at(d: DoubleArray, offset: Double): DoubleArray {
				val along = d[0] * e[0] + d[1] * e[1] + d[2] * e[2]
				val v = DoubleArray(3) { e[it] + (d[it] / along - e[it]) * (1 + offset) }
				val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
				return DoubleArray(3) { v[it] / n * rc }
			}
			for (band in 0 until RING_OFFSETS.size - 1) {
				val quad = listOf(0 to band, 1 to band, 1 to band + 1, 0 to band + 1)
				for ((which, step) in quad + quad.reversed()) {
					val p = at(ds[which], RING_OFFSETS[step])
					val c = colours[which]
					val glow = RING_GLOW[step]
					fun byte(v: Double) = (v.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
					consumer.addVertex(pose, p[0].toFloat(), p[1].toFloat(), p[2].toFloat()).setColor(byte(c[0]), byte(c[1]), byte(c[2]), byte(glow))
				}
			}
		}
		buffers.endBatch()
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

	/** Azimuths [edges] gives the shadow's edge at. */
	const val EDGE_SAMPLES = 64

	/**
	 * The exact shadow's edge for the lens (vertex_lens_core.glsl relativityShadowEdge): its angle from the hole's
	 * direction (radians) at [EDGE_SAMPLES] azimuths round it, evenly from the projection of up ([frame]'s zero) toward
	 * its quarter; null until an outline is ready for this camera.
	 */
	fun edges(hole: BlackHole, cameraX: Double, cameraY: Double, cameraZ: Double): FloatArray? {
		val rx = cameraX - hole.x
		val ry = cameraY - hole.y
		val rz = cameraZ - hole.z
		val rc = sqrt(rx * rx + ry * ry + rz * rz)
		if (rc <= 1.05 * hole.equatorialExtent(hole.horizonRadius)) return null
		update(doubleArrayOf(rx, ry, rz, hole.mass, hole.spin * hole.mass), rc)
		val impacts = outline ?: return null
		val redshift = sqrt(maxOf(1.0 - 2.0 * hole.mass / rc, 1e-6))
		val n = impacts.size
		return FloatArray(EDGE_SAMPLES) { k ->
			val f = k.toDouble() * n / EDGE_SAMPLES
			val i0 = kotlin.math.floor(f).toInt() % n
			val t = f - kotlin.math.floor(f)
			val impact = (1 - t) * impacts[i0] + t * impacts[(i0 + 1) % n]
			val sine = (kotlin.math.abs(impact) * redshift / rc).coerceIn(0.0, 1.0)
			val angle = kotlin.math.asin(sine)
			(if (impact < 0.0) Math.PI - angle else angle).toFloat()
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
