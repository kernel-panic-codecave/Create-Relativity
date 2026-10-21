package net.kernelpanicsoft.relativity.client.maglev

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.simibubi.create.content.trains.track.BezierConnection
import com.simibubi.create.content.trains.track.ITrackBlock
import com.simibubi.create.content.trains.track.TrackBlockEntity
import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.api.instance.InstancerProvider
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.model.baked.BakedModelBuilder
import net.kernelpanicsoft.relativity.content.Maglev
import net.kernelpanicsoft.relativity.content.RelativityBlocks
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.client.renderer.block.model.ItemOverrides
import net.minecraft.client.renderer.block.model.ItemTransforms
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.ChunkRenderTypeSet
import net.neoforged.neoforge.client.model.IDynamicBakedModel
import net.neoforged.neoforge.client.model.data.ModelData
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Maglev track's curves: the guideway trough ([MaglevGuideway]) swept along each, one surface (no pieces stretched
 * between rail points, so nothing opens on the outside of a bend), cut where it runs through the track at its ends (a
 * junction: the straight going on, other curves leaving the same blocks). Drawn as Flywheel instances in Create's
 * track visual (TrackVisualMixin), or straight into the buffer where Flywheel is off (TrackRendererMixin).
 */
object MaglevCurves {
	/** How far up from a curve's points (Create's) the profile's origin is, so curves meet the blocks flush. */
	private const val DROP = 0.197

	/** How far along the straight at an end (blocks) a curve looks for track to be cut by. */
	private const val ALONG = 16

	/** A curve's floor's height rank ([MaglevGuideway.build]): above every straight direction's. */
	private const val RANK = 4

	@JvmStatic
	fun isMaglev(curve: BezierConnection): Boolean = curve.material === Maglev.MATERIAL

	/** [curve]'s centre line, seen from above, relative to [origin]. (Its points are relative to its first block.) */
	fun centreLine(curve: BezierConnection, origin: BlockPos): MaglevGuideway.Line {
		val first = curve.bePositions.first
		val points = ArrayList<Double>()
		for (segment in curve) {
			points += segment.position.x + first.x - origin.x
			points += segment.position.z + first.z - origin.z
		}
		return MaglevGuideway.Line(points)
	}

	/** [curve]'s cross-sections, relative to its first block (as its points are). */
	private fun frames(curve: BezierConnection): List<MaglevGuideway.Frame> {
		val frames = ArrayList<MaglevGuideway.Frame>()
		var s = 0.0
		var last: Vec3? = null
		for (segment in curve) {
			val p = segment.position
			val up = segment.faceNormal
			val across = segment.normal
			last?.let { s += it.distanceTo(p) }
			last = p
			frames += MaglevGuideway.Frame(
				p.x - up.x * DROP, p.y - up.y * DROP, p.z - up.z * DROP,
				across.x, across.y, across.z, up.x, up.y, up.z, s,
			)
		}
		return frames
	}

	/**
	 * What runs through [curve] where it leaves its blocks: the straight track along each end block's axis (from the
	 * block on, as far as it goes toward the curve) and every other curve leaving those blocks.
	 */
	private fun others(level: Level, curve: BezierConnection): List<MaglevGuideway.Line> {
		val origin = curve.bePositions.first
		val lines = LinkedHashSet<MaglevGuideway.Line>()
		val seen = HashSet<Pair<BlockPos, BlockPos>>()
		seen += key(curve)
		for (end in listOf(true, false)) {
			val block = curve.bePositions.get(end)
			val axis = curve.axes.get(end)
			val sx = stepOf(axis.x, axis.z)
			val sz = stepOf(axis.z, axis.x)
			if (sx == 0 && sz == 0) continue
			var reach = 0
			while (reach < ALONG && isTrack(level, block.offset(sx * (reach + 1), 0, sz * (reach + 1)))) reach++
			// The straight: from the end block's far end to the last block along toward the curve.
			val cx = block.x + 0.5 - origin.x
			val cz = block.z + 0.5 - origin.z
			lines += MaglevGuideway.Line(listOf(cx - sx * 0.5, cz - sz * 0.5, cx + sx * (reach + 0.5), cz + sz * (reach + 0.5)))
			for (m in 0..reach) {
				val entity = level.getBlockEntity(block.offset(sx * m, 0, sz * m)) as? TrackBlockEntity ?: continue
				for (other in entity.connections.values) if (seen.add(key(other))) lines += centreLine(other, origin)
			}
		}
		return lines.toList()
	}

	/** A curve's ends, the same from either of them. */
	private fun key(curve: BezierConnection): Pair<BlockPos, BlockPos> {
		val (a, b) = curve.bePositions.first to curve.bePositions.second
		return if (a < b) a to b else b to a
	}

	/** One block along an axis ([a] this coordinate, [b] the other), in whole steps (diagonals both). */
	private fun stepOf(a: Double, b: Double): Int {
		val length = hypot(a, b)
		if (length < 1e-6) return 0
		return (a / length * 1.5).roundToInt().coerceIn(-1, 1).takeIf { abs(a / length) > 0.3 } ?: 0
	}

	private fun isTrack(level: Level, pos: BlockPos): Boolean = level.getBlockState(pos).block is ITrackBlock

	/** [curve]'s quads, relative to its first block. */
	fun quads(level: Level, curve: BezierConnection): List<BakedQuad> {
		val quads = ArrayList<BakedQuad>()
		MaglevGuideway.build(frames(curve), others(level, curve), RANK, MaglevTrackModel.sprite, quads)
		return quads
	}

	/** Flywheel instances of [entity]'s maglev curves (those it leads), at [visualPos]. */
	@JvmStatic
	fun instances(provider: InstancerProvider, entity: TrackBlockEntity, visualPos: BlockPos): List<Instance> {
		val level = entity.level ?: return emptyList()
		return entity.connections.values.filter { it.isPrimary && isMaglev(it) }.map { curve ->
			val model = BakedModelBuilder(QuadsModel(quads(level, curve))).build()
				provider.instancer(InstanceTypes.TRANSFORMED, model).createInstance().apply {
				setIdentityTransform()
				translate(visualPos.x.toFloat(), visualPos.y.toFloat(), visualPos.z.toFloat())
				// (Lit as at its middle, as Create lights a curve's pieces each where it is.)
				val middle = curve.getPosition(0.5)
				light(LevelRenderer.getLightColor(level, BlockPos.containing(middle.x, middle.y + 0.5, middle.z)))
				setChanged()
			}
		}
	}

	/** [curve] straight into [buffer] (where Flywheel is off), lit as at its first block. */
	@JvmStatic
	fun render(level: Level, curve: BezierConnection, pose: PoseStack, buffer: VertexConsumer) {
		if (!curve.isPrimary) return
		val light = LevelRenderer.getLightColor(level, curve.bePositions.first.above())
		for (quad in quads(level, curve)) buffer.putBulkData(pose.last(), quad, 1f, 1f, 1f, 1f, light, OverlayTexture.NO_OVERLAY)
	}

	/** Re-meshes the blocks whose walls a curve at [pos] can cut (its curves changed). */
	@JvmStatic
	fun remesh(level: Level, pos: BlockPos) {
		if (!level.isClientSide || !level.getBlockState(pos).`is`(RelativityBlocks.MAGLEV_TRACK.get())) return
		val reach = ALONG + 2
		Minecraft.getInstance().levelRenderer.setBlocksDirty(pos.x - reach, pos.y - 1, pos.z - reach, pos.x + reach, pos.y + 1, pos.z + reach)
	}

	/** A model of given quads, to make a Flywheel model of. */
	private class QuadsModel(private val quads: List<BakedQuad>) : IDynamicBakedModel {
		override fun getQuads(state: BlockState?, side: Direction?, rand: RandomSource, data: ModelData, renderType: RenderType?): List<BakedQuad> =
			if (side == null) quads else emptyList()
		override fun useAmbientOcclusion() = false
		override fun isGui3d() = false
		override fun usesBlockLight() = true
		override fun isCustomRenderer() = false
		@Deprecated("Deprecated in Java")
		override fun getParticleIcon(): TextureAtlasSprite = MaglevTrackModel.sprite
		override fun getTransforms(): ItemTransforms = ItemTransforms.NO_TRANSFORMS
		override fun getOverrides(): ItemOverrides = ItemOverrides.EMPTY
		override fun getRenderTypes(state: BlockState, rand: RandomSource, data: ModelData): ChunkRenderTypeSet = ChunkRenderTypeSet.of(RenderType.solid())
	}
}
