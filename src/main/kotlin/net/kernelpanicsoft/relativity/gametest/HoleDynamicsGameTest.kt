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
	 * Regression for debris being flung out: loose debris launched on a circular orbit stays bound. Per-tick kicks
	 * pumped energy into tight orbits; the sub-stepped integrator (plus disk drag) only lets it spiral inward.
	 */
	@GameTest(template = SMALL, batch = "relativity_orbit", timeoutTicks = 100)
	fun GameTestHelper.orbitingDebrisStaysBound() {
		val rs = 1.0
		val hole = placeHole(Vec3(5.5, 3.5, 5.5), rs)
		val r0 = 5.0
		val start = absoluteVec(Vec3(5.5 + r0, 3.5, 5.5))
		val debris = FallingBlockEntity.fall(level, net.minecraft.core.BlockPos.containing(start), Blocks.SAND.defaultBlockState())
		debris.isNoGravity = true
		debris.setPos(start.x, start.y - 0.49, start.z)
		val c = RelativityConfig.speedOfLight(level.dimension())
		val circular = sqrt(c * c * hole.mass / r0) // blocks/s
		debris.deltaMovement = Vec3(0.0, 0.0, circular / 20.0)

		var farthest = 0.0
		onEachTick {
			if (!debris.isRemoved) {
				val center = debris.boundingBox.center
				farthest = maxOf(farthest, center.distanceTo(Vec3(hole.x, hole.y, hole.z)))
			}
		}
		runAfterDelay(80) {
			assertTrue(farthest < r0 * 1.3) { "Debris reached r = $farthest from a circular orbit at $r0 (flung out)" }
			if (!debris.isRemoved) debris.discard()
			clearHoles()
			succeed()
		}
	}
}
