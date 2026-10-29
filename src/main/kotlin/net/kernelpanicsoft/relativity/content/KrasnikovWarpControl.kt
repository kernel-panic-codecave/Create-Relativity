package net.kernelpanicsoft.relativity.content

import com.simibubi.create.AllItems
import com.simibubi.create.content.trains.track.ITrackBlock
import dev.nyon.klf.MOD_BUS
import net.kernelpanicsoft.relativity.CreateRelativity
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent

/**
 * Setting a Krasnikov network's warp factor (KrasnikovNetworks) with a wrench: right-clicking any of its track opens
 * Create's value board (held and dragged across, let go on the warp wanted), at the network's warp now (asked of the
 * server, which alone keeps the networks); let go, the server sets it for the whole network.
 */
object KrasnikovWarpControl {
	/** Client to server: the network of the Krasnikov track at [pos], its warp please. */
	data class Ask(val pos: BlockPos) : CustomPacketPayload {
		override fun type() = ASK
	}

	/** Server to client: the network of the track at [pos] runs at [tenths] of warp: open the board there. */
	data class Show(val pos: BlockPos, val tenths: Int) : CustomPacketPayload {
		override fun type() = SHOW
	}

	/** Client to server: set the network of the track at [pos] to [tenths] of warp. */
	data class Set(val pos: BlockPos, val tenths: Int) : CustomPacketPayload {
		override fun type() = SET
	}

	val ASK = CustomPacketPayload.Type<Ask>(CreateRelativity.id("krasnikov_warp_ask"))
	val SHOW = CustomPacketPayload.Type<Show>(CreateRelativity.id("krasnikov_warp_show"))
	val SET = CustomPacketPayload.Type<Set>(CreateRelativity.id("krasnikov_warp_set"))

	private val ASK_CODEC: StreamCodec<RegistryFriendlyByteBuf, Ask> = StreamCodec.of({ buf, it -> buf.writeBlockPos(it.pos) }, { Ask(it.readBlockPos()) })
	private val SHOW_CODEC: StreamCodec<RegistryFriendlyByteBuf, Show> =
		StreamCodec.of({ buf, it -> buf.writeBlockPos(it.pos); buf.writeVarInt(it.tenths) }, { Show(it.readBlockPos(), it.readVarInt()) })
	private val SET_CODEC: StreamCodec<RegistryFriendlyByteBuf, Set> =
		StreamCodec.of({ buf, it -> buf.writeBlockPos(it.pos); buf.writeVarInt(it.tenths) }, { Set(it.readBlockPos(), it.readVarInt()) })

	/** How far (blocks) a player may be from the track they set. */
	private const val REACH = 8.0

	/** The lowest warp a network may be set to, in tenths: light speed (slower is no Krasnikov tube at all). */
	const val MIN_TENTHS = 10

	fun init() {
		MOD_BUS.addListener(::registerPayloads)
	}

	private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
		val registrar = event.registrar("1")
		registrar.playToServer(ASK, ASK_CODEC) { payload, context ->
			context.enqueueWork {
				val player = context.player() as? ServerPlayer ?: return@enqueueWork
				if (!near(player, payload.pos)) return@enqueueWork
				val tenths = KrasnikovNetworks.tenthsAt(player.level(), payload.pos) ?: return@enqueueWork
				PacketDistributor.sendToPlayer(player, Show(payload.pos, tenths))
			}
		}
		registrar.playToClient(SHOW, SHOW_CODEC) { payload, context ->
			context.enqueueWork { net.kernelpanicsoft.relativity.client.KrasnikovWarpScreen.open(payload.pos, payload.tenths) }
		}
		registrar.playToServer(SET, SET_CODEC) { payload, context ->
			context.enqueueWork {
				val player = context.player() as? ServerPlayer ?: return@enqueueWork
				set(player, payload.pos, payload.tenths)
			}
		}
	}

	private fun near(player: Player, pos: BlockPos): Boolean =
		isKrasnikov(player.level(), pos) && player.position().distanceTo(pos.center) <= REACH

	private fun isKrasnikov(level: Level, pos: BlockPos): Boolean = (level.getBlockState(pos).block as? ITrackBlock)?.material === Maglev.KRASNIKOV

	/** Sets the network of the Krasnikov track at [pos] to [tenths] of warp, for [player] (if they're by it), and tells them. */
	fun set(player: ServerPlayer, pos: BlockPos, tenths: Int) {
		if (!near(player, pos)) return
		val clamped = tenths.coerceIn(MIN_TENTHS, KrasnikovNetworks.MAX_TENTHS)
		KrasnikovNetworks.setAt(player.level(), pos, clamped)
		player.displayClientMessage(Component.translatable("create_relativity.krasnikov_track.warp_set", WarpSetting.format(clamped)), true)
	}

	/** A right-click on a block: on Krasnikov track with a wrench (not sneaking: that's Create's own), the warp board. */
	fun onRightClick(event: PlayerInteractEvent.RightClickBlock) {
		val player = event.entity
		if (player.isShiftKeyDown || !AllItems.WRENCH.isIn(event.itemStack) || !isKrasnikov(event.level, event.pos)) return
		event.isCanceled = true
		event.cancellationResult = InteractionResult.SUCCESS
		if (event.level.isClientSide) PacketDistributor.sendToServer(Ask(event.pos))
	}
}
