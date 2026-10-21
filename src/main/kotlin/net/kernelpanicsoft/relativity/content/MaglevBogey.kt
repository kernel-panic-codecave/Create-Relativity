package net.kernelpanicsoft.relativity.content

import com.simibubi.create.AllBlocks
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity
import com.simibubi.create.content.trains.bogey.BogeySizes
import com.simibubi.create.content.trains.bogey.BogeyStyle
import com.simibubi.create.content.trains.track.TrackMaterial
import net.kernelpanicsoft.relativity.CreateRelativity
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.function.Supplier

/**
 * A maglev bogey: no wheels (they'd have to turn faster than light), a 2 x 2 of magnets hovering in the guideway, north
 * poles forward, under a chassis to Create's top plate. Runs only on maglev track (its track type); laid by using a
 * train casing on maglev track, as Create's bogeys are on theirs (TrackBlockMixin).
 */
class MaglevBogeyBlock(properties: BlockBehaviour.Properties) : AbstractBogeyBlock<MaglevBogeyBlockEntity>(properties, BogeySizes.SMALL) {
	override fun getTrackType(style: BogeyStyle): TrackMaterial.TrackType = Maglev.TRACK_TYPE

	// (As Create's small bogey: where its wheels would touch, how far apart, how it couples.)
	override fun getWheelPointSpacing(): Double = 2.0
	override fun getWheelRadius(): Double = 6.5 / 16.0
	override fun getConnectorAnchorOffset(): Vec3 = Vec3(0.0, 7.0 / 32.0, 1.0)

	override fun getDefaultStyle(): BogeyStyle = MaglevBogey.STYLE

	override fun getCloneItemStack(state: BlockState, target: HitResult, level: LevelReader, pos: BlockPos, player: net.minecraft.world.entity.player.Player): ItemStack =
		AllBlocks.RAILWAY_CASING.asStack()

	override fun getBlockEntityClass(): Class<MaglevBogeyBlockEntity> = MaglevBogeyBlockEntity::class.java
	override fun getBlockEntityType(): BlockEntityType<out MaglevBogeyBlockEntity> = RelativityBlocks.MAGLEV_BOGEY_ENTITY.get()
}

class MaglevBogeyBlockEntity(type: BlockEntityType<*>, pos: BlockPos, state: BlockState) : AbstractBogeyBlockEntity(type, pos, state) {
	override fun getDefaultStyle(): BogeyStyle = MaglevBogey.STYLE
}

object MaglevBogey {
	/** The maglev bogey's style: its own cycle group (no other style runs on maglev track). */
	val STYLE: BogeyStyle by lazy {
		BogeyStyle.Builder(CreateRelativity.id("maglev"), CreateRelativity.id("maglev"))
			.displayName(Component.translatable("create_relativity.bogey.style.maglev"))
			.contactParticle(ParticleTypes.ELECTRIC_SPARK)
			.size(BogeySizes.SMALL, Supplier { RelativityBlocks.MAGLEV_BOGEY.get() }, Supplier { Supplier { net.kernelpanicsoft.relativity.client.maglev.MaglevBogeyRender.sizeRenderer() } })
			.build()
	}

	/** Whether [train] runs wholly on maglev bogeys (faster, less friction: TrainMixin). */
	@JvmStatic
	fun isMaglev(train: com.simibubi.create.content.trains.entity.Train): Boolean =
		train.carriages.isNotEmpty() && train.carriages.all { carriage -> carriage.bogeys.all { it == null || it.style === STYLE } }

	/** Builds the style (registering it with Create's) once the bogey block is registered. */
	fun init() {
		STYLE
	}
}

