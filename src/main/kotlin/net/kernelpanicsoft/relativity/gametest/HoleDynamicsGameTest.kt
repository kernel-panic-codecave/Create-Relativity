package net.kernelpanicsoft.relativity.gametest

import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.entity.Spaghettification
import net.kernelpanicsoft.relativity.spacetime.SpacetimeAttachments
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

/** Motion and tides around holes (M4–M6). */
@Suppress("unused")
class HoleDynamicsGameTest {
	/** A body held where the tide is several times its tolerance stretches past λ = 1.5 and dies of spaghettification. */
	@GameTest(template = SMALL, batch = "relativity_spaghetti", timeoutTicks = 100)
	fun GameTestHelper.heldZombieIsSpaghettified() {
		placeHole(Vec3(5.5, 3.0, 5.5), 2.0)
		val zombie = spawnWithNoFreeWill(EntityType.ZOMBIE, Vec3(5.5, 3.0 - 0.975, 1.0)) // centre 4.5 blocks out
		val hold = zombie.position()
		var peak = 1.0
		onEachTick {
			if (zombie.isAlive) {
				zombie.setPos(hold)
				zombie.deltaMovement = Vec3.ZERO
				peak = maxOf(peak, SpacetimeAttachments.bodyStretch.get(zombie))
			}
		}
		succeedWhen {
			assertTrue(!zombie.isAlive) { "Zombie still alive (peak stretch $peak)" }
			assertTrue(peak >= 1.5) { "Zombie died without stretching (peak stretch $peak)" }
			val cause = zombie.lastDamageSource?.typeHolder()?.unwrapKey()?.orElse(null)
			assertTrue(cause in Spaghettification.DAMAGE) { "Died of $cause, not spaghettification" }
			clearHoles()
		}
	}

	/**
	 * A creative player held where the zombie above is spaghettified isn't stretched or hurt. (A FakePlayer driven
	 * through the tidal step directly: a mock player joining the server trips another mod's login sync.)
	 */
	@GameTest(template = SMALL, batch = "relativity_creative", timeoutTicks = 140)
	fun GameTestHelper.creativePlayerIsUnhurt() {
		placeHole(Vec3(5.5, 3.0, 5.5), 2.0)
		val player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level, com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "relativity_creative"))
		player.setGameMode(net.minecraft.world.level.GameType.CREATIVE)
		val at = absoluteVec(Vec3(5.5, 3.0 - 0.975, 1.0)) // the zombie's spot: centre 4.5 blocks out
		player.setPos(at)
		var peak = 1.0
		onEachTick {
			player.setPos(at)
			Spaghettification.tick(player, org.joml.Vector3d(at.x, at.y + player.bbHeight / 2.0, at.z))
			peak = maxOf(peak, SpacetimeAttachments.bodyStretch.get(player))
		}
		runAfterDelay(100) {
			assertTrue(player.isAlive && player.health == player.maxHealth) { "Creative player hurt (health ${player.health}, alive ${player.isAlive})" }
			assertTrue(peak == 1.0) { "Creative player stretched (peak $peak)" }
			clearHoles()
			succeed()
		}
	}

	/**
	 * Regression for debris being flung out: loose debris launched on a circular orbit stays bound. Per-tick kicks
	 * pumped energy into tight orbits; the sub-stepped integrator (plus disk drag) only lets it spiral inward.
	 */
	@GameTest(template = MEDIUM, batch = "relativity_orbit", timeoutTicks = 120)
	fun GameTestHelper.orbitingDebrisStaysBound() {
		val rs = 1.0
		val center = Vec3(10.5, 4.5, 10.5)
		val r0 = 5.0
		val setupTick = 2L // chunks start entity-ticking a moment after the test does
		var tick = 0L
		var debris: FallingBlockEntity? = null
		var holeAt = Vec3.ZERO
		var farthest = 0.0
		var nearest = Double.POSITIVE_INFINITY
		onEachTick {
			tick++
			if (tick == setupTick) {
				val hole = placeHole(center, rs)
				holeAt = Vec3(hole.x, hole.y, hole.z)
				val start = absoluteVec(Vec3(center.x + r0, center.y, center.z))
				val c = RelativityConfig.speedOfLight(level.dimension())
				val circular = sqrt(c * c * hole.mass / r0) // blocks/s
				debris = FallingBlockEntity.fall(level, net.minecraft.core.BlockPos.containing(start), Blocks.SAND.defaultBlockState()).also {
					it.isNoGravity = true
					it.setPos(start.x, start.y - 0.49, start.z)
					it.deltaMovement = Vec3(0.0, 0.0, circular / 20.0)
				}
			}
			debris?.takeIf { !it.isRemoved }?.let {
				val r = it.boundingBox.center.distanceTo(holeAt)
				farthest = maxOf(farthest, r)
				nearest = minOf(nearest, r)
			}
			if (tick == setupTick + 80) {
				assertTrue(nearest < r0 + 0.5) { "Debris never moved (nearest approach $nearest)" }
				assertTrue(farthest < r0 * 1.3) { "Debris reached r = $farthest from a circular orbit at $r0 (flung out)" }
				debris?.takeIf { !it.isRemoved }?.discard()
				clearHoles()
				succeed()
			}
		}
	}
}
