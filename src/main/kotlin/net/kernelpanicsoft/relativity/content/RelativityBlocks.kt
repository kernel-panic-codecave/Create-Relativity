package net.kernelpanicsoft.relativity.content

import com.simibubi.create.content.trains.track.TrackBlock
import com.simibubi.create.content.trains.track.TrackBlockItem
import dev.nyon.klf.MOD_BUS
import net.kernelpanicsoft.relativity.CreateRelativity
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister

/** The mod's blocks, their items, and its creative tab. */
object RelativityBlocks {
	private val BLOCKS = DeferredRegister.createBlocks(CreateRelativity.MOD_ID)
	private val ITEMS = DeferredRegister.createItems(CreateRelativity.MOD_ID)
	private val TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CreateRelativity.MOD_ID)
	private val BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CreateRelativity.MOD_ID)

	/** Maglev track ([Maglev]): Create's track, magnetic; only maglev bogeys run on it. */
	val MAGLEV_TRACK: DeferredBlock<TrackBlock> = BLOCKS.register("maglev_track") { ->
		Maglev.MATERIAL.createBlock(
			BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(0.8f).sound(SoundType.METAL)
				.noOcclusion().forceSolidOn().pushReaction(PushReaction.BLOCK),
		)
	}

	/** Maglev bogey ([MaglevBogeyBlock]): no item, laid with a train casing as Create's are. */
	val MAGLEV_BOGEY: DeferredBlock<MaglevBogeyBlock> = BLOCKS.register("maglev_bogey") { ->
		MaglevBogeyBlock(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(2.0f).sound(SoundType.NETHERITE_BLOCK).noOcclusion())
	}

	val MAGLEV_BOGEY_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<MaglevBogeyBlockEntity>> = BLOCK_ENTITY_TYPES.register("maglev_bogey") { ->
		BlockEntityType.Builder.of({ pos, state -> MaglevBogeyBlockEntity(MAGLEV_BOGEY_ENTITY.get(), pos, state) }, MAGLEV_BOGEY.get()).build(null)
	}

	val MAGLEV_TRACK_ITEM: DeferredItem<TrackBlockItem> = ITEMS.register("maglev_track") { -> TrackBlockItem(MAGLEV_TRACK.get(), Item.Properties()) }

	val TAB = TABS.register("main") { ->
		CreativeModeTab.builder()
			.title(Component.translatable("itemGroup.create_relativity"))
			.icon { ItemStack(MAGLEV_TRACK_ITEM.get()) }
			.displayItems { _, output -> output.accept(MAGLEV_TRACK_ITEM.get()) }
			.build()
	}

	fun register() {
		Maglev.init()
		BLOCKS.register(MOD_BUS)
		ITEMS.register(MOD_BUS)
		TABS.register(MOD_BUS)
		BLOCK_ENTITY_TYPES.register(MOD_BUS)
		MOD_BUS.addListener(net.neoforged.neoforge.registries.RegisterEvent::class.java) { event ->
			// (The style needs the bogey block: built once blocks are registered.)
			if (event.registryKey == Registries.BLOCK_ENTITY_TYPE) MaglevBogey.init()
		}
	}
}
