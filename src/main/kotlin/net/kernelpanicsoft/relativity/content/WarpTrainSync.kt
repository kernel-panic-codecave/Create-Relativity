package net.kernelpanicsoft.relativity.content

import com.simibubi.create.Create
import com.simibubi.create.CreateClient
import com.simibubi.create.content.trains.entity.Train
import dev.nyon.klf.MOD_BUS
import net.kernelpanicsoft.relativity.CreateRelativity
import net.minecraft.client.Minecraft
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.util.UUID

/**
 * Warp trains' places, sent to the clients every tick and set there as they are: Create sends a carriage's wheels every
 * few ticks and has the client's copy run after them (CarriageSyncData.approach), looking for them along the track at
 * most 32 blocks on; a train in its tube goes further than that a tick, and its copy lags, jumps wheel by wheel (its
 * carriages torn apart) and overshoots. While a train's places come this way, Create's own are ignored on the client
 * (CarriageSyncDataMixin), and for [HOLD] ticks after the last (it stops where it was last sent).
 */
object WarpTrainSync {
	/** A carriage's wheels: each its edge's nodes (net ids) and how far along it. */
	data class Wheel(val node1: Int, val node2: Int, val position: Double)

	/** A train's speed, its distance to its destination, and its carriages' wheels (two or four a carriage). */
	data class Place(val train: UUID, val speed: Double, val left: Double, val carriages: List<List<Wheel>>)

	data class Payload(val trains: List<Place>) : CustomPacketPayload {
		override fun type() = TYPE
	}

	val TYPE = CustomPacketPayload.Type<Payload>(CreateRelativity.id("warp_trains"))

	private val CODEC: StreamCodec<RegistryFriendlyByteBuf, Payload> = StreamCodec.of(
		{ buf, payload ->
			buf.writeVarInt(payload.trains.size)
			for (place in payload.trains) {
				buf.writeUUID(place.train)
				buf.writeDouble(place.speed)
				buf.writeDouble(place.left)
				buf.writeVarInt(place.carriages.size)
				for (wheels in place.carriages) {
					buf.writeVarInt(wheels.size)
					for (wheel in wheels) {
						buf.writeVarInt(wheel.node1)
						buf.writeVarInt(wheel.node2)
						buf.writeDouble(wheel.position)
					}
				}
			}
		},
		{ buf ->
			Payload(List(buf.readVarInt()) {
				val train = buf.readUUID()
				val speed = buf.readDouble()
				val left = buf.readDouble()
				val carriages = List(buf.readVarInt()) { List(buf.readVarInt()) { Wheel(buf.readVarInt(), buf.readVarInt(), buf.readDouble()) } }
				Place(train, speed, left, carriages)
			})
		},
	)

	/** Ticks a train's places keep overriding Create's after the last one sent: till its own sync has caught up. */
	private const val HOLD = 20

	/** The trains sent last tick (each gets one more send once it's no longer a warp train: where it stopped). */
	private var sentLast: Set<UUID> = emptySet()

	/** On the client: trains whose places came this way, till what client tick. */
	private val heldUntil = HashMap<UUID, Long>()

	fun init() {
		MOD_BUS.addListener(::registerPayloads)
	}

	private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
		event.registrar("1").playToClient(TYPE, CODEC) { payload, context ->
			context.enqueueWork { receive(payload) }
		}
	}

	/** Whether [train] (a server's) is sent this way: a warp train under way, or in its tube. */
	private fun isSent(train: Train): Boolean =
		MaglevBogey.isMaglev(train) && (WarpReservations.isWarp(train) || KrasnikovTubes.topSpeed(train) != null)

	/** Each server tick: warp trains' places out to every player (and once more each for those just stopped). */
	fun onServerTick(event: ServerTickEvent.Post) {
		val trains = Create.RAILWAYS.trains
		val sending = trains.values.filter { isSent(it) }.mapTo(HashSet()) { it.id }
		val out = (sending + sentLast).mapNotNull { id -> trains[id]?.let { placeOf(it) } }
		sentLast = sending
		if (out.isNotEmpty()) PacketDistributor.sendToAllPlayers(Payload(out))
	}

	private fun placeOf(train: Train): Place? {
		val carriages = train.carriages.map { carriage ->
			val bogeys = if (carriage.isOnTwoBogeys) listOf(carriage.bogeys.first, carriage.bogeys.second) else listOf(carriage.bogeys.first)
			bogeys.flatMap { bogey ->
				listOf(bogey.leading(), bogey.trailing()).map { point ->
					val node1 = point.node1 ?: return null
					val node2 = point.node2 ?: return null
					Wheel(node1.netId, node2.netId, point.position)
				}
			}
		}
		return Place(train.id, train.speed, train.navigation.distanceToDestination, carriages)
	}

	/** On the client: each train's wheels set where the server has them. */
	private fun receive(payload: Payload) {
		val tick = Minecraft.getInstance().level?.gameTime ?: return
		for (place in payload.trains) {
			val train = CreateClient.RAILWAYS.trains[place.train] ?: continue
			val graph = train.graph ?: continue
			if (train.carriages.size != place.carriages.size) continue
			for ((carriage, wheels) in train.carriages.zip(place.carriages)) {
				val bogeys = if (carriage.isOnTwoBogeys) listOf(carriage.bogeys.first, carriage.bogeys.second) else listOf(carriage.bogeys.first)
				val points = bogeys.flatMap { listOf(it.leading(), it.trailing()) }
				if (points.size != wheels.size) continue
				for ((point, wheel) in points.zip(wheels)) {
					val node1 = graph.getNode(wheel.node1) ?: continue
					val node2 = graph.getNode(wheel.node2) ?: continue
					val edge = graph.getConnectionsFrom(node1)?.get(node2) ?: continue
					point.node1 = node1
					point.node2 = node2
					point.edge = edge
					point.position = wheel.position
				}
			}
			train.speed = place.speed
			train.navigation.distanceToDestination = place.left
			heldUntil[place.train] = tick + HOLD
		}
	}

	/** On the client: whether [train]'s places come this way now (Create's own sync ignored: CarriageSyncDataMixin). */
	@JvmStatic
	fun isHeld(train: Train): Boolean {
		val until = heldUntil[train.id] ?: return false
		val tick = Minecraft.getInstance().level?.gameTime ?: return false
		if (tick <= until) return true
		heldUntil.remove(train.id)
		return false
	}
}
