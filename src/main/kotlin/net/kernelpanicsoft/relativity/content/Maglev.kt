package net.kernelpanicsoft.relativity.content

import com.simibubi.create.content.trains.track.TrackBlock
import com.simibubi.create.content.trains.track.TrackMaterial
import com.simibubi.create.content.trains.track.TrackMaterialFactory
import com.tterrag.registrate.util.nullness.NonNullSupplier
import dev.engine_room.flywheel.lib.model.baked.PartialModel
import net.kernelpanicsoft.relativity.CreateRelativity
import net.minecraft.world.item.Items
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
}

/** Maglev track's pieces for curves (client only: Flywheel partial models). */
object MaglevModels {
	val TIE: PartialModel = PartialModel.of(CreateRelativity.id("block/maglev_track/tie"))
	val LEFT: PartialModel = PartialModel.of(CreateRelativity.id("block/maglev_track/segment_left"))
	val RIGHT: PartialModel = PartialModel.of(CreateRelativity.id("block/maglev_track/segment_right"))
}
