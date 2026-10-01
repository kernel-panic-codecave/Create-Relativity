package net.kernelpanicsoft.relativity.gametest

import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.relativity.time.LocalClocks
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.entity.FurnaceBlockEntity
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/** Local tick rates from proper time (M7). */
@Suppress("unused")
class ProperTimeGameTest {
	/** A furnace's cook progress advances at its dτ/dt, which matches Schwarzschild's √(1 − r_s/r) for a static clock. */
	@GameTest(template = SMALL, batch = "relativity_furnace", timeoutTicks = 200)
	fun GameTestHelper.furnaceCooksAtItsClockRate() {
		val rs = 2.0
		placeHole(Vec3(5.5, 3.5, 5.5), rs)
		val furnacePos = BlockPos(5, 3, 8)
		setBlock(furnacePos, Blocks.FURNACE)
		val furnace = getBlockEntity<FurnaceBlockEntity>(furnacePos)
		furnace.setItem(0, ItemStack(Items.RAW_IRON, 8))
		furnace.setItem(1, ItemStack(Items.COAL, 8))

		val r = 3.0
		val expectedRate = sqrt(1.0 - rs / r)
		val rate = LocalClocks.rateAt(level, absolutePos(furnacePos))
		assertTrue(abs(rate - expectedRate) < 0.01) { "Clock rate $rate at r = $r, expected √(1 − r_s/r) = $expectedRate" }

		val ticks = 120
		runAfterDelay(ticks.toLong()) {
			val cookTime = furnace.saveWithoutMetadata(level.registryAccess()).getInt("CookTime")
			val expected = ticks * expectedRate
			assertTrue(abs(cookTime - expected) < 4.0) { "Cooked $cookTime ticks in $ticks world ticks, expected ≈ $expected" }
			clearHoles()
			succeed()
		}
	}

	/** A button's release is a scheduled tick: near a hole it stays pressed 1/(dτ/dt) times longer. */
	@GameTest(template = SMALL, batch = "relativity_button", timeoutTicks = 120)
	fun GameTestHelper.scheduledTicksWaitInLocalTime() {
		val rs = 2.0
		placeHole(Vec3(5.5, 3.5, 5.5), rs)
		val supportPos = BlockPos(5, 2, 8)
		val buttonPos = BlockPos(5, 3, 8)
		setBlock(supportPos, Blocks.STONE)
		setBlock(buttonPos, Blocks.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR).setValue(ButtonBlock.FACING, Direction.NORTH))
		pressButton(buttonPos)

		val rate = sqrt(1.0 - rs / 3.0)
		val dilated = (20 / rate).toLong() // a stone button holds for 20 local ticks: ≈ 35 world ticks here
		runAfterDelay(25) {
			assertTrue(getBlockState(buttonPos).getValue(ButtonBlock.POWERED)) { "Button released after 25 world ticks; expected to hold for ≈ $dilated" }
		}
		runAfterDelay(dilated + 5) {
			assertTrue(!getBlockState(buttonPos).getValue(ButtonBlock.POWERED)) { "Button still pressed after ${dilated + 5} world ticks" }
			clearHoles()
			succeed()
		}
	}
}
