package net.kernelpanicsoft.relativity.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.NetworkChannel
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.spacetime.BlackHole
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent

object RelativityNetwork {
	private val CHANNEL by lazy { NetworkChannel(CreateRelativity.MOD % "main") }

	/** Full list of a dimension's black holes; small, so it's resent whole on any change. */
	@Serializable
	data class SyncBlackHoles(val dimension: String, val holes: List<BlackHole>)

	fun init() {
		CHANNEL.clientbound<SyncBlackHoles> { packet, _ ->
			val key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(packet.dimension))
			SpacetimeField.setClientHoles(key, packet.holes)
		}
		CHANNEL.register()

		NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent::class.java) { syncTo(it.entity) }
		NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerChangedDimensionEvent::class.java) { syncTo(it.entity) }
		NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerRespawnEvent::class.java) { syncTo(it.entity) }
	}

	fun syncToDimension(level: ServerLevel, holes: List<BlackHole>) =
		CHANNEL.toPlayersInDimension(level, SyncBlackHoles(level.dimension().location().toString(), holes))

	private fun syncTo(player: net.minecraft.world.entity.player.Player) {
		val serverPlayer = player as? ServerPlayer ?: return
		val level = serverPlayer.serverLevel()
		CHANNEL.toPlayer(serverPlayer, SyncBlackHoles(level.dimension().location().toString(), SpacetimeField.blackHoles(level)))
	}
}
