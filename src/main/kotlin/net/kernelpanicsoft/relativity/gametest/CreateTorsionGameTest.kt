package net.kernelpanicsoft.relativity.gametest

import com.simibubi.create.AllBlocks
import com.simibubi.create.content.kinetics.base.KineticBlockEntity
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.Vec3

/** Create integration (M8). */
@Suppress("unused")
class CreateTorsionGameTest {
	/** A shaft line running down a gravity well twists: its network carries torsion stress though shafts have none. */
	@GameTest(template = SMALL, batch = "relativity_torsion", timeoutTicks = 100)
	fun GameTestHelper.shaftDownAWellTwists() {
		placeHole(Vec3(5.5, 3.5, 0.5), 2.0)
		// Motor at the far end (z = 9), shafts running toward the hole down to z = 3 (3 blocks from it).
		setBlock(BlockPos(5, 3, 9), AllBlocks.CREATIVE_MOTOR.defaultState.setValue(CreativeMotorBlock.FACING, Direction.NORTH))
		for (z in 3..8) setBlock(BlockPos(5, 3, z), AllBlocks.SHAFT.defaultState.setValue(BlockStateProperties.AXIS, Direction.Axis.Z))

		succeedWhen {
			val shaft = getBlockEntity<KineticBlockEntity>(BlockPos(5, 3, 3))
			assertTrue(shaft.speed != 0f) { "Shaft line isn't turning" }
			val network = shaft.getOrCreateNetwork()
			assertTrue(network != null) { "Shaft has no network" }
			val stress = network!!.calculateStress()
			assertTrue(stress > 1f) { "Network stress $stress: expected torsion from the clock-rate spread" }
			clearHoles()
		}
	}
}
