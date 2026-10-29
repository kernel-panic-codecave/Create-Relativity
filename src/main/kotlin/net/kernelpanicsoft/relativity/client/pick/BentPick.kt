package net.kernelpanicsoft.relativity.client.pick

import net.kernelpanicsoft.relativity.physics.lens.field.BubbleModule
import net.kernelpanicsoft.relativity.physics.lens.field.HoleModule
import net.kernelpanicsoft.relativity.physics.lens.field.LensModule
import net.kernelpanicsoft.relativity.physics.lens.field.MassModule
import net.kernelpanicsoft.relativity.physics.lens.field.OrbitField
import net.kernelpanicsoft.relativity.physics.lens.field.TubeModule
import net.kernelpanicsoft.relativity.physics.lens.field.V3
import net.kernelpanicsoft.relativity.spacetime.BlackHole
import net.kernelpanicsoft.relativity.spacetime.FieldSource
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

/**
 * Crosshair picking along the bent light path: you click what you see, however the lens bends it. Mirrors vanilla's pick
 * (nearest block within block reach, unless an entity within entity reach is closer), but distances are measured along
 * the light leaving the eye instead of a straight line, followed as the renderer bends it (VertexLens): through every
 * hole and mass together (OrbitField, exact for a lone hole), or through a tube's or bubble's walls (their lens
 * modules). Only used when something bends the sight line noticeably within reach.
 */
object BentPick {
	/** A hole whose closest approach to the straight sight line is within this many r_s bends the ray within reach. */
	private const val INFLUENCE = 40.0

	/** How far apart the path's points are (blocks): finer than a block, so the clip between them follows the bend. */
	private const val SPACING = 0.1

	@JvmStatic
	fun pick(entity: Entity, blockRange: Double, entityRange: Double, partialTick: Float): HitResult? {
		val level = entity.level()
		val eye = entity.getEyePosition(partialTick)
		val view = entity.getViewVector(partialTick)
		val reach = maxOf(blockRange, entityRange)
		val points = path(level, eye, view, reach) ?: return null
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

	/**
	 * The light seen along [view] from [eye], as the renderer bends it, out to about [reach] blocks of path (game
	 * coordinates); null if nothing bends it noticeably (vanilla's pick then).
	 */
	fun path(level: net.minecraft.world.level.Level, eye: Vec3, view: Vec3, reach: Double): List<Vec3>? {
		val holes = SpacetimeField.blackHoles(level)
		val sources = SpacetimeField.fieldSources(level)
		val masses = sources.filterIsInstance<FieldSource.Mass>()
		val at = { x: Double, y: Double, z: Double -> V3(x - eye.x, y - eye.y, z - eye.z) }
		val d = V3(view.x, view.y, view.z)
		if (holes.any { bends(it, eye, view, reach) }) {
			val field = OrbitField(
				holes.map { HoleModule(at(it.x, it.y, it.z), it.schwarzschildRadius) },
				masses.map { MassModule(at(it.center.x(), it.center.y(), it.center.z()), it.mass, it.softening) },
			)
			// (Captured: the path stops where the hole took it, and nothing past it is picked.)
			val traced = field.trace(V3.ZERO, d, reach = reach, record = true, recordEvery = SPACING)
			return game(traced.points, eye)
		}
		// Through a tube's or bubble's walls, as drawn: the first whose light isn't straight.
		for (source in sources) {
			val module: LensModule = when (source) {
				is FieldSource.Krasnikov -> TubeModule(
					source.spine.asReversed().map { at(it.x(), it.y(), it.z()) }, TubeModule.Interior.Krasnikov(source.k), source.radius,
					// (Each segment at its ends' tilts' mean: a tube easing back to flat toward its end.)
					source.spineTilts.asReversed().zipWithNext { a, b -> TubeModule.Interior.Krasnikov(0.5 * (a + b)) },
				)
				is FieldSource.Tube -> TubeModule(source.spine.asReversed().map { at(it.x(), it.y(), it.z()) }, TubeModule.Interior.Warp(source.speed), source.radius)
				is FieldSource.Ellipsoid -> BubbleModule(
					at(source.center.x(), source.center.y(), source.center.z()),
					source.axes.map { V3(it.x(), it.y(), it.z()) },
					V3(-source.direction.x(), -source.direction.y(), -source.direction.z()).normalized(),
					TubeModule.Interior.Warp(source.speed),
				)
				else -> continue
			}
			val bent = when (module) {
				is TubeModule -> module.path(V3.ZERO, d, reach)
				is BubbleModule -> module.path(V3.ZERO, d, reach)
				else -> null
			} ?: continue
			if (bent.size > 2 && bent.any { p -> (p - d * (p dot d)).length > 1e-3 }) return game(bent, eye)
		}
		return null
	}

	private fun game(points: List<V3>, eye: Vec3): List<Vec3> = points.map { Vec3(it.x + eye.x, it.y + eye.y, it.z + eye.z) }

	/** Whether [hole] bends the straight sight line noticeably within [reach]. */
	private fun bends(hole: BlackHole, eye: Vec3, view: Vec3, reach: Double): Boolean {
		val toHole = Vec3(hole.x - eye.x, hole.y - eye.y, hole.z - eye.z)
		val along = toHole.dot(view).coerceIn(0.0, reach)
		val closest = eye.add(view.scale(along)).distanceTo(Vec3(hole.x, hole.y, hole.z))
		return closest <= INFLUENCE * hole.schwarzschildRadius
	}

	private fun miss(at: Vec3, heading: Vec3): HitResult =
		BlockHitResult.miss(at, Direction.getNearest(heading.x, heading.y, heading.z), BlockPos.containing(at))
}
