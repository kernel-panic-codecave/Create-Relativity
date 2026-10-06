package net.kernelpanicsoft.relativity.gametest

import net.kernelpanicsoft.archie.events.gametest.AGametestEvents
import net.kernelpanicsoft.archie.gametest.AGameTestEventObject
import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.accretion.Accretion
import net.kernelpanicsoft.relativity.spacetime.BlackHole
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/** A 10×6×10 all-air template, for tests that place blocks. */
internal const val SMALL = "create_relativity:gametest/small"

/** A 20×10×20 all-air template, for tests that need room around a hole. */
internal const val MEDIUM = "create_relativity:gametest/medium"

/**
 * Registers the GameTest suite (only under `runGametest`/`runGametestClient`: the gametest dependency isn't on the
 * production classpath, so every reference to it stays behind [net.kernelpanicsoft.archie.gametest.platform.AGameTestPlatform.isGameTest]).
 */
internal object RelativityGameTest : AGameTestEventObject(CreateRelativity.MOD) {
	override fun AGametestEvents.ArchieGameTestBuilder.handler() {
		server {
			register<ProperTimeGameTest>()
			register<HoleDynamicsGameTest>()
			register<CreateTorsionGameTest>()
			register<ShipGameTest>()
			register<KerrGameTest>()
			register<PickGameTest>()
		}
		client {
			register<LensClientGameTest>()
		}
	}
}

/**
 * Holes pull and dilate far beyond one test's area, so every hole test runs in its own batch (alone), first clearing
 * any hole a failed earlier test left behind and turning growth off so the hole doesn't eat the test's blocks.
 */
internal fun GameTestHelper.placeHole(relative: Vec3, schwarzschildRadius: Double): BlackHole {
	val level = level as ServerLevel
	SpacetimeField.removeBlackHoles(level) { true }
	level.gameRules.getRule(Accretion.GROWTH).set(false, level.server)
	val center = absoluteVec(relative)
	val hole = BlackHole(x = center.x, y = center.y, z = center.z, schwarzschildRadius = schwarzschildRadius)
	SpacetimeField.addBlackHole(level, hole)
	return hole
}

internal fun GameTestHelper.clearHoles() {
	SpacetimeField.removeBlackHoles(level as ServerLevel) { true }
}

/** Centre of the block at [relative], in world coordinates. */
internal fun GameTestHelper.blockCenter(relative: BlockPos): Vec3 = Vec3.atCenterOf(absolutePos(relative))
