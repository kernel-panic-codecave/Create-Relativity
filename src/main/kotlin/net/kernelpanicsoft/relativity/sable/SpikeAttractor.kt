package net.kernelpanicsoft.relativity.sable

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent
import net.kernelpanicsoft.relativity.physics.Schwarzschild
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d

/**
 * M0 spike: a single Newtonian point attractor that pulls every Sable sub-level in one dimension.
 * Proves the force pipeline end to end; replaced by the spacetime layer in M2.
 */
object SpikeAttractor {
	data class Attractor(val dimension: ResourceKey<Level>, val center: Vec3, val hole: Schwarzschild)

	@Volatile
	var active: Attractor? = null

	fun onPrePhysicsTick(event: ForgeSablePrePhysicsTickEvent) {
		val attractor = active ?: return
		val level = event.physicsSystem.level
		if (level.dimension() != attractor.dimension) return
		val container = SubLevelContainer.getContainer(level) ?: return
		val dt = event.timeStep
		val toCenter = Vector3d()

		for (subLevel in container.allSubLevels) {
			val mass = subLevel.massTracker
			if (mass.isInvalid) continue
			val pose = subLevel.logicalPose()
			val com = mass.centerOfMass ?: continue
			val worldCom = pose.transformPosition(Vector3d(com))

			toCenter.set(attractor.center.x - worldCom.x, attractor.center.y - worldCom.y, attractor.center.z - worldCom.z)
			val r = toCenter.length()
			if (r < 1e-3) continue

			// Newtonian placeholder, softened inside the horizon so the spike can't blow up the solver.
			val accel = attractor.hole.gm / (maxOf(r, attractor.hole.rs) * maxOf(r, attractor.hole.rs))
			val impulse = pose.transformNormalInverse(toCenter.normalize().mul(accel * mass.mass * dt))

			subLevel.getOrCreateQueuedForceGroup(RelativityForceGroups.SPACETIME_CURVATURE.get())
				.applyAndRecordPointForce(com, impulse)
		}
	}
}
