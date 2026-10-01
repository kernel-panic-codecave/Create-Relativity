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

	/**
	 * Server players' clocks (server thread only). Entity equality is by id, and in singleplayer the client and server
	 * player share one, so the local player's clock must not live in the same map.
	 */
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

	/** The local player's position and rotation at the start of their last own tick, kept across skipped ticks. */
	private class Pose(val x: Double, val y: Double, val z: Double, val xRot: Float, val yRot: Float, val bodyRot: Float, val headRot: Float)

	private var lastOwnPose: Pose? = null

	/** The local player's clock (client thread only). */
	private var localClock: LocalClocks.Clock? = null

	/**
	 * Client side, the local player's tick (old position already stepped by vanilla): on their own ticks remember where
	 * they started; on skipped ticks put that back, so the render interpolates across the whole of their (longer) tick.
	 */
	fun localPlayerTick(player: net.minecraft.world.entity.LivingEntity): Boolean {
		val due = localPlayerTicks(player)
		if (due) {
			lastOwnPose = Pose(player.xo, player.yo, player.zo, player.xRotO, player.yRotO, player.yBodyRotO, player.yHeadRotO)
		} else {
			lastOwnPose?.let {
				player.xo = it.x; player.yo = it.y; player.zo = it.z
				player.xOld = it.x; player.yOld = it.y; player.zOld = it.z
				player.xRotO = it.xRot; player.yRotO = it.yRot
				player.yBodyRotO = it.bodyRot; player.yHeadRotO = it.headRot
			}
		}
		return due
	}

	/** Partial tick for drawing the local player (camera, body, hand): progress across their own tick. */
	@JvmStatic
	fun playerPartial(partialTick: Float): Float {
		val clock = localClock ?: return partialTick
		if (clock.rate >= 1.0) return partialTick
		// Past 1 extrapolates until the player's own tick arrives, so steady motion carries on instead of holding.
		return (clock.fraction + partialTick * clock.rate).coerceAtMost(RenderTime.MAX_PARTIAL).toFloat()
	}

	/**
	 * Client side: whether the local player ticks this client tick. The client runs at the server's tick rate, so
	 * the player's rate is just 20 / that rate; only in singleplayer, where the server set it for the frame.
	 */
	private fun localPlayerTicks(player: Entity): Boolean {
		val minecraft = Minecraft.getInstance()
		val level = minecraft.level ?: return true
		if (!minecraft.hasSingleplayerServer()) return true
		val rate = NORMAL_RATE / level.tickRateManager().tickrate()
		if (rate >= 0.9999) {
			localClock = null
			return true
		}
		val clock = localClock ?: LocalClocks.Clock().also { localClock = it }
		return clock.advance(rate.toDouble())
	}
}
