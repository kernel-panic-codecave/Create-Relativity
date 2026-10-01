package net.kernelpanicsoft.relativity.sable

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason
import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.accretion.Accretion
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.spacetime.BlackHole
import net.kernelpanicsoft.relativity.spacetime.GameFrame
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.event.tick.LevelTickEvent
import org.joml.Vector3d
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Applies the spacetime field to every Sable sub-level each physics substep, at its centre of mass:
 * ships orbit, precess, get dragged by spinning holes and fall in along true geodesics. A ship whose
 * centre of mass crosses a horizon is captured: removed, with its mass added to the hole.
 * Tidal stress across a ship comes later (M6/M8).
 */
object SableSpacetimeForces {
	/**
	 * Sable's fixed substeps can carry a body past c just outside a horizon, where the pull is ≈ c²/2r_s, and
	 * the geodesic acceleration is meaningless for v ≥ c. The velocity fed to it is clamped below c; the real
	 * relativistic speed limit on ships is M11.
	 */
	private const val MAX_FIELD_SPEED = 0.95

	private class Capture(val subLevel: ServerSubLevel, val hole: BlackHole, val mass: Double, val position: Vector3d, val velocity: Vector3d)

	private val captures = ConcurrentLinkedQueue<Capture>()

	fun onPrePhysicsTick(event: ForgeSablePrePhysicsTickEvent) {
		val system = event.physicsSystem
		val level = system.level
		val holes = SpacetimeField.blackHoles(level)
		if (holes.isEmpty()) return
		val container = SubLevelContainer.getContainer(level) ?: return
		val c = RelativityConfig.speedOfLight(level.dimension())
		val dt = event.timeStep
		val velocity = Vector3d()
		val accel = Vector3d()

		for (subLevel in container.allSubLevels) {
			if (subLevel.isRemoved) continue
			val mass = subLevel.massTracker
			if (mass.isInvalid) continue
			val com = mass.centerOfMass ?: continue
			val pose = subLevel.logicalPose()
			val worldCom = pose.transformPosition(Vector3d(com))

			system.getPhysicsHandle(subLevel).getLinearVelocity(velocity)
			val swallowedBy = holes.firstOrNull { insideHorizon(it, worldCom) }
			if (swallowedBy != null) {
				if (captures.none { it.subLevel === subLevel }) {
					captures += Capture(subLevel, swallowedBy, mass.mass, Vector3d(worldCom), Vector3d(velocity))
				}
				continue
			}

			if (velocity.length() > MAX_FIELD_SPEED * c) velocity.normalize(MAX_FIELD_SPEED * c)
			SpacetimeField.acceleration(level, worldCom, velocity, accel)
			accel.add(SpacetimeField.radiationAcceleration(level, worldCom, Accretion.SHIP_COUPLING, Vector3d()))
			if (accel.lengthSquared() == 0.0) continue
			val impulse = pose.transformNormalInverse(accel.mul(mass.mass * dt))
			subLevel.getOrCreateQueuedForceGroup(RelativityForceGroups.SPACETIME_CURVATURE.get())
				.applyAndRecordPointForce(com, impulse)
		}
	}

	/** Swallows queued sub-levels once the physics step is over, so the container isn't modified mid-iteration. */
	fun onLevelTick(event: LevelTickEvent.Post) {
		val level = event.level as? ServerLevel ?: return
		if (captures.isEmpty()) return
		val container = SubLevelContainer.getContainer(level) ?: return
		val units = RelativityConfig.units(level.dimension())
		val iterator = captures.iterator()
		while (iterator.hasNext()) {
			val capture = iterator.next()
			if (capture.subLevel.level !== level) continue
			iterator.remove()
			if (capture.subLevel.isRemoved) continue
			container.removeSubLevel(capture.subLevel, SubLevelRemovalReason.REMOVED)
			Accretion.capture(level, capture.hole, capture.mass, capture.position, capture.velocity)
			CreateRelativity.LOGGER.info("Black hole {} swallowed sub-level {} (mass {})", capture.hole.id, capture.subLevel.uniqueId, capture.mass)
		}
	}

	private fun insideHorizon(hole: BlackHole, worldPos: Vector3d): Boolean {
		val p = DoubleArray(3)
		GameFrame.toPhysics(Vector3d(worldPos).sub(hole.x, hole.y, hole.z), p)
		val kerr = hole.spacetime()
		return kerr.radius(p[0], p[1], p[2]) < kerr.horizonRadius
	}
}
