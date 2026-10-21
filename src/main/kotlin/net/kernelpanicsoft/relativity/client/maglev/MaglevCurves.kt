package net.kernelpanicsoft.relativity.client.maglev

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.simibubi.create.content.trains.track.BezierConnection
import com.simibubi.create.content.trains.track.TrackBlockEntity
import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.api.instance.InstancerProvider
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.model.baked.BakedModelBuilder
import net.kernelpanicsoft.relativity.content.Maglev
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
import net.minecraft.world.level.ChunkPos
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Maglev track's curves: the guideway trough ([MaglevGuideway]) swept along each, one surface (no pieces stretched
 * between rail points, so nothing opens on the outside of a bend), cut where other maglev track runs through it, at
 * any angle (a junction: the straight going on, other curves leaving the same blocks; a crossing). Drawn as Flywheel
 * instances in Create's track visual (TrackVisualMixin), or straight into the buffer where Flywheel is off
 * (TrackRendererMixin); indexed by where they reach, for the blocks and curves they run through.
 */
object MaglevCurves {
	/** How far up from a curve's points (Create's) the profile's origin is, so curves meet the blocks flush. */
	private const val DROP = 0.197

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

	/** What runs through [curve]: every maglev block along it (each its paths), every other maglev curve near it. */
	private fun others(level: Level, curve: BezierConnection): List<MaglevGuideway.Line> {
		val origin = curve.bePositions.first
		val lines = LinkedHashSet<MaglevGuideway.Line>()
		val blocks = HashSet<BlockPos>()
		val reach = ceil(2 * MaglevGuideway.HALF_WIDTH).toInt()
		for (segment in curve) {
			val x = floor(segment.position.x + origin.x).toInt()
			val y = floor(segment.position.y + origin.y).toInt()
			val z = floor(segment.position.z + origin.z).toInt()
			for (dx in -reach..reach) for (dy in -1..0) for (dz in -reach..reach) blocks += BlockPos(x + dx, y + dy, z + dz)
		}
		for (block in blocks) lines += MaglevTrackModel.centreLines(level.getBlockState(block), block.x - origin.x, block.z - origin.z)
		val self = key(curve)
		for (other in near(bounds(curve))) if (key(other) != self) lines += centreLine(other, origin)
		return lines.toList()
	}

	/** A curve's ends, the same from either of them. */
	private fun key(curve: BezierConnection): Pair<BlockPos, BlockPos> {
		val (a, b) = curve.bePositions.first to curve.bePositions.second
		return if (a < b) a to b else b to a
	}

	// Every loaded maglev curve (each once, from the block it leads from), by the map columns (16 x 16) within reach of
	// it: what runs through a block or curve, at any angle.
	private var indexed: Level? = null
	private val byColumn = ConcurrentHashMap<Long, MutableSet<BezierConnection>>()
	private val byBlock = ConcurrentHashMap<BlockPos, List<BezierConnection>>()

	/** [curve]'s extent seen from above (blocks: min x, min z, max x, max z), out to where its trough can cut another's. */
	private fun bounds(curve: BezierConnection): IntArray {
		val origin = curve.bePositions.first
		var minX = Double.MAX_VALUE
		var minZ = Double.MAX_VALUE
		var maxX = -Double.MAX_VALUE
		var maxZ = -Double.MAX_VALUE
		for (segment in curve) {
			minX = minOf(minX, segment.position.x)
			maxX = maxOf(maxX, segment.position.x)
			minZ = minOf(minZ, segment.position.z)
			maxZ = maxOf(maxZ, segment.position.z)
		}
		val reach = 2 * MaglevGuideway.HALF_WIDTH + 1
		return intArrayOf(
			floor(minX - reach).toInt() + origin.x, floor(minZ - reach).toInt() + origin.z,
			ceil(maxX + reach).toInt() + origin.x, ceil(maxZ + reach).toInt() + origin.z,
		)
	}

	private fun columns(b: IntArray): List<Long> {
		val columns = ArrayList<Long>()
		for (cx in (b[0] shr 4)..(b[2] shr 4)) for (cz in (b[1] shr 4)..(b[3] shr 4)) columns += ChunkPos.asLong(cx, cz)
		return columns
	}

	/** The maglev curves whose reach (bounds) meets [b] (min x, min z, max x, max z). */
	fun near(b: IntArray): List<BezierConnection> {
		val found = LinkedHashSet<BezierConnection>()
		for (column in columns(b)) for (curve in byColumn[column] ?: continue) {
			val c = bounds(curve)
			if (c[0] <= b[2] && b[0] <= c[2] && c[1] <= b[3] && b[1] <= c[3]) found += curve
		}
		return found.toList()
	}

	/**
	 * Track at [pos] now leads [curves] (none: gone): indexes its maglev ones anew and re-meshes the blocks their troughs
	 * cut, before and after.
	 */
	@JvmStatic
	fun update(level: Level, pos: BlockPos, curves: Collection<BezierConnection>) {
		if (!level.isClientSide) return
		if (level !== indexed) {
			byColumn.clear()
			byBlock.clear()
			indexed = level
		}
		val old = byBlock.remove(pos) ?: emptyList()
		for (curve in old) for (column in columns(bounds(curve))) byColumn[column]?.remove(curve)
		val new = curves.filter { it.isPrimary && isMaglev(it) }
		if (new.isNotEmpty()) byBlock[pos.immutable()] = new
		for (curve in new) for (column in columns(bounds(curve))) byColumn.computeIfAbsent(column) { ConcurrentHashMap.newKeySet() } += curve
		val renderer = Minecraft.getInstance().levelRenderer
		for (curve in old + new) {
			val b = bounds(curve)
			val y = curve.bePositions.first.y
			renderer.setBlocksDirty(b[0], y - 2, b[1], b[2], y + 2, b[3])
		}
	}

	/** [curve]'s quads, relative to its first block. */
	fun quads(level: Level, curve: BezierConnection): List<BakedQuad> {
		val quads = ArrayList<BakedQuad>()
		MaglevGuideway.build(MaglevGuideway.refined(frames(curve), 4), others(level, curve), RANK, MaglevTrackModel.sprite, quads)
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
