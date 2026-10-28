package net.kernelpanicsoft.relativity.content

import dev.nyon.klf.MOD_BUS
import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.spacetime.FieldSource
import net.minecraft.client.Minecraft
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.neoforged.neoforge.event.tick.LevelTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import org.joml.Vector3d
import org.joml.Vector3dc
import java.util.WeakHashMap

/**
 * The server's Krasnikov tubes, sent to the clients in each level: a tube follows its train's route, and only the
 * server's trains know their routes (a client's copy of a train has no destination), so a client can't work them out
 * itself. Sent when they change (a tube forming runs out and tilts a little each tick) and every [RESEND] ticks (players
 * joining or changing levels); the client lenses and shows what it was last sent ([received]).
 */
object KrasnikovSync {
	data class Payload(val tubes: List<FieldSource.Krasnikov>) : CustomPacketPayload {
		override fun type() = TYPE
	}

	val TYPE = CustomPacketPayload.Type<Payload>(CreateRelativity.id("krasnikov_tubes"))

	private val CODEC: StreamCodec<RegistryFriendlyByteBuf, Payload> = StreamCodec.of(
		{ buf, payload ->
			buf.writeVarInt(payload.tubes.size)
			for (tube in payload.tubes) {
				buf.writeFloat(tube.k.toFloat())
				buf.writeFloat(tube.radius.toFloat())
				buf.writeVarInt(tube.spine.size)
				for (p in tube.spine) {
					buf.writeDouble(p.x())
					buf.writeDouble(p.y())
					buf.writeDouble(p.z())
				}
			}
		},
		{ buf ->
			val tubes = List(buf.readVarInt()) {
				val k = buf.readFloat().toDouble()
				val radius = buf.readFloat().toDouble()
				val spine = List<Vector3dc>(buf.readVarInt()) { Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble()) }
				FieldSource.Krasnikov(spine, k, radius)
			}
			Payload(tubes)
		},
	)

	/** Ticks between sends of unchanged tubes (non-empty ones; an empty list goes out once, on change). */
	private const val RESEND = 40

	/** What each level was last sent, and when. */
	private val sent = WeakHashMap<Level, Pair<List<FieldSource.Krasnikov>, Long>>()

	/** The tubes the client was last sent for each level (by dimension). */
	@Volatile
	private var received: Map<ResourceKey<Level>, List<FieldSource.Krasnikov>> = emptyMap()

	fun init() {
		MOD_BUS.addListener(::registerPayloads)
	}

	private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
		event.registrar("1").playToClient(TYPE, CODEC) { payload, context ->
			context.enqueueWork {
				val level = Minecraft.getInstance().level ?: return@enqueueWork
				received = received + (level.dimension() to payload.tubes)
			}
		}
	}

	/** On the client: [level]'s tubes as last sent. */
	fun received(level: Level): List<FieldSource.Krasnikov> = received[level.dimension()] ?: emptyList()

	/** Each server level's tick: its tubes out to its players if they changed (or are due again). */
	fun onLevelTick(event: LevelTickEvent.Post) {
		val level = event.level as? ServerLevel ?: return
		val tubes = KrasnikovTubes.tubes(level)
		val last = sent[level]
		val tick = level.gameTime
		val changed = last == null || last.first != tubes
		if (!changed && (tubes.isEmpty() || tick - last!!.second < RESEND)) return
		sent[level] = tubes to tick
		PacketDistributor.sendToPlayersInDimension(level, Payload(tubes))
	}
}
