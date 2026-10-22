package net.kernelpanicsoft.relativity.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.spacetime.BlackHole
import net.kernelpanicsoft.relativity.spacetime.FieldSource
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.event.ClientTickEvent
import org.joml.Vector3d
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * F3 + Z: a 3D grid round every source showing the flow of space there (the river picture, [SpacetimeField.flow]):
 * each node is moved along the flow (as seen by the player: their own motion taken off), round holes inward and
 * twisted by frame dragging, in a warp bubble or tube along its push, along a Krasnikov tube; and a little toward
 * what pulls (round a mass, which has no flow). Coloured by how fast a clock there runs (at rest, or riding a push).
 * Rebuilt each tick near the camera; drawn with the other gizmos (true positions, over the lensed frame).
 */
object SpacetimeGrid {
	@Volatile
	var shown = false
		private set

	@JvmStatic
	fun toggle(): Boolean {
		shown = !shown
		if (!shown) {
			lattices = emptyList()
			job = null
		}
		return shown
	}

	/** Cells across each source's lattice (along its longest side). */
	private const val CELLS = 8

	/** Sources this far from the camera (blocks) get no lattice; long ones are cut to it. */
	private const val REACH = 64.0

	/** How far a node moves along the flow and toward a pull, at most (of the spacing). */
	private const val FLOW_SHIFT = 0.45
	private const val PULL_SHIFT = 0.35

	/** Nodes worked out each tick: a rebuild is spread over several, to keep the tick short. */
	private const val NODES_PER_TICK = 400

	/**
	 * One source's lattice: [n] nodes a side from [origin] at [spacing], each drawn at [points] (xyz) with clock rate
	 * [rates]; [centre] for the fade toward its edge.
	 */
	private class Lattice(val origin: Vector3d, val spacing: Double, val n: IntArray, val centre: Vector3d, val half: Double) {
		val points = DoubleArray(3 * n[0] * n[1] * n[2])
		val rates = DoubleArray(n[0] * n[1] * n[2])
		fun index(i: Int, j: Int, k: Int) = (i * n[1] + j) * n[2] + k
	}

	/** The lattices drawn, and the rebuild under way (its lattices, and how many nodes are done). */
	@Volatile
	private var lattices: List<Lattice> = emptyList()
	private var job: Pair<List<Lattice>, IntArray>? = null

	/** The box round [source] its lattice covers. */
	private fun regionOf(source: FieldSource): AABB = when (source) {
		is BlackHole -> AABB(source.center, source.center).inflate(max(8.0 * source.schwarzschildRadius, 6.0))
		is FieldSource.Mass -> Vec3(source.center.x(), source.center.y(), source.center.z()).let { AABB(it, it).inflate(max(6.0 * source.softening, 6.0)) }
		is FieldSource.Bubble -> Vec3(source.center.x(), source.center.y(), source.center.z()).let { AABB(it, it).inflate(1.6 * source.radius) }
		is FieldSource.Ellipsoid -> Vec3(source.center.x(), source.center.y(), source.center.z()).let { AABB(it, it).inflate(1.6 * source.axes.maxOf { a -> a.length() }) }
		is FieldSource.Tube -> spineBox(source.spine, source.radius)
		is FieldSource.Krasnikov -> spineBox(source.spine, source.radius)
	}

	private fun spineBox(spine: List<org.joml.Vector3dc>, radius: Double): AABB =
		AABB(spine.minOf { it.x() }, spine.minOf { it.y() }, spine.minOf { it.z() }, spine.maxOf { it.x() }, spine.maxOf { it.y() }, spine.maxOf { it.z() })
			.inflate(2.0 * radius)

	fun onClientTick(event: ClientTickEvent.Post) {
		if (!shown) return
		val minecraft = Minecraft.getInstance()
		val level = minecraft.level ?: return
		val player = minecraft.player ?: return
		val current = job ?: start(level, minecraft.gameRenderer.mainCamera.position)?.also { job = it } ?: run {
			lattices = emptyList()
			return
		}
		val (building, done) = current
		val c = RelativityConfig.speedOfLight(level.dimension())
		// The observer: the player (their motion, in units of c, is taken off the flow).
		val observer = Vector3d(player.deltaMovement.x, player.deltaMovement.y, player.deltaMovement.z).mul(20.0 / c)
		val p = Vector3d()
		val flow = Vector3d()
		val pull = Vector3d()
		var budget = NODES_PER_TICK
		while (budget > 0 && done[0] < building.size) {
			val lattice = building[done[0]]
			val count = lattice.rates.size
			while (budget > 0 && done[1] < count) {
				val node = done[1]
				val k = node % lattice.n[2]
				val j = (node / lattice.n[2]) % lattice.n[1]
				val i = node / (lattice.n[1] * lattice.n[2])
				val s = lattice.spacing
				p.set(lattice.origin.x + i * s, lattice.origin.y + j * s, lattice.origin.z + k * s)
				SpacetimeField.flow(level, p, flow).sub(observer)
				val speed = flow.length()
				val shift = Vector3d()
				if (speed > 1e-9) shift.set(flow).mul(s * FLOW_SHIFT * tanh(speed) / speed)
				SpacetimeField.acceleration(level, p, Vector3d(), pull)
				// (The pull a body at rest feels, geometric (1/blocks), across a spacing.)
				val strength = pull.length() / (c * c) * s
				if (strength > 1e-12) shift.add(pull.mul(s * PULL_SHIFT * tanh(20.0 * strength) / pull.length()))
				lattice.points[3 * node] = p.x + shift.x
				lattice.points[3 * node + 1] = p.y + shift.y
				lattice.points[3 * node + 2] = p.z + shift.z
				lattice.rates[node] = SpacetimeField.properTimeRate(level, p, Vector3d())
				done[1]++
				budget--
			}
			if (done[1] >= count) {
				done[0]++
				done[1] = 0
			}
		}
		if (done[0] >= building.size) {
			lattices = building
			job = null
		}
	}

	/** A rebuild's lattices: one round each source near the camera (cut to [REACH] of it). */
	private fun start(level: net.minecraft.world.level.Level, camera: Vec3): Pair<List<Lattice>, IntArray>? {
		val near = AABB(camera, camera).inflate(REACH)
		val built = ArrayList<Lattice>()
		for (source in SpacetimeField.blackHoles(level) + SpacetimeField.fieldSources(level)) {
			val box = regionOf(source).intersect(near).takeIf { it.xsize > 0 && it.ysize > 0 && it.zsize > 0 } ?: continue
			val s = max(box.xsize, max(box.ysize, box.zsize)) / CELLS
			val n = intArrayOf((box.xsize / s).toInt() + 1, (box.ysize / s).toInt() + 1, (box.zsize / s).toInt() + 1)
			val centre = Vector3d(box.center.x, box.center.y, box.center.z)
			built += Lattice(Vector3d(box.minX, box.minY, box.minZ), s, n, centre, 0.5 * max(box.xsize, max(box.ysize, box.zsize)))
		}
		return if (built.isEmpty()) null else built to IntArray(2)
	}

	/** Whether there's anything to draw. */
	val visible: Boolean get() = shown && lattices.isNotEmpty()

	/** The grid's lines into [lines], camera-relative at [camera] through [pose]. */
	fun draw(lines: VertexConsumer, pose: PoseStack.Pose, camera: Vec3) {
		for (lattice in lattices) {
			val n = lattice.n
			for (i in 0 until n[0]) for (j in 0 until n[1]) for (k in 0 until n[2]) {
				val a = lattice.index(i, j, k)
				if (i + 1 < n[0]) line(lines, pose, lattice, a, lattice.index(i + 1, j, k), camera)
				if (j + 1 < n[1]) line(lines, pose, lattice, a, lattice.index(i, j + 1, k), camera)
				if (k + 1 < n[2]) line(lines, pose, lattice, a, lattice.index(i, j, k + 1), camera)
			}
		}
	}

	private fun line(lines: VertexConsumer, pose: PoseStack.Pose, lattice: Lattice, a: Int, b: Int, camera: Vec3) {
		val pts = lattice.points
		val ax = (pts[3 * a] - camera.x).toFloat()
		val ay = (pts[3 * a + 1] - camera.y).toFloat()
		val az = (pts[3 * a + 2] - camera.z).toFloat()
		val bx = (pts[3 * b] - camera.x).toFloat()
		val by = (pts[3 * b + 1] - camera.y).toFloat()
		val bz = (pts[3 * b + 2] - camera.z).toFloat()
		val nx = bx - ax
		val ny = by - ay
		val nz = bz - az
		val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
		lines.addVertex(pose, ax, ay, az).setColor(color(lattice, a)).setNormal(pose, nx / len, ny / len, nz / len)
		lines.addVertex(pose, bx, by, bz).setColor(color(lattice, b)).setNormal(pose, nx / len, ny / len, nz / len)
	}

	/** Green where clocks run at world time, through yellow to red where they stop; fading toward the lattice's edge. */
	private fun color(lattice: Lattice, node: Int): Int {
		val t = sqrt(1.0 - lattice.rates[node].coerceIn(0.0, 1.0))
		val r = if (t < 0.5) (60 + 390 * t).toInt() else 255
		val g = if (t < 0.5) 255 else (255 - 430 * (t - 0.5)).toInt()
		val b = (90 * (1 - t)).toInt()
		val pts = lattice.points
		val dx = pts[3 * node] - lattice.centre.x
		val dy = pts[3 * node + 1] - lattice.centre.y
		val dz = pts[3 * node + 2] - lattice.centre.z
		val edge = (sqrt(dx * dx + dy * dy + dz * dz) / (lattice.half * sqrt(3.0))).coerceIn(0.0, 1.0)
		val alpha = (255 * (1.0 - 0.6 * edge)).toInt()
		return (alpha shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b
	}
}
