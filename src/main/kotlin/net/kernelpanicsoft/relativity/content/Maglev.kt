package net.kernelpanicsoft.relativity.content

import com.simibubi.create.AllDataComponents
import com.simibubi.create.content.trains.track.ITrackBlock
import com.simibubi.create.content.trains.track.TrackBlock
import com.simibubi.create.content.trains.track.TrackMaterial
import com.simibubi.create.content.trains.track.TrackMaterialFactory
import com.tterrag.registrate.util.nullness.NonNullSupplier
import dev.engine_room.flywheel.lib.model.baked.PartialModel
import net.kernelpanicsoft.relativity.CreateRelativity
import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.abs
import kotlin.math.sign
import java.util.function.Supplier

/**
 * Maglev: track for trains that float (the warp trains': wheels on rails would have to turn faster than light). A
 * track material of its own type, so only maglev bogeys run on it. For now Create's track geometry, retextured: a
 * concrete guideway, steel rails, a magnet strip along each.
 */
object Maglev {
	/** Maglev track's type: what maglev bogeys run on (and only they). */
	val TRACK_TYPE = TrackMaterial.TrackType(CreateRelativity.id("maglev")) { properties, material -> TrackBlock(properties, material) }

	val MATERIAL: TrackMaterial = TrackMaterialFactory.make(CreateRelativity.id("maglev"))
		.lang("Maglev")
		.block(NonNullSupplier { NonNullSupplier<TrackBlock> { RelativityBlocks.MAGLEV_TRACK.get() } })
		.particle(CreateRelativity.id("block/maglev_track"))
		.trackType(TRACK_TYPE)
		.sleeper(Items.SMOOTH_STONE_SLAB)
		.rails(Items.IRON_NUGGET)
		.noRecipeGen()
		.customModels(
			Supplier { Supplier { MaglevModels.TIE } },
			Supplier { Supplier { MaglevModels.LEFT } },
			Supplier { Supplier { MaglevModels.RIGHT } },
		)
		.build()

	/** Loads this (the material registers itself with Create on construction). */
	fun init() = Unit

	/** [state]'s track type, if it's track. */
	private fun typeOf(state: BlockState): TrackMaterial.TrackType? = (state.block as? ITrackBlock)?.material?.trackType

	/**
	 * Whether track [state] at [pos] would have an end running into track of another type: a track block next to it
	 * along one of its axes (level, or a block up or down: slopes), lying along the same line. Track types never join
	 * (maglev only maglev): Create's graph would join them where they meet.
	 */
	@JvmStatic
	fun meetsOtherType(level: BlockGetter, pos: BlockPos, state: BlockState): Boolean {
		val type = typeOf(state) ?: return false
		val shape = state.getOptionalValue(TrackBlock.SHAPE).orElse(null) ?: return false
		for (axis in shape.axes) {
			val sx = sign(axis.x).toInt()
			val sz = sign(axis.z).toInt()
			for (end in intArrayOf(1, -1)) for (dy in -1..1) {
				val there = level.getBlockState(pos.offset(sx * end, dy, sz * end))
				val other = typeOf(there) ?: continue
				if (other === type) continue
				val otherShape = there.getOptionalValue(TrackBlock.SHAPE).orElse(null) ?: continue
				// (Along the same line: an axis of its own runs as this one does, either way, seen from above.)
				if (otherShape.axes.any { abs(it.x * axis.z - it.z * axis.x) < 1e-6 && abs(it.x) + abs(it.z) > 1e-6 }) return true
			}
		}
		return false
	}

	/** Create's message (track.*) for a curve refused for joining track of another type. */
	const val OTHER_TYPE = "create_relativity.other_type"

	/**
	 * Whether a curve from the track selected in [stack] to track [state] can't be laid: one or the other is of a type
	 * other than the track laid's.
	 */
	@JvmStatic
	fun refusesConnection(level: BlockGetter, state: BlockState, stack: ItemStack): Boolean {
		val material = TrackMaterial.fromItem(stack.item)
		val from = stack.get(AllDataComponents.TRACK_CONNECTING_FROM)?.pos?.let(level::getBlockState)
		val types = listOfNotNull(typeOf(state), from?.let(::typeOf))
		return types.any { it !== material.trackType }
	}
}

/** Maglev track's pieces for curves (client only: Flywheel partial models). */
object MaglevModels {
	val TIE: PartialModel = PartialModel.of(CreateRelativity.id("block/maglev_track/tie"))
	val LEFT: PartialModel = PartialModel.of(CreateRelativity.id("block/maglev_track/segment_left"))
	val RIGHT: PartialModel = PartialModel.of(CreateRelativity.id("block/maglev_track/segment_right"))
}
