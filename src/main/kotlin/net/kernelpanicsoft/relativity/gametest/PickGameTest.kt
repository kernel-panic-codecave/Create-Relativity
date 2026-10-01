package net.kernelpanicsoft.relativity.gametest

import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.relativity.client.pick.BentPick
import net.kernelpanicsoft.relativity.physics.geodesic.NullRay
import net.kernelpanicsoft.relativity.spacetime.GameFrame
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d

/** Geodesic picking (M9): the crosshair follows the bent light path. */
@Suppress("unused")
class PickGameTest {
	/**
	 * Sight line passing a hole just outside the capture impact parameter (3√3 M): the light bends round it, so a block
	 * on the bent path is picked although the straight line misses it.
	 */
	@GameTest(template = MEDIUM, batch = "relativity_pick", timeoutTicks = 40)
	fun GameTestHelper.pickFollowsBentLight() {
		var tick = 0
		onEachTick {
			if (++tick != 2) return@onEachTick
			val rs = 2.0
			val hole = placeHole(Vec3(12.5, 4.5, 6.5), rs)
			val holeAt = Vec3(hole.x, hole.y, hole.z)
			val eye = holeAt.add(-4.0, 0.0, 5.6) // passes at ≈ 5.6 > 3√3 M ≈ 5.2: bent, not captured
			val zombie = spawnWithNoFreeWill(EntityType.ZOMBIE, relativeVec(eye.subtract(0.0, 1.74, 0.0)))
			zombie.setPos(eye.x, eye.y - zombie.eyeHeight, eye.z)
			zombie.yRot = -90f // looking +x
			zombie.xRot = 0f
			zombie.yHeadRot = -90f
			zombie.yRotO = -90f
			zombie.xRotO = 0f
			val view = zombie.getViewVector(1f)

			// Where the bent ray is after 4 blocks of path.
			val origin = DoubleArray(3)
			val direction = DoubleArray(3)
			GameFrame.toPhysics(Vector3d(eye.x - hole.x, eye.y - hole.y, eye.z - hole.z), origin)
			GameFrame.toPhysics(Vector3d(view.x, view.y, view.z), direction)
			val path = NullRay.trace(hole.spacetime(), origin, direction, 4.0, hole.equatorialExtent(hole.horizonRadius))
			val end = GameFrame.toGame(path.points.last(), 0, Vector3d())
			val target = BlockPos.containing(end.x + hole.x, end.y + hole.y, end.z + hole.z)
			val straight = BlockPos.containing(eye.add(view.scale(4.0)))
			assertTrue(target != straight) { "Bent and straight rays end in the same block ($target); bend too weak for the test" }
			level.setBlock(target, Blocks.STONE.defaultBlockState(), 3)

			val vanilla = zombie.pick(5.0, 1f, false)
			assertTrue(!(vanilla is BlockHitResult && vanilla.type == HitResult.Type.BLOCK && vanilla.blockPos == target)) {
				"The straight sight line already hits $target"
			}
			val bent = BentPick.pick(zombie, 5.0, 3.0, 1f)
			assertTrue(bent is BlockHitResult && bent.type == HitResult.Type.BLOCK && bent.blockPos == target) {
				"Bent pick got ${(bent as? BlockHitResult)?.blockPos ?: bent}, expected the block on the bent path, $target (straight path ends at $straight)"
			}
			zombie.discard()
			clearHoles()
			succeed()
		}
	}
}
