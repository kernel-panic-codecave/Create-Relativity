package net.kernelpanicsoft.relativity.time

import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.minecraft.client.Minecraft
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.Entity
import net.neoforged.neoforge.event.tick.ServerTickEvent
import org.joml.Vector3d
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Singleplayer reference frame: the world runs relative to the player's own clock. Server ticks are coordinate time;
 * with the player's dτ/dt = p, the server runs at 20/p ticks per second and the player's own ticks skip at rate p, so
 * the player still gets 20 ticks per second of their proper time while every other clock (at its absolute rate)
 * races by 1/p. Only with one player who can't be joined: a shared server can't speed up the world for one person.
 */
object ReferenceFrame {
	private const val NORMAL_RATE = 20f

	/** The player's clock rate the server is currently following; 1 when the frame is inactive. */
	var playerRate = 1.0
		private set

	private val clocks = WeakHashMap<Entity, LocalClocks.Clock>()

	fun onServerTick(event: ServerTickEvent.Post) {
		val server = event.server
		val target = targetRate(server)
		// Small changes aren't worth a tick-rate packet each tick.
		if (abs(target - playerRate) < 0.005 && !(target == 1.0 && playerRate != 1.0)) return
		playerRate = target
		server.tickRateManager().setTickRate((NORMAL_RATE / target).toFloat())
	}

	private fun targetRate(server: MinecraftServer): Double {
		if (!LocalClocks.enabled || !server.isSingleplayer || server.isPublished) return 1.0
		val player = server.playerList.players.singleOrNull() ?: return 1.0
		if (player.isSpectator) return 1.0
		val center = Vector3d(player.x, player.y + player.bbHeight / 2.0, player.z)
		val rate = LocalClocks.rateAt(player.level(), center)
		val floor = 1.0 / RelativityConfig.Server.Time.maxFrameSpeedup
		return rate.coerceAtLeast(floor)
	}

	/** Server side: whether the frame's player gets their own tick this server tick. */
	fun playerTicks(player: Entity): Boolean {
		if (playerRate >= 1.0) {
			clocks.remove(player)
			return true
		}
		return clocks.getOrPut(player, LocalClocks::Clock).advance(playerRate)
	}

	/**
	 * Client side: whether the local player ticks this client tick. The client runs at the server's tick rate, so
	 * the player's rate is just 20 / that rate; only in singleplayer, where the server set it for the frame.
	 */
	fun localPlayerTicks(player: Entity): Boolean {
		val minecraft = Minecraft.getInstance()
		val level = minecraft.level ?: return true
		if (!minecraft.hasSingleplayerServer()) return true
		val rate = NORMAL_RATE / level.tickRateManager().tickrate()
		if (rate >= 0.9999) {
			clocks.remove(player)
			return true
		}
		return clocks.getOrPut(player, LocalClocks::Clock).advance(rate.toDouble())
	}
}
