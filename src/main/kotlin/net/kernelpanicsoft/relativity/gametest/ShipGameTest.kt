package net.kernelpanicsoft.relativity.gametest

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.companion.math.BoundingBox3i
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.time.LocalClocks
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import kotlin.math.abs
import kotlin.math.sqrt

/** Sable ships in curved spacetime (M7–M8). */
@Suppress("unused")
class ShipGameTest {
	/** Assembles a row of stone along x, [length] long, into a Sable ship. */
	private fun GameTestHelper.assembleRod(length: Int, y: Int = 5, z: Int = 5): ServerSubLevel {
		val level = level as ServerLevel
		val blocks = (0 until length).map { BlockPos(it, y, z) }
		blocks.forEach { setBlock(it, Blocks.STONE) }
		val absolute = blocks.map(::absolutePos)
		val bounds = BoundingBox3i(absolute.first(), absolute.last())
		return SubLevelAssemblyHelper.assembleBlocks(level, absolute[length / 2], absolute, bounds)
	}

	private fun GameTestHelper.remove(ship: ServerSubLevel) {
		if (!ship.isRemoved) SubLevelContainer.getContainer(level as ServerLevel)?.removeSubLevel(ship, SubLevelRemovalReason.REMOVED)
	}

	private fun handle(ship: ServerSubLevel) = requireNotNull(RigidBodyHandle.of(ship)) { "Ship has no physics body" }

	/** A block in the ship's plot (where its block entities live), to read the ship's clock at. */
	private fun plotBlock(ship: ServerSubLevel): BlockPos {
		val box = ship.plot.boundingBox
		return BlockPos(box.minX(), box.minY(), box.minZ())
	}

	/**
	 * Regression: a spinning ship's clock stays at ≈ 1. Its velocity was taken at its world position read as plot
	 * coordinates (millions of blocks from its pivot), so any spin gave a speed past c and froze the ship.
	 */
	@GameTest(template = SMALL, batch = "relativity_ship_clock", timeoutTicks = 60)
	fun GameTestHelper.spinningShipKeepsTime() {
		clearHoles()
		val ship = assembleRod(5)
		handle(ship).addLinearAndAngularVelocity(Vector3d(), Vector3d(0.0, 1.0, 0.0))
		runAfterDelay(2) {
			val rate = LocalClocks.rateAt(level, plotBlock(ship))
			assertTrue(rate > 0.99) { "Ship spinning at 1 rad/s has clock rate $rate, expected ≈ 1" }
			remove(ship)
			succeed()
		}
	}

	/** The twin paradox: a ship moving at v keeps time at √(1 − v²/c²). */
	@GameTest(template = SMALL, batch = "relativity_ship_clock", timeoutTicks = 60)
	fun GameTestHelper.fastShipClockRunsSlow() {
		clearHoles()
		val ship = assembleRod(5, z = 2)
		val c = RelativityConfig.speedOfLight(level.dimension())
		handle(ship).addLinearAndAngularVelocity(Vector3d(0.6 * c, 0.0, 0.0), Vector3d())
		runAfterDelay(1) {
			val v = handle(ship).getLinearVelocity(Vector3d()).length()
			assertTrue(v > 0.5 * c) { "Ship slowed to $v blocks/s before it could be timed" }
			val expected = sqrt(1.0 - v * v / (c * c))
			val rate = LocalClocks.rateAt(level, plotBlock(ship))
			assertTrue(abs(rate - expected) < 0.03) { "Ship at ${v / c} c has clock rate $rate, expected $expected" }
			remove(ship)
			succeed()
		}
	}

	/**
	 * Gravity-gradient torque turns a ship's long axis toward a hole. A rod along x with the hole 20 blocks off at 45°
	 * toward +z: τ = (3GM/r³) r̂ × (I·r̂) points along −y. Without it the rod has no reason to turn either way.
	 */
	@GameTest(template = SMALL, batch = "relativity_ship_torque", timeoutTicks = 60)
	fun GameTestHelper.shipTurnsTowardHole() {
		val ship = assembleRod(9)
		val center = Vec3(4.5, 5.5, 5.5)
		val offset = 20.0 / sqrt(2.0)
		placeHole(Vec3(center.x + offset, center.y, center.z + offset), 2.0)
		runAfterDelay(8) {
			val spin = handle(ship).getAngularVelocity(Vector3d())
			assertTrue(spin.y < -0.02) { "Rod's spin about y is ${spin.y} rad/s, expected it to turn toward the hole (negative)" }
			remove(ship)
			clearHoles()
			succeed()
		}
	}
}
