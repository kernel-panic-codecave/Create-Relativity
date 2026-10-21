package net.kernelpanicsoft.relativity.content

import com.mojang.blaze3d.vertex.PoseStack
import com.simibubi.create.foundation.block.IBE
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour
import net.createmod.catnip.math.VecHelper
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.physics.WarpScale
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.phys.Vec3

/**
 * The warp drive: a 3 x 3 x 3 of a [WarpCoreBlock] wrapped in 26 [WarpCoilBlock]s, formed once it's complete. The coil
 * on the middle of the core's front face carries the drive's setting (its value box: the highest warp it runs at);
 * redstone into any coil throttles it from nothing to that. Aboard a maglev train, the train's controls throttle it
 * instead; aboard a ship, it carries the ship the way the core faces.
 */
object WarpDrive {
	val FORMED: BooleanProperty = BooleanProperty.create("formed")

	/** The 26 places round [core] its coils go. */
	fun shell(core: BlockPos): List<BlockPos> =
		BlockPos.betweenClosed(core.offset(-1, -1, -1), core.offset(1, 1, 1)).map { it.immutable() }.filter { it != core }

	/** The highest warp factor a drive may be set to (the config's, kept below 10), in tenths. */
	fun maxTenths(): Int = (RelativityConfig.Server.Trains.maxWarp.coerceIn(0.0, 9.99) * 10).toInt()
}

class WarpCoreBlock(properties: BlockBehaviour.Properties) : Block(properties), IBE<WarpCoreBlockEntity> {
	init {
		registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.FACING, Direction.NORTH).setValue(WarpDrive.FORMED, false))
	}

	override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
		builder.add(BlockStateProperties.FACING, WarpDrive.FORMED)
	}

	/** Facing away from the one placing it: its front where they look. */
	override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
		defaultBlockState().setValue(BlockStateProperties.FACING, context.nearestLookingDirection)

	override fun getBlockEntityClass(): Class<WarpCoreBlockEntity> = WarpCoreBlockEntity::class.java
	override fun getBlockEntityType(): BlockEntityType<out WarpCoreBlockEntity> = RelativityBlocks.WARP_CORE_ENTITY.get()
}

class WarpCoilBlock(properties: BlockBehaviour.Properties) : Block(properties), IBE<WarpCoilBlockEntity> {
	init {
		registerDefaultState(stateDefinition.any().setValue(WarpDrive.FORMED, false))
	}

	override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
		builder.add(WarpDrive.FORMED)
	}

	override fun getBlockEntityClass(): Class<WarpCoilBlockEntity> = WarpCoilBlockEntity::class.java
	override fun getBlockEntityType(): BlockEntityType<out WarpCoilBlockEntity> = RelativityBlocks.WARP_COIL_ENTITY.get()
}

class WarpCoreBlockEntity(type: BlockEntityType<*>, pos: BlockPos, state: BlockState) : SmartBlockEntity(type, pos, state) {
	/** The strongest redstone signal into any of its coils (0 to 15). */
	var signal = 0
		private set

	override fun addBehaviours(behaviours: MutableList<BlockEntityBehaviour>) {
		setLazyTickRate(10)
	}

	val facing: Direction get() = blockState.getValue(BlockStateProperties.FACING)
	val formed: Boolean get() = blockState.getValue(WarpDrive.FORMED)

	/** The coil carrying the setting: the middle of the front face. */
	val controlPos: BlockPos get() = worldPosition.relative(facing)

	/** The highest warp it runs at (the control coil's setting), 0 unformed. */
	val maxWarp: Double
		get() {
			if (!formed) return 0.0
			val coil = level?.getBlockEntity(controlPos) as? WarpCoilBlockEntity ?: return 0.0
			return coil.setting.value / 10.0
		}

	/** The warp it runs at now, by redstone: from nothing to [maxWarp]. */
	val warp: Double get() = maxWarp * signal / 15.0

	/** [warp] as a multiple of c. */
	val speed: Double get() = WarpScale.speed(warp)

	override fun lazyTick() {
		super.lazyTick()
		val level = level ?: return
		if (level.isClientSide) return
		val shell = WarpDrive.shell(worldPosition)
		val complete = shell.all { level.getBlockState(it).block is WarpCoilBlock }
		if (complete != formed) {
			level.setBlock(worldPosition, blockState.setValue(WarpDrive.FORMED, complete), 3)
			for (pos in shell) {
				val state = level.getBlockState(pos)
				if (state.block is WarpCoilBlock && state.getValue(WarpDrive.FORMED) != complete) level.setBlock(pos, state.setValue(WarpDrive.FORMED, complete), 3)
			}
		}
		val strongest = if (complete) shell.maxOf { level.getBestNeighborSignal(it) } else 0
		if (strongest != signal) {
			signal = strongest
			sendData()
		}
		// (The control coil learns which face its value box is on.)
		(level.getBlockEntity(controlPos) as? WarpCoilBlockEntity)?.control(if (complete) facing else null)
	}

	override fun write(tag: CompoundTag, registries: HolderLookup.Provider, clientPacket: Boolean) {
		super.write(tag, registries, clientPacket)
		tag.putInt("Signal", signal)
	}

	override fun read(tag: CompoundTag, registries: HolderLookup.Provider, clientPacket: Boolean) {
		super.read(tag, registries, clientPacket)
		signal = tag.getInt("Signal")
	}
}

class WarpCoilBlockEntity(type: BlockEntityType<*>, pos: BlockPos, state: BlockState) : SmartBlockEntity(type, pos, state) {
	/** The face its value box is on, if it's a drive's control coil. */
	var controlFace: Direction? = null
		private set

	lateinit var setting: ScrollValueBehaviour
		private set

	override fun addBehaviours(behaviours: MutableList<BlockEntityBehaviour>) {
		setting = ScrollValueBehaviour(Component.translatable("create_relativity.warp_drive.max_warp"), this, ControlFace())
			.between(0, WarpDrive.maxTenths())
			.withFormatter { "%.1f".format(it / 10.0) }
			.onlyActiveWhen { controlFace != null }
		setting.value = 10
		behaviours += setting
	}

	/** Made (or unmade) a drive's control coil, its value box on [face]. */
	fun control(face: Direction?) {
		if (face == controlFace) return
		controlFace = face
		sendData()
	}

	override fun write(tag: CompoundTag, registries: HolderLookup.Provider, clientPacket: Boolean) {
		super.write(tag, registries, clientPacket)
		controlFace?.let { tag.putInt("ControlFace", it.get3DDataValue()) }
	}

	override fun read(tag: CompoundTag, registries: HolderLookup.Provider, clientPacket: Boolean) {
		super.read(tag, registries, clientPacket)
		controlFace = if (tag.contains("ControlFace")) Direction.from3DDataValue(tag.getInt("ControlFace")) else null
	}

	/** The value box in the middle of the control face. */
	private inner class ControlFace : ValueBoxTransform.Sided() {
		override fun getSouthLocation(): Vec3 = VecHelper.voxelSpace(8.0, 8.0, 15.5)

		override fun isSideActive(state: BlockState, direction: Direction): Boolean = direction == controlFace

		override fun getLocalOffset(level: LevelAccessor, pos: BlockPos, state: BlockState): Vec3 {
			controlFace?.let { fromSide(it) }
			return super.getLocalOffset(level, pos, state)
		}

		override fun rotate(level: LevelAccessor, pos: BlockPos, state: BlockState, ms: PoseStack) {
			controlFace?.let { fromSide(it) }
			super.rotate(level, pos, state, ms)
		}
	}
}

/** The warp drive aboard [level] at [core], if there's a formed one there. */
fun warpCoreAt(level: Level, core: BlockPos): WarpCoreBlockEntity? =
	(level.getBlockEntity(core) as? WarpCoreBlockEntity)?.takeIf { it.formed }
