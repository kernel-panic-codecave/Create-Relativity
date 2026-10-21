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
					setBlock(core.offset(0, 2, 0), Blocks.REDSTONE_BLOCK)
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

	/**
	 * A ship carrying a running drive (warp 1, facing east) is carried east, spooling up, inside a warp bubble round it,
	 * its clock slowed by its own motion through the bubble alone.
	 */
	@GameTest(template = MEDIUM, batch = "relativity_warp_ship", timeoutTicks = 100)
	fun GameTestHelper.carriesAShip() {
		clearHoles()
		val core = BlockPos(5, 4, 5)
		// Facing east, along the test's longest clear run (it's walled in: blocks stop a warp ship as any other).
		setBlock(core, RelativityBlocks.WARP_CORE.get().defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST))
		val shell = WarpDrive.shell(core)
		for (pos in shell) setBlock(pos, RelativityBlocks.WARP_COIL.get().defaultBlockState())
		setBlock(core.offset(0, 2, 0), Blocks.REDSTONE_BLOCK)
		var ship: dev.ryanhcode.sable.sublevel.ServerSubLevel? = null
		var tick = 0
		// When the drive was first seen running aboard (the spool starts then).
		var running = -1
		onEachTick {
			tick++
			if (tick == 25) {
				val entity = getBlockEntity<WarpCoreBlockEntity>(core)
				assertTrue(entity.formed && entity.signal == 15) { "Drive formed ${entity.formed}, signal ${entity.signal}" }
				getBlockEntity<WarpCoilBlockEntity>(core.relative(Direction.EAST)).setting.value = 10
				val blocks = (shell + core + core.offset(0, 2, 0)).map(::absolutePos)
				val bounds = dev.ryanhcode.sable.companion.math.BoundingBox3i(absolutePos(core.offset(-1, -1, -1)), absolutePos(core.offset(1, 2, 1)))
				ship = dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(level as net.minecraft.server.level.ServerLevel, absolutePos(core), blocks, bounds)
			}
			if (ship != null && running < 0 && net.kernelpanicsoft.relativity.sable.ShipWarp.drives(level).isNotEmpty()) running = tick
			assertTrue(tick < 90 || running >= 0) { "No warp drive running aboard the ship" }
			if (running >= 0 && tick == running + 30) {
				val ship = ship!!
				val handle = requireNotNull(dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(ship))
				val v = handle.getLinearVelocity(org.joml.Vector3d())
				val c = net.kernelpanicsoft.relativity.config.RelativityConfig.speedOfLight(level.dimension())
				val drives = net.kernelpanicsoft.relativity.sable.ShipWarp.drives(level)
				net.kernelpanicsoft.relativity.CreateRelativity.LOGGER.info("Warp ship: velocity {} ({} c), drives {}", v, v.length() / c, drives.map { "${it.direction} ${it.speed}" })
				assertTrue(drives.size == 1) { "${drives.size} warp drives aboard ships" }
				// A second and a half of a ten-second spool to warp 1: 15% of its speed, the way its drive faces.
				val target = WarpScale.speed(1.0) * c
				val along = v.dot(drives[0].direction)
				assertTrue(along > 0.1 * target && along < 0.2 * target) { "Ship at $along blocks/s along its drive, expected about 15% of $target" }
				val bubbles = net.kernelpanicsoft.relativity.spacetime.SpacetimeField.fieldSources(level).filterIsInstance<net.kernelpanicsoft.relativity.spacetime.FieldSource.Ellipsoid>()
				assertTrue(bubbles.size == 1 && bubbles[0].radius(ship.logicalPose().transformPosition(org.joml.Vector3d(ship.plot.boundingBox.minX() + 1.5, ship.plot.boundingBox.minY() + 1.5, ship.plot.boundingBox.minZ() + 1.5))) < 0.9) {
					"Bubbles ${bubbles.size}, the ship's middle not inside"
				}
				val box = ship.plot.boundingBox
				val rate = net.kernelpanicsoft.relativity.time.LocalClocks.rateAt(level, BlockPos(box.minX(), box.minY(), box.minZ()))
				// Only its motion through the bubble (any drift) slows its clock, not the bubble's.
				val through = org.joml.Vector3d(v).sub(org.joml.Vector3d(drives[0].direction).mul(v.dot(drives[0].direction)))
				val expected = kotlin.math.sqrt(1.0 - through.lengthSquared() / (c * c))
				assertTrue(kotlin.math.abs(rate - expected) < 0.03) { "A warp ship's clock runs at $rate, expected $expected (its own motion through the bubble alone)" }
				dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level as net.minecraft.server.level.ServerLevel)?.removeSubLevel(ship, dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason.REMOVED)
				succeed()
			}
		}
	}
}
