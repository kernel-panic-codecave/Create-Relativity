package net.kernelpanicsoft.relativity.client.maglev

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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Maglev track's block model: the guideway trough ([MaglevGuideway]) along the block's path, built per block from what
 * runs through it, at any angle: the maglev blocks around (up to [REACH] out: a trough is wider than a block) and the
 * maglev curves near ([MaglevCurves]).
 */
class MaglevTrackModel(private val delegate: BakedModel, private val shape: TrackShape, private val sprite: TextureAtlasSprite) : IDynamicBakedModel {
	override fun getModelData(level: BlockAndTintGetter, pos: BlockPos, state: BlockState, modelData: ModelData): ModelData {
		val others = LinkedHashSet<MaglevGuideway.Line>()
		// Every maglev block's paths around (along this one's line too: those cut nothing), every maglev curve near.
		for (dx in -REACH..REACH) for (dz in -REACH..REACH) others += centreLines(level.getBlockState(pos.offset(dx, 0, dz)), dx, dz)
		for (curve in MaglevCurves.near(intArrayOf(pos.x - 1, pos.z - 1, pos.x + 1, pos.z + 1))) others += MaglevCurves.centreLine(curve, pos)
		return if (others.isEmpty()) modelData else modelData.derive().with(OTHERS, others.toList()).build()
	}

	override fun getQuads(state: BlockState?, side: Direction?, rand: RandomSource, data: ModelData, renderType: RenderType?): List<BakedQuad> {
		if (side != null) return emptyList()
		val others = data.get(OTHERS) ?: emptyList()
		return cache.getOrPut(others) {
			val quads = ArrayList<BakedQuad>()
			for (path in PATHS[shape] ?: emptyList()) MaglevGuideway.build(path.frames(), others, path.rank, sprite, quads)
			quads
		}
	}

	private val cache = ConcurrentHashMap<List<MaglevGuideway.Line>, List<BakedQuad>>()

	override fun useAmbientOcclusion(): Boolean = false
	override fun isGui3d(): Boolean = delegate.isGui3d
	override fun usesBlockLight(): Boolean = delegate.usesBlockLight()
	override fun isCustomRenderer(): Boolean = false
	@Deprecated("Deprecated in Java")
	override fun getParticleIcon(): TextureAtlasSprite = sprite
	override fun getTransforms() = delegate.transforms
	override fun getOverrides() = delegate.overrides
	override fun getRenderTypes(state: BlockState, rand: RandomSource, data: ModelData): ChunkRenderTypeSet = ChunkRenderTypeSet.of(RenderType.solid())

	/** A straight stretch of the track's path in the block (blocks, block-local). */
	private class Path(val x0: Double, val y0: Double, val z0: Double, val x1: Double, val y1: Double, val z1: Double) {
		val dx get() = x1 - x0
		val dz get() = z1 - z0

		/** Its floor's height rank ([MaglevGuideway.build]): one per direction. */
		val rank: Int
			get() = when {
				abs(dx) < 1e-9 -> 0
				abs(dz) < 1e-9 -> 1
				dx * dz > 0 -> 2
				else -> 3
			}

		/** Its cross-sections, kept upright (sheared up slopes), [STEPS] a block. */
		fun frames(): List<MaglevGuideway.Frame> {
			val h = hypot(dx, dz)
			val rx = dz / h
			val rz = -dx / h
			val length = sqrt(dx * dx + (y1 - y0) * (y1 - y0) + dz * dz)
			val steps = (STEPS * length).toInt().coerceAtLeast(1)
			return List(steps + 1) { i ->
				val t = i.toDouble() / steps
				MaglevGuideway.Frame(x0 + dx * t, y0 + (y1 - y0) * t, z0 + dz * t, rx, 0.0, rz, 0.0, 1.0, 0.0, length * t)
			}
		}
	}

	companion object {
		private val OTHERS = ModelProperty<List<MaglevGuideway.Line>>()

		/**
		 * How far (blocks) a block looks for other maglev blocks: where two lines cross at 45° (the sharpest blocks
		 * meet at), the walls in the acute corners meet a trough's half-width / tan 22.5° (3.5 blocks) out.
		 */
		private const val REACH = 4

		/** [state]'s centre lines, seen from above, offset by ([dx], [dz]) (none if it isn't maglev track). */
		fun centreLines(state: BlockState, dx: Int, dz: Int): List<MaglevGuideway.Line> {
			if (!state.`is`(RelativityBlocks.MAGLEV_TRACK.get())) return emptyList()
			return PATHS[state.getValue(TrackBlock.SHAPE)]?.map { MaglevGuideway.Line(listOf(it.x0 + dx, it.z0 + dz, it.x1 + dx, it.z1 + dz)) } ?: emptyList()
		}

		/** Cross-sections a block along a straight path (where it's cut, between them, the cut runs straight). */
		private const val STEPS = 8

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

		/** Maglev track's texture (once models are baked). */
		lateinit var sprite: TextureAtlasSprite
			private set

		/** Swaps maglev track's block models for this one (keeping theirs for the item, particles, and the atlas). */
		fun onModifyBakingResult(event: ModelEvent.ModifyBakingResult) {
			sprite = event.textureGetter.apply(Material(InventoryMenu.BLOCK_ATLAS, CreateRelativity.id("block/maglev_track")))
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
