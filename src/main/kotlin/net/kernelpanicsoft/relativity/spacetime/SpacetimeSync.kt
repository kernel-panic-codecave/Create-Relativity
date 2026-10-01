package net.kernelpanicsoft.relativity.spacetime

import dev.nyon.klf.MOD_BUS
import net.kernelpanicsoft.relativity.CreateRelativity
import net.minecraft.client.Minecraft
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Pushes an entity's [SpacetimeAttachments] to the clients that can see it (and to the player themself).
 * The attachments' own `sync` never sends anything on NeoForge: Common Storage Lib's `set` only stores.
 */
object SpacetimeSync {
	data class Payload(val entityId: Int, val stretch: Float, val lag: Float) : CustomPacketPayload {
		override fun type() = TYPE
	}

	val TYPE = CustomPacketPayload.Type<Payload>(CreateRelativity.id("entity_spacetime"))

	private val CODEC: StreamCodec<RegistryFriendlyByteBuf, Payload> = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, Payload::entityId,
		ByteBufCodecs.FLOAT, Payload::stretch,
		ByteBufCodecs.FLOAT, Payload::lag,
		::Payload,
	)

	private const val STRETCH_STEP = 0.002f
	private const val LAG_STEP = 0.01f

	/** Last values sent per entity, to send only on change. */
	private val sent = WeakHashMap<Entity, Payload>()

	fun init() {
		MOD_BUS.addListener(::registerPayloads)
	}

	private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
		event.registrar("1").playToClient(TYPE, CODEC) { payload, context ->
			context.enqueueWork { receive(payload) }
		}
	}

	private fun receive(payload: Payload) {
		val entity = Minecraft.getInstance().level?.getEntity(payload.entityId) ?: return
		SpacetimeAttachments.bodyStretch.set(entity, payload.stretch.toDouble())
		SpacetimeAttachments.properTimeLag.set(entity, payload.lag.toDouble())
	}

	private fun current(entity: Entity) = Payload(
		entity.id,
		SpacetimeAttachments.bodyStretch.get(entity).toFloat(),
		SpacetimeAttachments.properTimeLag.get(entity).toFloat(),
	)

	/** Sends [entity]'s values if they moved noticeably since the last send. Server side, once per tick. */
	fun update(entity: Entity) {
		val now = current(entity)
		val last = sent[entity]
		if (last != null && abs(now.stretch - last.stretch) < STRETCH_STEP && abs(now.lag - last.lag) < LAG_STEP) return
		// Settling back to exactly 1 always goes out, so clients don't keep a sliver of stretch.
		if (last == null && now.stretch == 1f && now.lag == 0f) return
		sent[entity] = now
		PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, now)
	}

	/** A player who starts seeing an entity gets its current values. */
	fun onStartTracking(event: PlayerEvent.StartTracking) {
		val player = event.entity as? ServerPlayer ?: return
		val now = current(event.target)
		if (now.stretch != 1f || now.lag != 0f) PacketDistributor.sendToPlayer(player, now)
	}
}
