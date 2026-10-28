package net.kernelpanicsoft.relativity.client.lens

import com.mojang.blaze3d.vertex.VertexConsumer
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.minecraft.client.Minecraft
import net.minecraft.core.SectionPos
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Finer block quads near black holes, for the vertex lens: it moves each vertex to where its light appears, and the GPU
 * draws straight edges between them, so a block face whose image curves (close to a hole) comes out as a straight-sided
 * slab, and a face with one hidden corner disappears whole. While a chunk section near a hole is built, each quad its
 * blocks and fluids draw is split into n x n (attributes interpolated), n growing toward the hole: [levelAt].
 *
 * Each edge is split as finely as its own image curves (LensCurvature: its ends' and midpoint's images, through the
 * lens tables, against a straight line on screen), so straight edges between its pieces' images stay within a pixel of
 * where the light really appears; elsewhere, however close the camera, a face stays whole. The curve depends on where
 * the camera is, so levels are measured for a snapshot of it ([view]), taken again when it has moved a step
 * ([VIEW_STEP] of its distance to the nearest hole) or the axis tube moves; then only the sections whose levels change
 * by a step (their [signature]) are rebuilt. Close round a hole the near rule ([nearLevel]), and in the tube round the
 * camera-hole axis behind it the axis rule ([axisLevel]), are the least: there the table the measure reads and the
 * shader's own images part ways (by the shadow's edge; by the axis, where the shader places points round the ring its
 * own way), and triangles stretch across the shadow.
 *
 * The tube round the camera-hole axis behind the hole ([polarAxis]): what lies by that axis is imaged all round the
 * Einstein ring, and a triangle that straddles it wraps round the ring (the pixel check throws such a triangle away,
 * leaving a band of the ring empty). Faces there are split round the axis in polar pieces; the few sections the tube
 * passes through are rebuilt as it moves.
 */
object QuadSubdivision {
	/** Quads are split into up to this many per side round the hole... */
	const val MAX_LEVEL = 32

	/** ...n = ceil(NEAR_SCALE / distance from the hole's centre (blocks)), within NEAR_REACH r_s of it. */
	private const val NEAR_SCALE = 48.0
	private const val NEAR_REACH = 8.0

	/** Where the axis tube [a]'s Einstein-ring axis runs through for world point (x, y, z): the hole's centre
	 * (vertex_lens_core.glsl's relativityCausticCentre). */
	@Suppress("UNUSED_PARAMETER")
	fun centreAt(a: DoubleArray, x: Double, y: Double, z: Double): DoubleArray = doubleArrayOf(a[3], a[4], a[5])

	/** Sections within this many r_s of a hole are built through the subdivider (beyond, bending is too slight to curve a face). */
	private const val REACH = 40.0

	/** The camera the levels are measured for, and a pixel's angle; null until there's one. */
	@Volatile
	private var view: LensCurvature.View? = null

	/** The view is taken again once the camera has moved this much of its distance to the nearest hole. */
	private const val VIEW_STEP = 0.15

	/** Each section's level signature as last built or marked ([signature]). */
	private val signatures = HashMap<Long, Int>()

	/** The holes' centres and radii (x, y, z, r_s; world), as the chunk builder threads see them. */
	@Volatile
	private var holes: List<DoubleArray> = emptyList()
	private var applied: List<DoubleArray> = emptyList()

	private val subdivider = ThreadLocal.withInitial { QuadSubdivider() }

	/** Quads split so far (debug). */
	@JvmField
	val split = AtomicLong()

	/**
	 * The axis tube: camera and hole centre (world), the hole's r_s, then its [AXIS_K]-scaled coefficient K (pieces a
	 * side = K x edge / distance from the axis), radius and reach (blocks); null when there's none. The chunk builders
	 * read it.
	 */
	@Volatile
	private var axis: DoubleArray? = null
	private var appliedAxis: DoubleArray? = null

	/**
	 * K = AXIS_K sqrt(θ_E): what a piece must stay under scales with the Einstein ring's angular size θ_E = sqrt(2 r_s / D)
	 * (camera distance D; the ring's arcs curve on its scale). 24 in the ground test (r_s 2, D 15).
	 */
	private const val AXIS_K = 33.0
	/** At most this many pieces a side. */
	const val AXIS_MAX_LEVEL = 32
	/** The tube's far end, AXIS_PIVOT blocks behind the hole, may move this far (blocks) before its sections are rebuilt. */
	private const val AXIS_TOLERANCE = 0.2
	private const val AXIS_PIVOT = 64.0

	/**
	 * Pieces a side for a quad of edge [edge] centred at world point (x, y, z) in the axis tube: small against its
	 * distance from the axis (1 outside the tube).
	 */
	fun axisLevel(x: Double, y: Double, z: Double, edge: Double): Int {
		val a = axis ?: return 1
		val distance = axisDistance(a, x, y, z) ?: return 1
		if (distance > a[8]) return 1
		return ceil(a[7] * edge / maxOf(distance, 1e-3)).toInt().coerceIn(1, AXIS_MAX_LEVEL)
	}

	/**
	 * The axis (camera then hole centre, world) when a face coming within [POLAR_REACH] blocks of it behind the hole is
	 * to be split round it in polar pieces ([QuadSubdivider]); null when there's no tube.
	 */
	fun polarAxis(): DoubleArray? = axis

	/**
	 * Faces this close to the axis (blocks) are split round the point where it crosses their plane: [POLAR_SLICES]
	 * slices of azimuth, rings growing geometrically from [POLAR_INNER] out. A piece by the axis is imaged as an arc of
	 * the Einstein ring as wide as the azimuth it spans round the axis, and a square grid's pieces there span most of
	 * a turn: their straight edges cut inside the ring and leave a thin line of it empty. A polar piece spans 2 pi / 64,
	 * whose chord strays from the ring by R (1 - cos(pi / 64)): about a pixel for a ring of 800 px.
	 */
	const val POLAR_REACH = 0.75

	/** Of the Einstein radius at a point's distance along the axis, how far round the axis it's split polar (at least
	 * [POLAR_REACH]): the region stretched too hard for a grid grows with that radius. vertex_lens_core.glsl's
	 * relativityPolarReach. */
	const val POLAR_EINSTEIN = 0.15

	/** How far round [a]'s axis (blocks) faces are split polar, at [along] blocks from the hole along it (either way). */
	fun polarReach(a: DoubleArray, rc: Double, along: Double): Double {
		val d = kotlin.math.abs(along)
		val einstein = sqrt(2.0 * a[6] * d * rc / (rc + d))
		return maxOf(POLAR_REACH, POLAR_EINSTEIN * einstein)
	}
	const val POLAR_SLICES = 64
	const val POLAR_INNER = 0.004

	/** Distance from world point (x, y, z) to the axis behind the hole (null if not beside that stretch of it). */
	private fun axisDistance(a: DoubleArray, x: Double, y: Double, z: Double): Double? {
		val c = centreAt(a, x, y, z)
		var ux = c[0] - a[0]
		var uy = c[1] - a[1]
		var uz = c[2] - a[2]
		val length = sqrt(ux * ux + uy * uy + uz * uz)
		if (length < 1e-6) return null
		ux /= length; uy /= length; uz /= length
		val dx = x - c[0]
		val dy = y - c[1]
		val dz = z - c[2]
		val t = dx * ux + dy * uy + dz * uz
		// (Both ways: behind the hole, the primary images' Einstein ring; through the camera and on behind it, the
		// secondary images' one.)
		if (t < -(length + a[9]) || t > a[9] + a[8]) return null
		val px = dx - t * ux
		val py = dy - t * uy
		val pz = dz - t * uz
		return sqrt(px * px + py * py + pz * pz)
	}

	/** Section builds read this: on for the level being drawn with lensing. */
	@Volatile
	private var active = false

	/** The near rule round [hole] at world point (x, y, z): where an edge's curve can't be measured (by a shadow's edge). */
	fun nearLevel(hole: DoubleArray, x: Double, y: Double, z: Double): Int {
		val dx = x - hole[0]
		val dy = y - hole[1]
		val dz = z - hole[2]
		val distance = sqrt(dx * dx + dy * dy + dz * dz)
		return if (distance < NEAR_REACH * hole[3]) ceil(NEAR_SCALE / maxOf(distance, 1e-3)).toInt().coerceAtMost(MAX_LEVEL) else 1
	}

	/** Pieces the edge from (ax, ay, az) to (bx, by, bz) (world) is split into, for [view] (the near rule without one). */
	fun edgePieces(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Int {
		val mx = (ax + bx) * 0.5
		val my = (ay + by) * 0.5
		val mz = (az + bz) * 0.5
		val length = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az))
		val near = maxOf(holes.maxOfOrNull { nearLevel(it, mx, my, mz) } ?: 1, axisLevel(mx, my, mz, length), TubeWalls.pieces(ax, ay, az, bx, by, bz))
		val v = view ?: return near
		return maxOf(near, LensCurvature.pieces(v, holes, ax, ay, az, bx, by, bz) { nearLevel(it, mx, my, mz) }).coerceAtMost(MAX_LEVEL)
	}

	/**
	 * A section's levels in steps (0 none split, then 2, 4, 8, 16, 32 a side at most) for [v]: the most a few unit
	 * edges through it ask, along each axis from a 3 x 3 x 3 grid of its points.
	 */
	private fun signature(v: LensCurvature.View, holes: List<DoubleArray>, sx: Int, sy: Int, sz: Int): Int {
		var most = 1
		for (i in 0..2) for (j in 0..2) for (k in 0..2) {
			val x = (sx shl 4) + i * 7.5 + 0.25
			val y = (sy shl 4) + j * 7.5 + 0.25
			val z = (sz shl 4) + k * 7.5 + 0.25
			for (hole in holes) most = maxOf(most, nearLevel(hole, x, y, z))
			most = maxOf(most, LensCurvature.pieces(v, holes, x, y, z, x + 1.0, y, z) { nearLevel(it, x, y, z) })
			most = maxOf(most, LensCurvature.pieces(v, holes, x, y, z, x, y + 1.0, z) { nearLevel(it, x, y, z) })
			most = maxOf(most, LensCurvature.pieces(v, holes, x, y, z, x, y, z + 1.0) { nearLevel(it, x, y, z) })
		}
		return 32 - Integer.numberOfLeadingZeros(minOf(most, MAX_LEVEL) - 1)
	}

	/** The consumer a section's blocks draw into: [target] itself, or a subdivider round it if the section is near a hole. */
	@JvmStatic
	fun wrap(target: VertexConsumer, section: SectionPos): VertexConsumer {
		if (!active) return target
		// (By a Krasnikov tube's wall: split where it can be.)
		if (TubeWalls.near(section.asLong())) return subdivider.get().also { it.begin(target, section.origin().x, section.origin().y, section.origin().z) }
		if (holes.isEmpty()) return target
		val origin = section.origin()
		// Within [REACH] of a hole (its nearest point), anything in it may be split.
		var near = false
		for (hole in holes) {
			val nx = hole[0].coerceIn(origin.x.toDouble(), origin.x + 16.0) - hole[0]
			val ny = hole[1].coerceIn(origin.y.toDouble(), origin.y + 16.0) - hole[1]
			val nz = hole[2].coerceIn(origin.z.toDouble(), origin.z + 16.0) - hole[2]
			if (nx * nx + ny * ny + nz * nz < REACH * REACH * hole[3] * hole[3]) near = true
		}
		// (Or the axis tube passes within the section: its centre within the tube plus the section's half-diagonal.)
		val a = axis
		if (!near && a != null) {
			val distance = axisDistance(a, origin.x + 8.0, origin.y + 8.0, origin.z + 8.0)
			if (distance != null && distance < a[8] + 13.9) near = true
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
		if (active) TubeWalls.tick(minecraft)
		val axisMoved = updateAxis(minecraft, now)
		applyPending(minecraft)
		updateView(minecraft, now, axisMoved)
		// (Turning the vertex lens on or off empties or fills the list, which counts as a change too.)
		if (!changed(now)) return
		holes = now
		signatures.clear()
		val renderer = minecraft.levelRenderer
		for (hole in applied + now) {
			// Sections within reach of the subdivider.
			val reach = REACH * hole[3] + 1.0
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

	/**
	 * The tube round the axis from the camera through the nearest hole: when its far end has moved more than
	 * [AXIS_TOLERANCE], the sections the old and the new tube pass through are rebuilt.
	 */
	private fun updateAxis(minecraft: Minecraft, holesNow: List<DoubleArray>): Boolean {
		val camera = minecraft.gameRenderer.mainCamera.position
		val hole = holesNow.minByOrNull { (it[0] - camera.x) * (it[0] - camera.x) + (it[1] - camera.y) * (it[1] - camera.y) + (it[2] - camera.z) * (it[2] - camera.z) }
		val now = hole?.let {
			val distance = maxOf(sqrt((it[0] - camera.x) * (it[0] - camera.x) + (it[1] - camera.y) * (it[1] - camera.y) + (it[2] - camera.z) * (it[2] - camera.z)), it[3])
			val k = AXIS_K * sqrt(sqrt(minOf(2.0 * it[3] / distance, 1.0)))
			// Out to where n falls to 2, and to the render distance behind the hole.
			val reach = minecraft.options.effectiveRenderDistance * 16.0 + 16.0
			doubleArrayOf(camera.x, camera.y, camera.z, it[0], it[1], it[2], it[3], k, 0.5 * k, reach)
		}
		val old = appliedAxis
		if (now == null && old == null) return false
		if (now != null && old != null && (now[7] - old[7]).let { it * it } < 0.01 * old[7] * old[7] && (now[9] == old[9]) && farEnd(now).zip(farEnd(old)).sumOf { (p, q) -> (p - q) * (p - q) } < AXIS_TOLERANCE * AXIS_TOLERANCE &&
			(0..2).sumOf { (now[3 + it] - old[3 + it]).let { d -> d * d } } < AXIS_TOLERANCE * AXIS_TOLERANCE) return false
		axis = now
		appliedAxis = now
		val renderer = minecraft.levelRenderer
		val dirty = HashSet<Long>()
		for (a in listOfNotNull(old, now)) {
			// Sections along the tube, from past the camera (behind it, the secondary images' axis) to the far end behind
			// the hole: every few blocks along it, those within its radius.
			val far = farEnd(a, a[9])
			val back = DoubleArray(3) { a[3 + it] + (a[3 + it] - far[it]) / a[9] * (sqrt((0..2).sumOf { k -> (a[3 + k] - a[k]) * (a[3 + k] - a[k]) }) + a[9]) }
			val span = sqrt((0..2).sumOf { (far[it] - back[it]) * (far[it] - back[it]) })
			val steps = ceil(span / 4.0).toInt()
			val across = ceil(a[8] / 8.0).toInt()
			for (k in 0..steps) {
				val f = k.toDouble() / steps
				val x = back[0] + f * (far[0] - back[0])
				val y = back[1] + f * (far[1] - back[1])
				val z = back[2] + f * (far[2] - back[2])
				for (dx in -across..across) for (dy in -across..across) for (dz in -across..across) {
					val r = a[8] / across
					dirty += SectionPos.asLong(floor(x + dx * r).toInt() shr 4, floor(y + dy * r).toInt() shr 4, floor(z + dz * r).toInt() shr 4)
				}
			}
		}
		try {
			for (section in dirty) renderer.setSectionDirty(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section))
		} catch (_: NullPointerException) {
		}
		return true
	}

	/**
	 * The camera snapshot the levels are measured for: taken again once the camera has moved [VIEW_STEP] of its
	 * distance to the nearest hole, or with the axis tube ([axisMoved]); then each section within [REACH] whose levels'
	 * [signature] changes is rebuilt.
	 */
	private fun updateView(minecraft: Minecraft, holesNow: List<DoubleArray>, axisMoved: Boolean) {
		if (holesNow.isEmpty() || VertexLens.primaryImages == null) {
			view = null
			return
		}
		val camera = minecraft.gameRenderer.mainCamera.position
		val nearest = holesNow.minOf { sqrt((it[0] - camera.x) * (it[0] - camera.x) + (it[1] - camera.y) * (it[1] - camera.y) + (it[2] - camera.z) * (it[2] - camera.z)) }
		val old = view
		val pixel = 2.0 * kotlin.math.atan(1.0 / VertexLens.lastProjection.m11().toDouble().coerceAtLeast(1e-3)) / maxOf(minecraft.window.height, 1)
		if (old != null && !axisMoved && holes === holesNow &&
			sqrt((old.x - camera.x) * (old.x - camera.x) + (old.y - camera.y) * (old.y - camera.y) + (old.z - camera.z) * (old.z - camera.z)) < VIEW_STEP * nearest &&
			kotlin.math.abs(old.pixel - pixel) < 0.1 * old.pixel) return
		val now = LensCurvature.View(camera.x, camera.y, camera.z, pixel)
		view = now
		// (One job at a time: a newer view waits for the running one, then is checked in turn.)
		if (pending != null) {
			queued = holesNow
			return
		}
		startSignatures(minecraft, now, holesNow)
	}

	/** Holes for a view taken while signatures were being worked out: checked once those are done. */
	private var queued: List<DoubleArray>? = null

	private fun startSignatures(minecraft: Minecraft, now: LensCurvature.View, holesNow: List<DoubleArray>) {
		// (The signatures are worked out off the client thread: hundreds of sections, a few hundred images each.)
		val minY = minecraft.level?.minSection ?: -4
		val maxY = (minecraft.level?.maxSection ?: 20) - 1
		pending = java.util.concurrent.CompletableFuture.supplyAsync {
			val result = HashMap<Long, Int>()
			for (hole in holesNow) {
				val reach = REACH * hole[3]
				val lo = SectionPos.of(floor(hole[0] - reach).toInt() shr 4, floor(hole[1] - reach).toInt() shr 4, floor(hole[2] - reach).toInt() shr 4)
				val hi = SectionPos.of(floor(hole[0] + reach).toInt() shr 4, floor(hole[1] + reach).toInt() shr 4, floor(hole[2] + reach).toInt() shr 4)
				for (sx in lo.x()..hi.x()) for (sy in maxOf(lo.y(), minY)..minOf(hi.y(), maxY)) for (sz in lo.z()..hi.z()) {
					val key = SectionPos.asLong(sx, sy, sz)
					if (key !in result) result[key] = signature(now, holesNow, sx, sy, sz)
				}
			}
			result
		}
	}

	/** The signatures being worked out for the latest view (the sections whose levels change are rebuilt when it's done). */
	private var pending: java.util.concurrent.CompletableFuture<HashMap<Long, Int>>? = null

	private fun applyPending(minecraft: Minecraft) {
		val job = pending ?: return
		if (!job.isDone) return
		pending = null
		queued?.let { holesNow ->
			queued = null
			view?.let { startSignatures(minecraft, it, holesNow) }
		}
		val result = try { job.get() } catch (_: Exception) { return }
		val renderer = minecraft.levelRenderer
		for ((key, signature) in result) {
			if (signatures.put(key, signature) == signature) continue
			try {
				renderer.setSectionDirty(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key))
			} catch (_: NullPointerException) {
			}
		}
	}

	/** The point [reach] blocks behind the hole along the axis from the camera (by default the pivot the tolerance is
	 * measured at). */
	private fun farEnd(a: DoubleArray, reach: Double = AXIS_PIVOT): DoubleArray {
		val ux = a[3] - a[0]
		val uy = a[4] - a[1]
		val uz = a[5] - a[2]
		val length = maxOf(sqrt(ux * ux + uy * uy + uz * uz), 1e-6)
		return doubleArrayOf(a[3] + ux / length * reach, a[4] + uy / length * reach, a[5] + uz / length * reach)
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
		if (polar()) return
		val cx = (position[0] + position[3] + position[6] + position[9]) / 4.0
		val cy = (position[1] + position[4] + position[7] + position[10]) / 4.0
		val cz = (position[2] + position[5] + position[8] + position[11]) / 4.0
		// Each edge's pieces from its own midpoint and length, which the face across it shares: both split it alike, so
		// no vertex of one lies partway along an edge of the other (a T-junction: once bent, the edges part and leave a
		// crack of sky). Edges in (s, t) order: s = 0 (v0-v1), t = 1 (v1-v2), s = 1 (v3-v2), t = 0 (v0-v3).
		val edges = edgeLevels()
		// (Inside, by its diagonals: a face can curve across though its edges don't.)
		val n = maxOf(edges.max(), pieces(0, 2), pieces(1, 3))
		if (n <= 1) {
			for (k in 0..3) put(k)
			return
		}
		QuadSubdivision.split.incrementAndGet()
		// The grid's points on the quad's edges snapped to the edge's own pieces (a few cells beside a coarser edge thin
		// to slivers or nothing; none fold over, the snapping keeping their order).
		fun s(i: Int, j: Int): Float = when {
			j == n -> snap(i, n, edges[1])
			j == 0 -> snap(i, n, edges[3])
			else -> i.toFloat() / n
		}
		fun t(i: Int, j: Int): Float = when {
			i == 0 -> snap(j, n, edges[0])
			i == n -> snap(j, n, edges[2])
			else -> j.toFloat() / n
		}
		// Bilinear over the quad: P(s, t) = (1-s)(1-t) v0 + (1-s) t v1 + s t v2 + s (1-t) v3, each sub-quad's corners in
		// the same order, so it keeps the quad's winding.
		for (i in 0 until n) for (j in 0 until n) {
			put(s(i, j), t(i, j))
			put(s(i, j + 1), t(i, j + 1))
			put(s(i + 1, j + 1), t(i + 1, j + 1))
			put(s(i + 1, j), t(i + 1, j))
		}
	}

	private val ends = arrayOf(intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(3, 2), intArrayOf(0, 3))

	private fun edgeLength(e: Int): Double {
		val p = ends[e][0] * 3
		val q = ends[e][1] * 3
		val ex = (position[p] - position[q]).toDouble()
		val ey = (position[p + 1] - position[q + 1]).toDouble()
		val ez = (position[p + 2] - position[q + 2]).toDouble()
		return sqrt(ex * ex + ey * ey + ez * ez)
	}

	/** Each edge's pieces, from its own ends (the same for the face across it). */
	private fun edgeLevels(): IntArray = IntArray(4) { e -> pieces(ends[e][0], ends[e][1]) }

	/** Pieces for the segment between corners [p] and [q] (QuadSubdivision.edgePieces). */
	private fun pieces(p: Int, q: Int): Int = QuadSubdivision.edgePieces(
		originX + position[p * 3], originY + position[p * 3 + 1], originZ + position[p * 3 + 2],
		originX + position[q * 3], originY + position[q * 3 + 1], originZ + position[q * 3 + 2],
	)

	/** Grid point [k] of [n] along an edge of [pieces] pieces: the nearest of its points. */
	private fun snap(k: Int, n: Int, pieces: Int): Float = Math.round(k.toDouble() * pieces / n).toFloat() / pieces

	/**
	 * Splits the quad round the point where the axis behind the hole crosses its plane, if it comes within
	 * [QuadSubdivision.POLAR_REACH] of it ([QuadSubdivision.POLAR_SLICES]): polar cells, each clipped to the quad.
	 * Whether it did.
	 */
	private fun polar(): Boolean {
		val a = QuadSubdivision.polarAxis() ?: return false
		// (The axis through the centre the quad's own points are lensed round: QuadSubdivision.centreAt.)
		val c = QuadSubdivision.centreAt(a, originX + (position[0] + position[3] + position[6] + position[9]) / 4.0,
			originY + (position[1] + position[4] + position[7] + position[10]) / 4.0, originZ + (position[2] + position[5] + position[8] + position[11]) / 4.0)
		var ux = c[0] - a[0]
		var uy = c[1] - a[1]
		var uz = c[2] - a[2]
		val length = sqrt(ux * ux + uy * uy + uz * uz)
		if (length < 1e-6) return false
		ux /= length; uy /= length; uz /= length
		// Across the axis: two directions perpendicular to it, and each corner's place in that plane (the axis at 0).
		val (bx, by, bz) = if (kotlin.math.abs(uy) < 0.9) Triple(0.0, 1.0, 0.0) else Triple(1.0, 0.0, 0.0)
		var ax = by * uz - bz * uy
		var ay = bz * ux - bx * uz
		var az = bx * uy - by * ux
		val al = sqrt(ax * ax + ay * ay + az * az)
		ax /= al; ay /= al; az /= al
		val cx = uy * az - uz * ay
		val cy = uz * ax - ux * az
		val cz = ux * ay - uy * ax
		val across = DoubleArray(8)
		var behind = 0.0
		for (k in 0..3) {
			val dx = originX + position[k * 3] - c[0]
			val dy = originY + position[k * 3 + 1] - c[1]
			val dz = originZ + position[k * 3 + 2] - c[2]
			behind += (dx * ux + dy * uy + dz * uz) / 4.0
			across[k * 2] = dx * ax + dy * ay + dz * az
			across[k * 2 + 1] = dx * cx + dy * cy + dz * cz
		}
		// (Behind the hole or on the camera's side alike: the secondary images' Einstein ring is round the axis there.)
		// (s, t) of a point across the axis: P(s, t) = v0 + s (v3 - v0) + t (v1 - v0), seen along the axis.
		val sx = across[6] - across[0]
		val sy = across[7] - across[1]
		val tx = across[2] - across[0]
		val ty = across[3] - across[1]
		val det = sx * ty - sy * tx
		val area = kotlin.math.abs(det)
		// (A face nearly along the axis: its pieces wouldn't be small round it anyway.)
		if (area < 1e-4 * (sx * sx + sy * sy + tx * tx + ty * ty)) return false
		val reach = QuadSubdivision.polarReach(a, length, behind)
		if (distanceToOrigin(across) > reach) return false
		// Edges whose face across (the next face in the plane) is split on a grid, not round the axis: the cells'
		// points on them go to that grid's ([edgeLevels]), so the two meet without T-junctions. (Between two faces split
		// round the axis the cells' points on the shared edge are the same already.)
		val levels = edgeLevels()
		val shifts = arrayOf(doubleArrayOf(-sx, -sy), doubleArrayOf(tx, ty), doubleArrayOf(sx, sy), doubleArrayOf(-tx, -ty))
		for (e in 0..3) {
			val shifted = DoubleArray(8) { across[it] + shifts[e][it % 2] }
			snapEdges[e] = if (distanceToOrigin(shifted) > reach) levels[e] else 0
		}
		var outer = 0.0
		for (k in 0..3) outer = maxOf(outer, sqrt(across[k * 2] * across[k * 2] + across[k * 2 + 1] * across[k * 2 + 1]))
		QuadSubdivision.split.incrementAndGet()
		val slices = QuadSubdivision.POLAR_SLICES
		val growth = 1.0 + 2.0 * Math.PI / slices
		fun st(r: Double, angle: Double): DoubleArray {
			val qx = r * kotlin.math.cos(angle) - across[0]
			val qy = r * kotlin.math.sin(angle) - across[1]
			return doubleArrayOf((qx * ty - qy * tx) / det, (sx * qy - sy * qx) / det)
		}
		val cell = ArrayList<DoubleArray>(4)
		// The disc inside the first ring, then the rings.
		cell.clear()
		for (j in 0 until slices) cell += st(QuadSubdivision.POLAR_INNER, 2.0 * Math.PI * j / slices)
		emitClipped(cell)
		var r = QuadSubdivision.POLAR_INNER
		var rings = 0
		while (r < outer && rings < 400) {
			val next = r * growth
			for (j in 0 until slices) {
				val a0 = 2.0 * Math.PI * j / slices
				val a1 = 2.0 * Math.PI * (j + 1) / slices
				cell.clear()
				cell += st(r, a0); cell += st(r, a1); cell += st(next, a1); cell += st(next, a0)
				emitClipped(cell)
			}
			r = next
			rings += 1
		}
		return true
	}

	/** Distance from the origin to the quad with corners [across] (x, y pairs; 0 if inside it). */
	private fun distanceToOrigin(across: DoubleArray): Double {
		var inside = true
		var sign = 0.0
		var best = Double.MAX_VALUE
		for (k in 0..3) {
			val px = across[k * 2]
			val py = across[k * 2 + 1]
			val qx = across[((k + 1) % 4) * 2]
			val qy = across[((k + 1) % 4) * 2 + 1]
			val cross = px * qy - py * qx
			if (sign == 0.0) sign = cross else if (cross * sign < 0.0) inside = false
			val ex = qx - px
			val ey = qy - py
			val f = (-(px * ex + py * ey) / maxOf(ex * ex + ey * ey, 1e-12)).coerceIn(0.0, 1.0)
			val dx = px + f * ex
			val dy = py + f * ey
			best = minOf(best, sqrt(dx * dx + dy * dy))
		}
		return if (inside) 0.0 else best
	}

	/** While a face is split round the axis: each edge's grid pieces to snap its points to (0: none). */
	private val snapEdges = IntArray(4)

	/** The polygon [points] ((s, t)) clipped to the quad, s and t in [0, 1], drawn as a fan of quads in its winding. */
	private fun emitClipped(points: List<DoubleArray>) {
		var minS = Double.MAX_VALUE; var maxS = -Double.MAX_VALUE; var minT = Double.MAX_VALUE; var maxT = -Double.MAX_VALUE
		for (p in points) { minS = minOf(minS, p[0]); maxS = maxOf(maxS, p[0]); minT = minOf(minT, p[1]); maxT = maxOf(maxT, p[1]) }
		if (maxS <= 0.0 || minS >= 1.0 || maxT <= 0.0 || minT >= 1.0) return
		var poly = points
		for (edge in 0..3) {
			val axisIndex = edge / 2
			val keepBelow = edge % 2 == 1
			val bound = if (keepBelow) 1.0 else 0.0
			fun inside(p: DoubleArray) = if (keepBelow) p[axisIndex] <= bound else p[axisIndex] >= bound
			val out = ArrayList<DoubleArray>(poly.size + 2)
			for (k in poly.indices) {
				val p = poly[k]
				val q = poly[(k + 1) % poly.size]
				val pin = inside(p)
				val qin = inside(q)
				if (pin) out += p
				if (pin != qin) {
					val f = (bound - p[axisIndex]) / (q[axisIndex] - p[axisIndex])
					val point = doubleArrayOf(p[0] + f * (q[0] - p[0]), p[1] + f * (q[1] - p[1]))
					point[axisIndex] = bound
					out += point
				}
			}
			poly = out
			if (poly.size < 3) return
		}
		// Points on an edge to snap, to that edge's grid.
		poly = poly.map { p ->
			fun snapped(x: Double, pieces: Int) = if (pieces > 0) Math.round(x * pieces).toDouble() / pieces else x
			when {
				p[0] == 0.0 -> doubleArrayOf(0.0, snapped(p[1], snapEdges[0]))
				p[0] == 1.0 -> doubleArrayOf(1.0, snapped(p[1], snapEdges[2]))
				p[1] == 1.0 -> doubleArrayOf(snapped(p[0], snapEdges[1]), 1.0)
				p[1] == 0.0 -> doubleArrayOf(snapped(p[0], snapEdges[3]), 0.0)
				else -> p
			}
		}
		// The quad's own corners, (0, 0), (0, 1), (1, 1), (1, 0), run clockwise in (s, t): the same here.
		var twice = 0.0
		for (k in poly.indices) {
			val p = poly[k]
			val q = poly[(k + 1) % poly.size]
			twice += p[0] * q[1] - q[0] * p[1]
		}
		if (kotlin.math.abs(twice) < 1e-14) return
		val ordered = if (twice > 0.0) poly.reversed() else poly
		for (k in 1 until ordered.size - 1) {
			put(ordered[0][0].toFloat(), ordered[0][1].toFloat())
			put(ordered[k][0].toFloat(), ordered[k][1].toFloat())
			put(ordered[k + 1][0].toFloat(), ordered[k + 1][1].toFloat())
			put(ordered[k + 1][0].toFloat(), ordered[k + 1][1].toFloat())
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
			.setUv1(i(overlay, 2, 0), i(overlay, 2, 1)) // (constant on a vanilla quad; Sodium's ambient occlusion: SodiumQuads)
			.setUv2(i(light, 2, 0), i(light, 2, 1))
			.setNormal(f(normal, 3, 0), f(normal, 3, 1), f(normal, 3, 2))
	}
}
