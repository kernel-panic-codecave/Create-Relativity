package net.kernelpanicsoft.relativity.gametest

import com.simibubi.create.content.trains.track.TrackBlock
import com.simibubi.create.content.trains.track.TrackPlacement
import com.simibubi.create.content.trains.track.TrackShape
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.relativity.content.Maglev
import net.kernelpanicsoft.relativity.content.RelativityBlocks
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.util.FakePlayerFactory

/** Maglev track (warp trains): it joins only maglev track. */
@Suppress("unused")
class MaglevGameTest {
	/**
	 * Laid by a player (facing along z): maglev in line with Create's track isn't placed, beside it or on its own it is;
	 * no curve joins the two.
	 */
	@GameTest(template = MEDIUM, batch = "relativity_maglev", timeoutTicks = 20)
	fun GameTestHelper.joinsOnlyMaglev() {
		val standard = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:track")).defaultBlockState()
			.setValue(TrackBlock.SHAPE, TrackShape.ZO)
		for (x in 0..9) for (z in 0..9) setBlock(BlockPos(x, 0, z), Blocks.STONE)
		setBlock(BlockPos(2, 1, 2), standard)

		val player = FakePlayerFactory.getMinecraft(level)
		val stack = ItemStack(RelativityBlocks.MAGLEV_TRACK_ITEM.get(), 64)
		player.setItemInHand(InteractionHand.MAIN_HAND, stack)
		fun lay(relative: BlockPos): Boolean {
			val ground = absolutePos(relative.below())
			player.moveTo(ground.x + 0.5, ground.y + 1.0, ground.z - 2.5, 0f, 30f) // facing +z
			val hit = BlockHitResult(Vec3.atCenterOf(ground).add(0.0, 0.5, 0.0), Direction.UP, ground, false)
			stack.useOn(UseOnContext(player, InteractionHand.MAIN_HAND, hit))
			return getBlockState(relative).`is`(RelativityBlocks.MAGLEV_TRACK.get())
		}

		assertTrue(!lay(BlockPos(2, 1, 3))) { "Maglev was laid in line with Create's track, joining it" }
		assertTrue(!lay(BlockPos(2, 1, 1))) { "Maglev was laid in line with Create's track (behind it), joining it" }
		assertTrue(lay(BlockPos(3, 1, 2))) { "Maglev wasn't laid beside Create's track (parallel, not joining)" }
		assertTrue(lay(BlockPos(6, 1, 6))) { "Maglev wasn't laid on its own" }
		assertTrue(lay(BlockPos(6, 1, 7))) { "Maglev wasn't laid in line with maglev" }

		// A curve begun on maglev (selected as a player selects it) toward Create's track, 4 across and 5 along.
		val start = absolutePos(BlockPos(6, 1, 7))
		player.moveTo(start.x + 0.5, start.y + 1.0, start.z + 0.5, 180f, 30f) // facing -z, toward it
		stack.useOn(UseOnContext(player, InteractionHand.MAIN_HAND, BlockHitResult(Vec3.atCenterOf(start), Direction.UP, start, false)))
		assertTrue(stack.has(com.simibubi.create.AllDataComponents.TRACK_CONNECTING_FROM)) { "Clicking maglev track with maglev track didn't select it" }
		val refused = TrackPlacement.tryConnect(level, player, absolutePos(BlockPos(2, 1, 2)), standard, stack, false, false)
		// (Its message is package-private.)
		val message = TrackPlacement.PlacementInfo::class.java.getDeclaredField("message").apply { isAccessible = true }.get(refused)
		assertTrue(message == "track.create_relativity.other_type") { "A maglev curve to Create's track wasn't refused ($message)" }
		val valid = TrackPlacement.PlacementInfo::class.java.getDeclaredField("valid").apply { isAccessible = true }.get(refused)
		assertTrue(valid == false) { "A maglev curve to Create's track was refused but left valid" }
		val maglev = getBlockState(BlockPos(6, 1, 6))
		assertTrue(!Maglev.refusesConnection(level, maglev, stack)) { "A maglev curve to maglev was refused" }
		succeed()
	}

	/** A train casing on maglev track lays a maglev bogey (on Create's, Create's), and it runs only on maglev track. */
	@GameTest(template = MEDIUM, batch = "relativity_maglev", timeoutTicks = 20)
	fun GameTestHelper.maglevBogey() {
		val maglev = RelativityBlocks.MAGLEV_TRACK.get().defaultBlockState()
		val standard = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:track")).defaultBlockState()
		setBlock(BlockPos(2, 1, 2), maglev)
		setBlock(BlockPos(5, 1, 2), standard)
		val onMaglev = (maglev.block as TrackBlock).getBogeyAnchor(level, absolutePos(BlockPos(2, 1, 2)), maglev)
		val onStandard = (standard.block as TrackBlock).getBogeyAnchor(level, absolutePos(BlockPos(5, 1, 2)), standard)
		assertTrue(onMaglev.`is`(RelativityBlocks.MAGLEV_BOGEY.get())) { "Maglev track anchors ${onMaglev.block}, not a maglev bogey" }
		assertTrue(onStandard.`is`(com.simibubi.create.AllBlocks.SMALL_BOGEY.get())) { "Create's track anchors ${onStandard.block}, not its small bogey" }
		val bogey = RelativityBlocks.MAGLEV_BOGEY.get()
		assertTrue(bogey.getTrackType(bogey.defaultStyle) === Maglev.TRACK_TYPE) { "The maglev bogey runs on ${bogey.getTrackType(bogey.defaultStyle)}" }
		setBlock(BlockPos(2, 2, 2), onMaglev)
		val entity = getBlockEntity<com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity>(BlockPos(2, 2, 2))
		assertTrue(entity.style === net.kernelpanicsoft.relativity.content.MaglevBogey.STYLE) { "The maglev bogey's style is ${entity.style.id}" }
		succeed()
	}
}
