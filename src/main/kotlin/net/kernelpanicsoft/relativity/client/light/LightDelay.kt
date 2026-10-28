package net.kernelpanicsoft.relativity.client.light

import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.mixin.client.network.PacketEntityIdAccessors
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.core.BlockPos
import net.minecraft.network.PacketListener
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundAnimatePacket
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import java.util.PriorityQueue

/**
 * Light-travel delay. At game c, light from 64 blocks away takes a second, so each client sees the world as it was when
 * the light left: updates about distant blocks and entities are held for distance / c (from where the player is when
 * they arrive) and then applied in order. The client's own world is delayed, so what's drawn and what's clicked agree;
 * the server stays authoritative, and within reach the delay is under a tick. Nothing about the player themself (or
 * what they ride) is delayed, nor are chunk loads or packets without a position.
 */
object LightDelay {
	private const val TICKS_PER_SECOND = 20.0

	/** A held packet; [order] keeps arrival order among equal due ticks. */
	private class Held(val due: Long, val order: Long, val packet: Packet<ClientGamePacketListener>, val listener: ClientPacketListener)

	private val queue = PriorityQueue<Held>(compareBy<Held>({ it.due }, { it.order }))
	private var arrivals = 0L

	/** Last due tick per source, so a source's later updates never overtake its earlier ones. */
	private val entityDue = HashMap<Long, Long>()
	private val blockDue = HashMap<Long, Long>()

	/** Client ticks while in a level and not paused: coordinate time on this side. */
	private var ticks = 0L
	private var level: ClientLevel? = null

	private val enabled: Boolean get() = RelativityConfig.Client.Render.lightDelay

	/** Network thread: hands delayable packets to the main thread in arrival order (other packets queue the same way). */
	@JvmStatic
	fun intercept(packet: Packet<*>, listener: PacketListener): Boolean {
		if (listener !is ClientPacketListener || !enabled) return false
		if (packet !is ClientboundBundlePacket && !delayable(packet)) return false
		Minecraft.getInstance().execute { receive(packet, listener) }
		return true
	}

	private fun delayable(packet: Packet<*>) = when (packet) {
		is ClientboundBlockUpdatePacket, is ClientboundSectionBlocksUpdatePacket, is ClientboundBlockEntityDataPacket,
		is ClientboundBlockEventPacket, is ClientboundLevelEventPacket, is ClientboundBlockDestructionPacket,
		is ClientboundAddEntityPacket, is ClientboundMoveEntityPacket, is ClientboundTeleportEntityPacket,
		is ClientboundSetEntityMotionPacket, is ClientboundRotateHeadPacket, is ClientboundAnimatePacket,
		is ClientboundEntityEventPacket, is ClientboundSetEntityDataPacket, is ClientboundSetEquipmentPacket,
		is ClientboundHurtAnimationPacket, is ClientboundRemoveEntitiesPacket -> true
		else -> false
	}

	@Suppress("UNCHECKED_CAST")
	private fun receive(packet: Packet<*>, listener: ClientPacketListener) = receiveOne(packet as Packet<ClientGamePacketListener>, listener)

	/**
	 * Holds [game] until its source's light arrives, or applies it now. A bundle is one unit, held for its first
	 * sub-packet with a source and then applied whole, in order: its packets belong together (an entity and its spawn
	 * data: applied before the entity arrives, that's dropped and the entity comes without it; Create's trains vanished).
	 */
	private fun receiveOne(game: Packet<ClientGamePacketListener>, listener: ClientPacketListener) {
		if (game is ClientboundRemoveEntitiesPacket && game.entityIds.size > 1) {
			// Each removal waits for its own entity's light.
			for (id in game.entityIds.toIntArray()) receiveOne(ClientboundRemoveEntitiesPacket(id), listener)
			return
		}
		val minecraft = Minecraft.getInstance()
		val player = minecraft.player
		val level = minecraft.level
		if (player == null || level == null || !enabled) {
			game.handle(listener)
			return
		}
		syncLevel(level)
		val packet = (game as? ClientboundBundlePacket)?.subPackets()?.firstOrNull { sourceOf(it, level) != null } ?: game
		val source = sourceOf(packet, level)
		if (source == null) {
			game.handle(listener)
			return
		}
		val (key, position) = source
		val dueTable = if (key.entity) entityDue else blockDue
		val after = dueTable[key.id] ?: Long.MIN_VALUE
		val delay = if (key.entity && isSelf(key.id.toInt(), player)) 0L else delayTicks(position, player.eyePosition, level)
		val due = maxOf(ticks + delay, after)
		if (due <= ticks) {
			game.handle(listener)
			return
		}
		dueTable[key.id] = due
		// A section's changes are its blocks' too: what comes after for any of them (a block entity's data, made by the
		// change) waits behind it. (Its own delay is its centre's: keyed apart, a block entity's data arrived before the
		// change that made it, and was dropped: Create's curves, laid, came without them.)
		if (packet is ClientboundSectionBlocksUpdatePacket) packet.runUpdates { pos, _ -> blockDue.merge(pos.asLong(), due, ::maxOf) }
		queue.add(Held(due, arrivals++, game, listener))
	}

	private class Key(val entity: Boolean, val id: Long)

	/** Which source a packet is about, and where it is now (or will appear); null if it has no place in the world. */
	private fun sourceOf(packet: Packet<*>, level: ClientLevel): Pair<Key, Vec3?>? {
		fun block(pos: BlockPos) = Key(false, pos.asLong()) to Vec3.atCenterOf(pos)
		fun entity(id: Int) = Key(true, id.toLong()) to level.getEntity(id)?.boundingBox?.center
		return when (packet) {
			is ClientboundBlockUpdatePacket -> block(packet.pos)
			is ClientboundSectionBlocksUpdatePacket ->
				block((packet as PacketEntityIdAccessors.SectionBlocks).`create_relativity$getSectionPos`().center())
			is ClientboundBlockEntityDataPacket -> block(packet.pos)
			is ClientboundBlockEventPacket -> block(packet.pos)
			is ClientboundLevelEventPacket -> block(packet.pos)
			is ClientboundBlockDestructionPacket -> block(packet.pos)
			is ClientboundAddEntityPacket -> Key(true, packet.id.toLong()) to Vec3(packet.x, packet.y, packet.z)
			is ClientboundMoveEntityPacket -> entity((packet as PacketEntityIdAccessors.MoveEntity).`create_relativity$getEntityId`())
			is ClientboundTeleportEntityPacket -> entity(packet.id)
			is ClientboundSetEntityMotionPacket -> entity(packet.id)
			is ClientboundRotateHeadPacket -> entity((packet as PacketEntityIdAccessors.RotateHead).`create_relativity$getEntityId`())
			is ClientboundAnimatePacket -> entity(packet.id)
			is ClientboundEntityEventPacket -> entity((packet as PacketEntityIdAccessors.EntityEvent).`create_relativity$getEntityId`())
			is ClientboundSetEntityDataPacket -> entity(packet.id())
			is ClientboundSetEquipmentPacket -> entity(packet.entity)
			is ClientboundHurtAnimationPacket -> entity(packet.id())
			is ClientboundRemoveEntitiesPacket -> packet.entityIds.firstOrNull()?.let { entity(it) }
			else -> null
		}
	}

	/** The player and anything they ride or carry are here, not away. */
	private fun isSelf(id: Int, player: net.minecraft.world.entity.player.Player): Boolean {
		if (id == player.id) return true
		val entity = player.level().getEntity(id) ?: return false
		return entity === player.rootVehicle || entity.hasIndirectPassenger(player) || player.hasIndirectPassenger(entity)
	}

	/** Light-travel time from [source] to the eye, in ticks; 0 if unknown (an entity not seen yet). */
	private fun delayTicks(source: Vec3?, eye: Vec3, level: ClientLevel): Long {
		source ?: return 0L
		val c = RelativityConfig.speedOfLight(level.dimension())
		return (source.distanceTo(eye) / c * TICKS_PER_SECOND).toLong()
	}

	/** A new level (dimension change, respawn) drops what was in flight from the old one. */
	private fun syncLevel(level: ClientLevel) {
		if (this.level === level) return
		this.level = level
		clear()
	}

	private fun clear() {
		queue.clear()
		entityDue.clear()
		blockDue.clear()
	}

	fun onClientTick(event: ClientTickEvent.Pre) {
		val minecraft = Minecraft.getInstance()
		val level = minecraft.level ?: return
		if (minecraft.isPaused) return
		syncLevel(level)
		ticks++
		if (!enabled) {
			flush()
			return
		}
		while (true) {
			val next = queue.peek() ?: break
			if (next.due > ticks) break
			queue.poll()
			apply(next)
		}
		if (queue.isEmpty()) {
			entityDue.clear()
			blockDue.clear()
		}
	}

	/** Light delay switched off: apply everything held, in order. */
	private fun flush() {
		while (true) apply(queue.poll() ?: break)
		entityDue.clear()
		blockDue.clear()
	}

	private fun apply(held: Held) {
		if (!held.listener.connection.isConnected) return
		held.packet.handle(held.listener)
	}

	fun onLoggingOut(event: ClientPlayerNetworkEvent.LoggingOut) {
		clear()
		level = null
	}
}
