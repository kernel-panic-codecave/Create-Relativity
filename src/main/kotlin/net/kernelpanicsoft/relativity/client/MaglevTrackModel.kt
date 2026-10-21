package net.kernelpanicsoft.relativity.client

import com.simibubi.create.content.trains.track.TrackBlock
import com.simibubi.create.content.trains.track.TrackShape
import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.content.RelativityBlocks
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.block.BlockModelShaper
import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.client.resources.model.Material
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.level.BlockAndTintGetter
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.neoforge.client.ChunkRenderTypeSet
import net.neoforged.neoforge.client.event.ModelEvent
import net.neoforged.neoforge.client.model.IDynamicBakedModel
import net.neoforged.neoforge.client.model.data.ModelData
import net.neoforged.neoforge.client.model.data.ModelProperty
import net.neoforged.neoforge.client.model.pipeline.QuadBakingVertexConsumer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Maglev track's block model: the guideway trough |\_______/| (tools/maglev_track_models.py draws the same profile for
 * the curves' pieces and the fallback block models) swept along the block's path, built per block from its neighbours.
 * A trough is wider than a block, so where two cross, the walls of each (in the crossing and in the blocks either side
 * of it along its line) are cut away inside the other's channel, mitred where they meet: one open floor.
 */
class MaglevTrackModel(private val delegate: BakedModel, private val shape: TrackShape, private val sprite: TextureAtlasSprite) : IDynamicBakedModel {
	/** A straight stretch of the track's path in the block (blocks, block-local). */
	private data class Path(val x0: Double, val y0: Double, val z0: Double, val x1: Double, val y1: Double, val z1: Double) {
		val dx get() = x1 - x0
		val dz get() = z1 - z0
	}

	/** Another track's channel across this block's paths: through (x, z) along unit (dx, dz) (block-local). */
	private data class Channel(val x: Double, val z: Double, val dx: Double, val dz: Double)

	override fun getModelData(level: BlockAndTintGetter, pos: BlockPos, state: BlockState, modelData: ModelData): ModelData {
		val own = PATHS[shape] ?: return modelData
		val channels = ArrayList<Channel>()
		for (dx in -REACH..REACH) for (dz in -REACH..REACH) {
			val there = level.getBlockState(pos.offset(dx, 0, dz))
			if (!there.`is`(RelativityBlocks.MAGLEV_TRACK.get())) continue
			val lines = PATHS[there.getValue(TrackBlock.SHAPE)] ?: continue
			if (lines.size < 2) continue
			// A crossing: each of its lines is a channel across any path of this block's that runs through its centre
			// and not along that line.
			val cx = dx + 0.5
			val cz = dz + 0.5
			for (line in lines) for (path in own) {
				val length = hypot(path.dx, path.dz)
				val offCentre = abs((cx - path.x0) * path.dz - (cz - path.z0) * path.dx) / length
				if (offCentre > 0.05) continue
				val lineLength = hypot(line.dx, line.dz)
				val channel = Channel(line.x0 + dx, line.z0 + dz, line.dx / lineLength, line.dz / lineLength)
				if (abs(channel.dx * path.dz - channel.dz * path.dx) / length < 1e-3) continue
				if (channel !in channels) channels += channel
			}
		}
		return if (channels.isEmpty()) modelData else modelData.derive().with(CHANNELS, channels).build()
	}

	override fun getQuads(state: BlockState?, side: Direction?, rand: RandomSource, data: ModelData, renderType: RenderType?): List<BakedQuad> {
		if (side != null) return emptyList()
		val channels = data.get(CHANNELS) ?: emptyList()
		return cache.getOrPut(channels) { build(channels) }
	}

	private val cache = ConcurrentHashMap<List<Channel>, List<BakedQuad>>()

	private fun build(channels: List<Channel>): List<BakedQuad> {
		val quads = ArrayList<BakedQuad>()
		for (path in PATHS[shape] ?: emptyList()) {
			// (Each direction's floor a hair higher than the next's: where two overlap, no flicker.)
			val lift = FLOOR_LIFT * floorRank(path)
			sweep(quads, path, FLOOR_PART, { 0.0 }, { 1.0 }, lift)
			for (wall in listOf(RIGHT_WALL, LEFT_WALL)) {
				// Each point of the wall's profile cut where it is (its own offset across): the cut ends slant.
				val stretches = wall.associate { (q, _) -> q to kept(path, q.x / 16.0, channels) }
				val count = stretches.values.first().size
				if (stretches.values.any { it.size != count }) continue // (never, for the profile's shape)
				for (i in 0 until count) sweep(quads, path, wall, { stretches.getValue(it)[i].first }, { stretches.getValue(it)[i].second }, 0.0)
			}
		}
		return quads
	}

	/**
	 * The stretches (fractions along [path]) of a line of wall at [offset] across it kept by every channel: where it's at
	 * least as far from the channel's line as from its own (mitred: the two tracks' walls meet along the diagonal
	 * between them, nothing left in either's way).
	 */
	private fun kept(path: Path, offset: Double, channels: List<Channel>): List<Pair<Double, Double>> {
		var stretches = listOf(0.0 to 1.0)
		val length = hypot(path.dx, path.dz)
		val rx = path.dz / length
		val rz = -path.dx / length
		for (c in channels) {
			// The wall's distance across the channel, f(t) = a + b t along the path: cut where |f| < its own offset.
			val ox = path.x0 + rx * offset - c.x
			val oz = path.z0 + rz * offset - c.z
			val a = c.dx * oz - c.dz * ox
			val b = c.dx * path.dz - c.dz * path.dx
			if (abs(b) < 1e-9) continue
			val half = abs(offset)
			val t1 = (-half - a) / b
			val t2 = (half - a) / b
			val lo = minOf(t1, t2)
			val hi = maxOf(t1, t2)
			stretches = stretches.flatMap { (from, to) ->
				listOfNotNull((from to minOf(to, lo)).takeIf { it.second - it.first > 1e-4 }, (maxOf(from, hi) to to).takeIf { it.second - it.first > 1e-4 })
			}
		}
		return stretches
	}

	/**
	 * One part of the profile swept along [path], each of its points from fraction [from] to [to] of it (kept upright:
	 * sheared up slopes), capped at both ends.
	 */
	private fun sweep(quads: MutableList<BakedQuad>, path: Path, part: List<Pair<P, String?>>, from: (P) -> Double, to: (P) -> Double, lift: Double) {
		val h = hypot(path.dx, path.dz)
		val tx = path.dx / h
		val tz = path.dz / h
		val rx = tz
		val rz = -tx
		val length = sqrt(path.dx * path.dx + (path.y1 - path.y0) * (path.y1 - path.y0) + path.dz * path.dz)
		fun u(t: Double) = t * length * 16
		fun at(t: Double, q: P): DoubleArray {
			val bx = path.x0 + path.dx * t
			val by = path.y0 + (path.y1 - path.y0) * t
			val bz = path.z0 + path.dz * t
			val x = q.x / 16.0
			return doubleArrayOf(bx + rx * x, by + q.y / 16.0 + lift, bz + rz * x)
		}
		val pts = part.map { it.first }
		for ((k, entry) in part.withIndex()) {
			val (a, kind) = entry
			if (kind == null) continue
			val b = pts[(k + 1) % pts.size]
			val nx = b.y - a.y
			val ny = -(b.x - a.x)
			val normal = doubleArrayOf(rx * nx, ny, rz * nx)
			val va = bandV(kind, a, b, a)
			val vb = bandV(kind, a, b, b)
			quad(quads, listOf(at(from(a), a), at(from(b), b), at(to(b), b), at(to(a), a)), listOf(u(from(a)) to va, u(from(b)) to vb, u(to(b)) to vb, u(to(a)) to va), normal)
		}
		val (lo, hi) = BANDS.getValue("cap")
		val cuv = pts.map { (it.x + WALL_OUT) / (2 * WALL_OUT) * TEX_W to hi - (it.y - BOTTOM) / (WALL_TOP - BOTTOM) * (hi - lo) }
		for ((t, sign) in listOf(from to -1.0, to to 1.0)) {
			val cap = pts.map { at(t(it), it) }
			// (Its plane's normal, facing out along the path: square across it, or slanted where mitred.)
			val e1 = DoubleArray(3) { cap[1][it] - cap[0][it] }
			val e2 = DoubleArray(3) { cap[2][it] - cap[0][it] }
			val normal = doubleArrayOf(e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0])
			if ((normal[0] * tx + normal[2] * tz) * sign < 0) for (i in 0..2) normal[i] = -normal[i]
			for (k in 1 until pts.size - 1) {
				quad(quads, listOf(cap[0], cap[k], cap[k + 1]), listOf(cuv[0], cuv[k], cuv[k + 1]), normal)
			}
		}
	}

	private fun quad(quads: MutableList<BakedQuad>, corners: List<DoubleArray>, uvs: List<Pair<Double, Double>>, normal: DoubleArray) {
		var points = corners
		var coords = uvs
		val e1 = DoubleArray(3) { points[1][it] - points[0][it] }
		val e2 = DoubleArray(3) { points[2][it] - points[0][it] }
		val gx = e1[1] * e2[2] - e1[2] * e2[1]
		val gy = e1[2] * e2[0] - e1[0] * e2[2]
		val gz = e1[0] * e2[1] - e1[1] * e2[0]
		if (gx * normal[0] + gy * normal[1] + gz * normal[2] < 0) {
			points = points.reversed()
			coords = coords.reversed()
		}
		if (points.size == 3) {
			points = points + listOf(points[2])
			coords = coords + listOf(coords[2])
		}
		val n = sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]).takeIf { it > 0 } ?: 1.0
		val nx = (normal[0] / n).toFloat()
		val ny = (normal[1] / n).toFloat()
		val nz = (normal[2] / n).toFloat()
		val baker = QuadBakingVertexConsumer()
		baker.setSprite(sprite)
		baker.setDirection(Direction.getNearest(nx, ny, nz))
		baker.setShade(true)
		baker.setHasAmbientOcclusion(false)
		for ((p, uv) in points.zip(coords)) {
			baker.addVertex(p[0].toFloat(), p[1].toFloat(), p[2].toFloat())
			baker.setColor(255, 255, 255, 255)
			baker.setUv(sprite.getU((uv.first / TEX_W).toFloat()), sprite.getV((uv.second / TEX_H).toFloat()))
			baker.setNormal(nx, ny, nz)
		}
		quads += baker.bakeQuad()
	}

	override fun useAmbientOcclusion(): Boolean = false
	override fun isGui3d(): Boolean = delegate.isGui3d
	override fun usesBlockLight(): Boolean = delegate.usesBlockLight()
	override fun isCustomRenderer(): Boolean = false
	@Deprecated("Deprecated in Java")
	override fun getParticleIcon(): TextureAtlasSprite = sprite
	override fun getTransforms() = delegate.transforms
	override fun getOverrides() = delegate.overrides
	override fun getRenderTypes(state: BlockState, rand: RandomSource, data: ModelData): ChunkRenderTypeSet = ChunkRenderTypeSet.of(RenderType.solid())

	/** A profile point (pixels: across from the centre line, up from the block's bottom). */
	private data class P(val x: Double, val y: Double)

	companion object {
		private val CHANNELS = ModelProperty<List<Channel>>()

		/**
		 * How far (blocks) a crossing's channel reaches along the lines through it: where two cross at 45°, the walls in
		 * the acute corners meet a trough's half-width / tan 22.5° (3.5 blocks) out.
		 */
		private const val REACH = 4

		// The profile (tools/maglev_track_models.py: the same numbers).
		private const val FLOOR = 15.0
		private const val TOP = 1.0
		private const val BOTTOM = -2.0
		private const val WALL_IN = 21.0
		private const val WALL_OUT = 23.0
		private const val WALL_TOP = 7.0
		private const val WALL_MID = (FLOOR + WALL_OUT) / 2

		private const val FLOOR_LIFT = 0.002

		private const val TEX_W = 32.0
		private const val TEX_H = 64.0
		private val BANDS = mapOf("floor" to (0.0 to 30.0), "slope" to (30.0 to 39.0), "top" to (39.0 to 41.0), "outer" to (41.0 to 50.0), "bottom" to (50.0 to 58.0), "cap" to (58.0 to 64.0))

		// Convex parts, counter-clockwise; a null kind marks an edge inside the profile (between parts), not drawn.
		private val FLOOR_PART = listOf(P(-FLOOR, BOTTOM) to "bottom", P(FLOOR, BOTTOM) to null, P(FLOOR, TOP) to "floor", P(-FLOOR, TOP) to null)
		private val RIGHT_WALL = listOf(P(FLOOR, BOTTOM) to "bottom", P(WALL_OUT, BOTTOM) to "outer", P(WALL_OUT, WALL_TOP) to "top", P(WALL_IN, WALL_TOP) to "slope", P(FLOOR, TOP) to null)
		private val LEFT_WALL = listOf(P(-WALL_OUT, BOTTOM) to "bottom", P(-FLOOR, BOTTOM) to null, P(-FLOOR, TOP) to "slope", P(-WALL_IN, WALL_TOP) to "top", P(-WALL_OUT, WALL_TOP) to "outer")

		private fun bandV(kind: String, a: P, b: P, p: P): Double {
			val (lo, hi) = BANDS.getValue(kind)
			return when (kind) {
				"floor" -> lo + (p.x + FLOOR) / (2 * FLOOR) * (hi - lo)
				"slope", "top", "outer" -> {
					val first = if (kind == "top") (if (abs(a.x) <= abs(b.x)) a else b) else (if (a.y <= b.y) a else b)
					val length = hypot(b.x - a.x, b.y - a.y)
					lo + (if (length > 0) hypot(p.x - first.x, p.y - first.y) / length else 0.0) * (hi - lo)
				}
				else -> lo + (p.x + WALL_OUT) / (2 * WALL_OUT) * (hi - lo)
			}
		}

		private val ZO = Path(0.5, 0.0, 0.0, 0.5, 0.0, 1.0)
		private val XO = Path(0.0, 0.0, 0.5, 1.0, 0.0, 0.5)
		private val PD = Path(0.0, 0.0, 0.0, 1.0, 0.0, 1.0)
		private val ND = Path(1.0, 0.0, 0.0, 0.0, 0.0, 1.0)

		private val PATHS: Map<TrackShape, List<Path>> = mapOf(
			TrackShape.ZO to listOf(ZO), TrackShape.XO to listOf(XO), TrackShape.PD to listOf(PD), TrackShape.ND to listOf(ND),
			TrackShape.AS to listOf(Path(0.5, 0.0, 0.0, 0.5, 1.0, 1.0)), TrackShape.AN to listOf(Path(0.5, 1.0, 0.0, 0.5, 0.0, 1.0)),
			TrackShape.AE to listOf(Path(0.0, 0.0, 0.5, 1.0, 1.0, 0.5)), TrackShape.AW to listOf(Path(0.0, 1.0, 0.5, 1.0, 0.0, 0.5)),
			TrackShape.CR_O to listOf(ZO, XO), TrackShape.CR_D to listOf(PD, ND),
			TrackShape.CR_PDX to listOf(PD, XO), TrackShape.CR_PDZ to listOf(PD, ZO),
			TrackShape.CR_NDX to listOf(ND, XO), TrackShape.CR_NDZ to listOf(ND, ZO),
			TrackShape.TN to listOf(ZO), TrackShape.TS to listOf(ZO), TrackShape.TE to listOf(XO), TrackShape.TW to listOf(XO),
		)

		private fun floorRank(path: Path): Int = when {
			abs(path.dx) < 1e-9 -> 0
			abs(path.dz) < 1e-9 -> 1
			path.dx * path.dz > 0 -> 2
			else -> 3
		}

		/** Swaps maglev track's block models for this one (keeping theirs for the item, particles, and the atlas). */
		fun onModifyBakingResult(event: ModelEvent.ModifyBakingResult) {
			val sprite = event.textureGetter.apply(Material(InventoryMenu.BLOCK_ATLAS, CreateRelativity.id("block/maglev_track")))
			for (state in RelativityBlocks.MAGLEV_TRACK.get().stateDefinition.possibleStates) {
				val shape = state.getValue(TrackBlock.SHAPE)
				if (shape !in PATHS) continue
				val key = BlockModelShaper.stateToModelLocation(state)
				val delegate = event.models[key] ?: continue
				event.models[key] = MaglevTrackModel(delegate, shape, sprite)
			}
		}
	}
}
