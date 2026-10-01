package net.kernelpanicsoft.relativity.gametest

import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.relativity.spacetime.BlackHole
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/** Spinning (Kerr) holes (M9). */
@Suppress("unused")
class KerrGameTest {
	/**
	 * Drops debris from rest at 3 r_s on the hole's +x side and returns its sideways (z) drift after [ticks], while
	 * it's still outside: frame dragging carries it around with the spin.
	 */
	private fun GameTestHelper.dragDrift(spin: Double, ticks: Long, check: (Double) -> Unit) {
		val rs = 2.0
		val center = Vec3(10.5, 4.5, 10.5)
		val setupTick = 2L // chunks start entity-ticking a moment after the test does
		var tick = 0L
		var debris: FallingBlockEntity? = null
		var holeAt = Vec3.ZERO
		var angle = 0.0
		val trace = StringBuilder()
		onEachTick {
			tick++
			if (tick == setupTick) {
				placeHole(center, rs)
				val level = level as ServerLevel
				val hole = SpacetimeField.blackHoles(level).single()
				SpacetimeField.updateBlackHole(level, hole.id) { it.copy(spin = spin) }
				holeAt = absoluteVec(center)
				val start = absoluteVec(Vec3(center.x + 3.0 * rs, center.y, center.z))
				debris = FallingBlockEntity.fall(level, BlockPos.containing(start), Blocks.SAND.defaultBlockState()).also {
					it.isNoGravity = true
					it.setPos(start.x, start.y - 0.49, start.z)
					it.deltaMovement = Vec3.ZERO
				}
			}
			debris?.takeIf { !it.isRemoved }?.let {
				val dx = it.x - holeAt.x
				val dz = it.z - holeAt.z
				angle = kotlin.math.atan2(dz, dx)
				trace.append("[r=${"%.2f".format(kotlin.math.sqrt(dx * dx + dz * dz))} φ=${"%.3f".format(angle)}] ")
			}
			if (tick == setupTick + ticks) {
				try {
					check(angle)
				} catch (failure: net.minecraft.gametest.framework.GameTestAssertException) {
					throw net.minecraft.gametest.framework.GameTestAssertException("${failure.message}; fall: $trace")
				}
				debris?.takeIf { !it.isRemoved }?.discard()
				clearHoles()
				succeed()
			}
		}
	}

	/*
	 * Debris let go at rest (in these Kerr–Schild coordinates "at rest" carries a little retrograde angular momentum, so
	 * it first drifts slightly the other way) is swept around with the spin by the time it reaches the horizon. Positive
	 * spin is counter-clockwise about physics +z (game up): +x turns toward −z, so its game angle atan2(z, x) goes negative.
	 */

	@GameTest(template = MEDIUM, batch = "relativity_drag_prograde", timeoutTicks = 60)
	fun GameTestHelper.positiveSpinDragsMatterAround() = dragDrift(0.9, 8) { angle ->
		assertTrue(angle < -0.05) { "Debris reached the horizon of a +0.9 hole at angle $angle; expected swept toward −z (negative)" }
	}

	@GameTest(template = MEDIUM, batch = "relativity_drag_retrograde", timeoutTicks = 60)
	fun GameTestHelper.negativeSpinDragsTheOtherWay() = dragDrift(-0.9, 8) { angle ->
		assertTrue(angle > 0.05) { "Debris reached the horizon of a −0.9 hole at angle $angle; expected swept toward +z (positive)" }
	}

	/** Control: a non-spinning hole pulls straight in. */
	@GameTest(template = MEDIUM, batch = "relativity_drag_none", timeoutTicks = 60)
	fun GameTestHelper.nonSpinningHolePullsStraightIn() = dragDrift(0.0, 8) { angle ->
		assertTrue(abs(angle) < 1e-3) { "Debris reached the horizon of a non-spinning hole at angle $angle; expected straight in" }
	}
}
