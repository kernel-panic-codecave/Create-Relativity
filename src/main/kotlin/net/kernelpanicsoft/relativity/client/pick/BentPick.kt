package net.kernelpanicsoft.relativity.client.pick

import net.kernelpanicsoft.relativity.physics.geodesic.NullRay
import net.kernelpanicsoft.relativity.spacetime.BlackHole
import net.kernelpanicsoft.relativity.spacetime.GameFrame
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d

/**
 * Crosshair picking along the bent light path: you click what you see around a hole. Mirrors vanilla's pick (nearest
 * block within block reach, unless an entity within entity reach is closer), but distances are measured along the
 * null geodesic leaving the eye instead of a straight line. Only used when a hole bends the ray noticeably within reach.
 */
object BentPick {
	/** A hole whose closest approach to the straight sight line is within this many r_s bends the ray within reach. */
	private const val INFLUENCE = 40.0

	@JvmStatic
	fun pick(entity: Entity, blockRange: Double, entityRange: Double, partialTick: Float): HitResult? {
		val level = entity.level()
		val holes = SpacetimeField.blackHoles(level)
		if (holes.isEmpty()) return null
		val eye = entity.getEyePosition(partialTick)
		val view = entity.getViewVector(partialTick)
		val reach = maxOf(blockRange, entityRange)
		val hole = strongest(holes, eye, view, reach) ?: return null

		val origin = DoubleArray(3)
		val direction = DoubleArray(3)
		GameFrame.toPhysics(Vector3d(eye.x - hole.x, eye.y - hole.y, eye.z - hole.z), origin)
		GameFrame.toPhysics(Vector3d(view.x, view.y, view.z), direction)
		// The light you see there arrived along the sight line: traced back in time (it differs around a spinning hole).
		// Traced back, light from the hole only nears its horizon, so capture is a little outside it.
		val capture = 1.02 * hole.equatorialExtent(hole.horizonRadius)
		val path = NullRay.trace(hole.spacetime(), origin, direction, reach, capture, arriving = true)
		val points = path.points.map {
			val game = GameFrame.toGame(it, 0, Vector3d())
			Vec3(game.x + hole.x, game.y + hole.y, game.z + hole.z)
		}

		var traveled = 0.0
		for (i in 0 until points.size - 1) {
			val a = points[i]
			val b = points[i + 1]
			val segment = a.distanceTo(b)
			if (segment <= 0.0) continue
			val block = level.clip(ClipContext(a, b, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, entity))
			val blockAt = if (block.type != HitResult.Type.MISS) a.distanceTo(block.location) else Double.POSITIVE_INFINITY
			val box = AABB(a, b).inflate(1.0)
			val hitEntity = ProjectileUtil.getEntityHitResult(entity, a, b, box, { !it.isSpectator && it.isPickable }, segment * segment)
			val entityAt = hitEntity?.let { a.distanceTo(it.location) } ?: Double.POSITIVE_INFINITY

			if (hitEntity != null && entityAt < blockAt) {
				return if (traveled + entityAt <= entityRange) hitEntity else miss(hitEntity.location, b.subtract(a))
			}
			if (block.type != HitResult.Type.MISS) {
				return if (traveled + blockAt <= blockRange) block else miss(block.location, b.subtract(a))
			}
			traveled += segment
		}
		// Nothing within reach, or the ray fell into the hole.
		val last = points.last()
		val heading = if (points.size > 1) last.subtract(points[points.size - 2]) else view
		return miss(last, heading)
	}

	/** The hole that bends the straight sight line most within reach, if any does noticeably. */
	private fun strongest(holes: List<BlackHole>, eye: Vec3, view: Vec3, reach: Double): BlackHole? {
		var best: BlackHole? = null
		var bestBend = 0.0
		for (hole in holes) {
			val toHole = Vec3(hole.x - eye.x, hole.y - eye.y, hole.z - eye.z)
			val along = toHole.dot(view).coerceIn(0.0, reach)
			val closest = eye.add(view.scale(along)).distanceTo(Vec3(hole.x, hole.y, hole.z))
			if (closest > INFLUENCE * hole.schwarzschildRadius) continue
			val bend = hole.schwarzschildRadius / closest.coerceAtLeast(1e-6)
			if (bend > bestBend) {
				bestBend = bend
				best = hole
			}
		}
		return best
	}

	private fun miss(at: Vec3, heading: Vec3): HitResult =
		BlockHitResult.miss(at, Direction.getNearest(heading.x, heading.y, heading.z), BlockPos.containing(at))
}
