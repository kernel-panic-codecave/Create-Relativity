package net.kernelpanicsoft.relativity.client.lens

import net.kernelpanicsoft.relativity.content.KrasnikovTubes
import net.kernelpanicsoft.relativity.content.Maglev
import net.minecraft.client.Minecraft
import net.minecraft.core.SectionPos
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Where Krasnikov tubes' walls can be, for [QuadSubdivision]: a tube runs along Krasnikov track (its centre line the
 * track's, raised: KrasnikovTubes.curve) at [KrasnikovTubes.RADIUS], so a block face across that wall has corners seen
 * through it and not, and drawn whole it's stretched across the wall and the pixel check throws it away. Faces near
 * where a wall can be are split small instead. By the track, not the tubes: they come and go with trains, and meshes
 * rebuilt as trains move would cost far too much; the track only changes when it's laid or broken.
 * The client's track graphs' Krasnikov edges, as segments of their tubes' centre lines, indexed by the chunk sections
 * within reach of their walls; rebuilt when a graph changes (its checksum).
 */
object TubeWalls {
	/** Faces this near a wall (blocks, either side) are split... */
	private const val REACH = 1.5

	/** ...into pieces this long at most (blocks). */
	private const val PIECE = 0.5

	/** A segment's sample spacing along a curve (blocks). */
	private const val STEP = 1.0

	/** Segments (ax, ay, az, bx, by, bz) by the sections within reach of their walls. */
	@Volatile
	private var bySection: Map<Long, List<DoubleArray>> = emptyMap()
	private var checksums: Map<java.util.UUID, Int> = emptyMap()
	private var dimension: Any? = null

	/** Whether section [section] (SectionPos.asLong) has a wall within reach. */
	fun near(section: Long): Boolean = section in bySection

	/**
	 * Pieces the edge from (ax, ay, az) to (bx, by, bz) (world) is split into for the walls: by [PIECE] if it comes within
	 * [REACH] of one (its ends' or middle's distance from a centre line straddling, or near, the radius), else 1.
	 */
	fun pieces(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Int {
		val mx = (ax + bx) * 0.5
		val my = (ay + by) * 0.5
		val mz = (az + bz) * 0.5
		val segments = bySection[SectionPos.asLong(floor(mx).toInt() shr 4, floor(my).toInt() shr 4, floor(mz).toInt() shr 4)] ?: return 1
		val r = KrasnikovTubes.RADIUS
		for (s in segments) {
			val da = distance(s, ax, ay, az)
			val db = distance(s, bx, by, bz)
			val dm = distance(s, mx, my, mz)
			val lo = minOf(da, db, dm)
			val hi = maxOf(da, db, dm)
			if (lo <= r + REACH && hi >= r - REACH) {
				val length = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az))
				return ceil(length / PIECE).toInt().coerceAtLeast(1)
			}
		}
		return 1
	}

	private fun distance(s: DoubleArray, x: Double, y: Double, z: Double): Double {
		val dx = s[3] - s[0]
		val dy = s[4] - s[1]
		val dz = s[5] - s[2]
		val l2 = dx * dx + dy * dy + dz * dz
		val t = if (l2 > 0) (((x - s[0]) * dx + (y - s[1]) * dy + (z - s[2]) * dz) / l2).coerceIn(0.0, 1.0) else 0.0
		val px = s[0] + dx * t - x
		val py = s[1] + dy * t - y
		val pz = s[2] + dz * t - z
		return sqrt(px * px + py * py + pz * pz)
	}

	/** Each client tick: the walls anew if a track graph changed (or the level did); the sections that changed rebuilt. */
	fun tick(minecraft: Minecraft) {
		val level = minecraft.level
		if (level == null) {
			bySection = emptyMap()
			checksums = emptyMap()
			dimension = null
			return
		}
		val graphs = com.simibubi.create.CreateClient.RAILWAYS.trackNetworks.values
		val now = graphs.associate { it.id to it.checksum }
		if (now == checksums && dimension == level.dimension()) return
		checksums = now
		dimension = level.dimension()
		val index = HashMap<Long, MutableList<DoubleArray>>()
		val reach = KrasnikovTubes.RADIUS + REACH
		for (graph in graphs) for (location in graph.nodes) {
			if (location.dimension != level.dimension()) continue
			val node = graph.locateNode(location) ?: continue
			for ((other, edge) in graph.getConnectionsFrom(node) ?: continue) {
				// (Each edge once: from its lower-numbered end.)
				if (node.netId > other.netId || edge.trackMaterial !== Maglev.KRASNIKOV) continue
				val curve = KrasnikovTubes.curve(edge)
				val steps = maxOf(1, ceil(edge.length / STEP).toInt())
				var last = curve.at(0.0)
				for (i in 1..steps) {
					val p = curve.at(i.toDouble() / steps)
					val segment = doubleArrayOf(last.x, last.y, last.z, p.x, p.y, p.z)
					last = p
					val lo = SectionPos.of(floor(minOf(segment[0], segment[3]) - reach).toInt() shr 4, floor(minOf(segment[1], segment[4]) - reach).toInt() shr 4, floor(minOf(segment[2], segment[5]) - reach).toInt() shr 4)
					val hi = SectionPos.of(floor(maxOf(segment[0], segment[3]) + reach).toInt() shr 4, floor(maxOf(segment[1], segment[4]) + reach).toInt() shr 4, floor(maxOf(segment[2], segment[5]) + reach).toInt() shr 4)
					for (sx in lo.x()..hi.x()) for (sy in lo.y()..hi.y()) for (sz in lo.z()..hi.z()) index.getOrPut(SectionPos.asLong(sx, sy, sz)) { ArrayList() } += segment
				}
			}
		}
		val old = bySection
		bySection = index
		val renderer = minecraft.levelRenderer
		for (key in old.keys + index.keys) {
			if (old[key]?.size == index[key]?.size) continue
			try {
				renderer.setSectionDirty(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key))
			} catch (_: NullPointerException) {
			}
		}
	}
}
