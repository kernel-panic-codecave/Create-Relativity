package net.kernelpanicsoft.relativity.gametest

import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.relativity.content.RelativityBlocks
import net.kernelpanicsoft.relativity.content.WarpCoilBlockEntity
import net.kernelpanicsoft.relativity.content.WarpCoreBlockEntity
import net.kernelpanicsoft.relativity.content.WarpDrive
import net.kernelpanicsoft.relativity.physics.WarpScale
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties

/** The warp drive: formed when its core is wrapped in coils, set on its control coil, throttled by redstone. */
@Suppress("unused")
class WarpDriveGameTest {
	@GameTest(template = MEDIUM, batch = "relativity_warp", timeoutTicks = 120)
	fun GameTestHelper.formsSetsAndThrottles() {
		val core = BlockPos(6, 3, 6)
		setBlock(core, RelativityBlocks.WARP_CORE.get().defaultBlockState().setValue(BlockStateProperties.FACING, Direction.NORTH))
		val shell = WarpDrive.shell(core)
		for (pos in shell.drop(1)) setBlock(pos, RelativityBlocks.WARP_COIL.get().defaultBlockState())
		var stage = 0
		var tick = 0
		onEachTick {
			tick++
			val entity = getBlockEntity<WarpCoreBlockEntity>(core)
			when (stage) {
				0 -> if (tick == 25) {
					assertTrue(!entity.formed) { "Formed with a coil missing" }
					setBlock(shell.first(), RelativityBlocks.WARP_COIL.get().defaultBlockState())
					stage = 1
				}
				1 -> if (tick == 50) {
					assertTrue(entity.formed) { "Not formed once complete" }
					assertTrue(shell.all { getBlockState(it).getValue(WarpDrive.FORMED) }) { "Coils not all formed" }
					val control = getBlockEntity<WarpCoilBlockEntity>(core.relative(Direction.NORTH))
					assertTrue(control.controlFace == Direction.NORTH) { "The control coil's face is ${control.controlFace}" }
					control.setting.value = 45
					assertTrue(entity.maxWarp == 4.5 && entity.warp == 0.0) { "Unpowered: max ${entity.maxWarp}, warp ${entity.warp}" }
					// A redstone block against a coil: full throttle.
					setBlock(core.offset(2, 0, 0), Blocks.REDSTONE_BLOCK)
					stage = 2
				}
				2 -> if (tick == 75) {
					assertTrue(entity.signal == 15 && entity.warp == 4.5) { "Powered: signal ${entity.signal}, warp ${entity.warp}" }
					assertTrue(kotlin.math.abs(entity.speed - WarpScale.speed(4.5)) < 1e-9) { "Speed ${entity.speed} c" }
					setBlock(shell.last(), Blocks.AIR)
					stage = 3
				}
				3 -> if (tick == 100) {
					assertTrue(!entity.formed && entity.maxWarp == 0.0) { "Still formed with a coil gone" }
					succeed()
				}
			}
		}
	}
}
