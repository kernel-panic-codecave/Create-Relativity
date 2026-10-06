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
 * The tidal field also torques the ship toward the hole (gravity gradient), and tears it apart past its strength.
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
		val torque = Vector3d()

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
			val forces = subLevel.getOrCreateQueuedForceGroup(RelativityForceGroups.SPACETIME_CURVATURE.get())
			forces.applyAndRecordPointForce(com, impulse)
			gravityGradientTorque(holes, c, worldCom, pose, mass.inertiaTensor, torque)
			forces.forceTotal.applyTorqueImpulse(torque.mul(dt))
		}
	}

	/**
	 * Gravity-gradient torque on a body (plot-local, N·m): τ = Σ (3GM/r³) r̂ × (I·r̂) over the holes, with r̂ from the
	 * hole to the centre of mass in the body frame and I the inertia tensor about it. It turns the body's long axis
	 * (least inertia) toward the hole: ships tidally lock and rock about the radial line.
	 */
	private fun gravityGradientTorque(
		holes: List<BlackHole>, c: Double, worldCom: Vector3d, pose: dev.ryanhcode.sable.companion.math.Pose3dc,
		inertia: org.joml.Matrix3dc, out: Vector3d,
	): Vector3d {
		out.zero()
		val radial = Vector3d()
		val ir = Vector3d()
		for (hole in holes) {
			radial.set(worldCom).sub(hole.x, hole.y, hole.z)
			val r = radial.length()
			if (r < hole.schwarzschildRadius) continue
			pose.transformNormalInverse(radial.div(r))
			inertia.transform(radial, ir)
			out.add(radial.cross(ir).mul(3.0 * c * c * hole.mass / (r * r * r)))
		}
		return out
	}

	private const val TEAR_INTERVAL_TICKS = 5
	private const val MAX_TEAR_BLOCKS = 128

	/**
	 * Swallows queued sub-levels once the physics step is over, so the container isn't modified mid-iteration, and
	 * tears ships apart where the tides across them beat their strength.
	 */
	fun onLevelTick(event: LevelTickEvent.Post) {
		val level = event.level as? ServerLevel ?: return
		// Frozen with /tick freeze: hold still with the rest of the world (stepping still runs).
		if (!level.tickRateManager().runsNormally()) return
		val container = SubLevelContainer.getContainer(level) ?: return
		if (level.gameTime % TEAR_INTERVAL_TICKS == 0L) tearShips(level, container)
		if (captures.isEmpty()) return
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

	/**
	 * A ship of extent D along the radial direction feels its ends pulled apart at 2GM·D/r³. Past its strength it
	 * tears across the mid-plane through its centre of mass (perpendicular to the radial): those blocks are removed and
	 * Sable's connectivity check splits it in two. Each half is checked again; shorter pieces feel weaker tides, so the
	 * breakup cascades as they fall in.
	 */
	private fun tearShips(level: ServerLevel, container: dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer) {
		val holes = SpacetimeField.blackHoles(level)
		if (holes.isEmpty()) return
		val c = RelativityConfig.speedOfLight(level.dimension())
		val strength = RelativityConfig.Server.BlackHoles.shipTidalStrength
		val corner = Vector3d()
		val world = Vector3d()
		for (subLevel in container.allSubLevels.toList()) {
			if (subLevel.isRemoved) continue
			val mass = subLevel.massTracker
			if (mass.isInvalid) continue
			val com = mass.centerOfMass ?: continue
			val pose = subLevel.logicalPose()
			val worldCom = pose.transformPosition(Vector3d(com))
			val hole = holes.minByOrNull { worldCom.distanceSquared(it.x, it.y, it.z) } ?: continue
			val r = worldCom.distance(hole.x, hole.y, hole.z)
			if (r > 50.0 * hole.schwarzschildRadius || r < 1e-3) continue
			val radial = Vector3d(worldCom).sub(hole.x, hole.y, hole.z).normalize()

			val box = subLevel.plot.boundingBox
			var low = Double.POSITIVE_INFINITY
			var high = Double.NEGATIVE_INFINITY
			for (i in 0 until 8) {
				corner.set(
					if (i and 1 == 0) box.minX().toDouble() else box.maxX() + 1.0,
					if (i and 2 == 0) box.minY().toDouble() else box.maxY() + 1.0,
					if (i and 4 == 0) box.minZ().toDouble() else box.maxZ() + 1.0,
				)
				val along = pose.transformPosition(corner, world).sub(worldCom).dot(radial)
				low = minOf(low, along)
				high = maxOf(high, along)
			}
			val extent = high - low
			if (extent < 1.5) continue // a single block's width can't be split further
			val tide = 2.0 * c * c * hole.mass * extent / (r * r * r)
			if (tide < strength) continue

			var torn = 0
			val pos = net.minecraft.core.BlockPos.MutableBlockPos()
			loop@ for (x in box.minX()..box.maxX()) for (y in box.minY()..box.maxY()) for (z in box.minZ()..box.maxZ()) {
				pos.set(x, y, z)
				val state = level.getBlockState(pos)
				if (state.isAir || state.getDestroySpeed(level, pos) < 0f) continue
				corner.set(x + 0.5, y + 0.5, z + 0.5)
				if (kotlin.math.abs(pose.transformPosition(corner, world).sub(worldCom).dot(radial)) >= 0.5) continue
				level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3)
				if (++torn >= MAX_TEAR_BLOCKS) break@loop
			}
			if (torn > 0) CreateRelativity.LOGGER.debug("Tides tore {} blocks from sub-level {}", torn, subLevel.uniqueId)
		}
	}

	private fun insideHorizon(hole: BlackHole, worldPos: Vector3d): Boolean {
		val p = DoubleArray(3)
		GameFrame.toPhysics(Vector3d(worldPos).sub(hole.x, hole.y, hole.z), p)
		val kerr = hole.spacetime()
		return kerr.radius(p[0], p[1], p[2]) < kerr.horizonRadius
	}
}
