package net.kernelpanicsoft.relativity.entity

import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper
import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.accretion.Accretion
import net.kernelpanicsoft.relativity.spacetime.SpacetimeAttachments
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.kernelpanicsoft.relativity.time.LocalClocks
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.neoforged.neoforge.event.tick.EntityTickEvent
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import java.util.WeakHashMap
import kotlin.math.PI

/**
 * Entities in curved spacetime: free fall toward black holes along their geodesics, a one-way horizon, death at the
 * singularity, and each player's proper-time clock.
 *
 * Inside a horizon the longest any path can take to reach the singularity is πM of proper time (geometric units),
 * so an entity that has spent that long inside is crushed, however fast it fell. For small holes that's under a
 * tick: falling in is final, and per-tick integration never gets the chance to fling anything through the centre.
 *
 * Motion is applied by whichever side simulates the entity ([Entity.isControlledByLocalInstance]): the server for
 * mobs and items, the client for its own player. Vanilla gravity is left alone; the hole's pull is added to it.
 */
object EntitySpacetime {
	/** Identical damage types that only differ in death message; one is picked at random. Must match data/…/damage_type. */
	val SINGULARITY: List<ResourceKey<DamageType>> = listOf(
		"singularity", "singularity_density", "singularity_divide", "singularity_future",
		"singularity_rounding", "singularity_information", "singularity_bottom", "singularity_entropy",
	).map { ResourceKey.create(Registries.DAMAGE_TYPE, CreateRelativity.id(it)) }

	private const val TICKS_PER_SECOND = 20.0

	/** Velocity fed to the field is kept below c; vanilla motion can briefly exceed it near a hole. */
	private const val MAX_FIELD_SPEED = 0.95

	/** Ticks each entity has spent inside a horizon (server side). */
	private val ticksInside = WeakHashMap<Entity, Int>()

	fun onEntityTick(event: EntityTickEvent.Pre) {
		val entity = event.entity
		val level = entity.level()
		// A hole sits inside its own horizon; it must not swallow itself (hole-hole interaction is M10's).
		if (entity is BlackHoleEntity || entity.isSpectator || entity.isPassenger) return
		if (SpacetimeField.blackHoles(level).isEmpty()) {
			// No holes left (removed or evaporated): let go of any stretch.
			if (!level.isClientSide && entity is LivingEntity && SpacetimeAttachments.bodyStretch.get(entity) != 1.0) {
				SpacetimeAttachments.bodyStretch.set(entity, 1.0)
				net.kernelpanicsoft.relativity.spacetime.SpacetimeSync.update(entity)
			}
			return
		}
		val halfHeight = entity.bbHeight / 2.0
		val center = Vector3d(entity.x, entity.y + halfHeight, entity.z)

		if (!level.isClientSide) {
			val previous = Vector3d(entity.xo, entity.yo + halfHeight, entity.zo)
			val hole = SpacetimeField.crossedHorizon(level, previous, center)
			if (hole != null) {
				val ticks = ticksInside.merge(entity, 1, Int::plus)!!
				val c = net.kernelpanicsoft.relativity.config.RelativityConfig.speedOfLight(level.dimension())
				val longestFall = PI * hole.mass / c // seconds of proper time, horizon to singularity
				if (SpacetimeField.atSingularity(level, center) != null || ticks / TICKS_PER_SECOND >= longestFall) {
					ticksInside.remove(entity)
					val velocity = Vector3d(entity.x - entity.xo, entity.y - entity.yo, entity.z - entity.zo).mul(TICKS_PER_SECOND)
					Accretion.capture(level, hole, massOf(entity), center, velocity)
					crush(entity)
					return
				}
			} else {
				ticksInside.remove(entity)
			}
			if (entity is ServerPlayer) tickClock(entity, center)
			if (entity is LivingEntity && entity.isAlive) Spaghettification.tick(entity, center)
			if (entity is LivingEntity) net.kernelpanicsoft.relativity.spacetime.SpacetimeSync.update(entity)
		}

		// Creative flight is a building tool, not a way to hover over a hole; survival flight (mods) still falls.
		val creativeFlying = entity is Player && entity.isCreative && entity.abilities.flying
		val owned = entity is FallingBlockEntity || entity is ItemEntity
		if (entity.isControlledByLocalInstance && !creativeFlying) {
			if (owned) drift(entity, center) else fall(entity, center)
		}

		if (!level.isClientSide && entity !is Player && !properTimeTick(entity, center)) {
			// Motion is coordinate-time kinematics and keeps going; only the entity's own tick (AI, ageing, vanilla
			// physics) waits for its clock. Debris and items' motion already cancels the gravity vanilla would add.
			val motion = entity.deltaMovement
			val gravity = if (entity.isNoGravity) 0.0 else entity.gravity
			entity.move(MoverType.SELF, if (owned) motion.subtract(0.0, gravity, 0.0) else motion)
			if (!owned && !entity.onGround()) entity.deltaMovement = entity.deltaMovement.subtract(0.0, gravity, 0.0)
			event.isCanceled = true
		}
	}

	private val clocks = WeakHashMap<Entity, LocalClocks.Clock>()

	/**
	 * Whether [entity]'s own tick runs this server tick, on its proper-time clock. Only the gravitational part of
	 * dτ/dt counts for now: vanilla speeds can exceed c until M11 enforces the limit, which would freeze things.
	 */
	private fun properTimeTick(entity: Entity, center: Vector3d): Boolean {
		if (!LocalClocks.enabled) return true
		val rate = LocalClocks.rateAt(entity.level(), center)
		if (rate >= 1.0) {
			clocks.remove(entity)
			return true
		}
		return clocks.getOrPut(entity, LocalClocks::Clock).advance(rate)
	}

	/** Where an entity should be after this tick, and velocity carried into the next one (blocks/s). */
	private class Trajectory(val expected: Vec3, val velocity: Vector3d)

	private val trajectories = WeakHashMap<Entity, Trajectory>()

	/** Debris drag inside a hole's disk region (1/s): stands in for disk viscosity, so orbits decay and spiral in. */
	private const val DISK_DRAG = 0.4
	private const val MAX_SUBSTEPS = 32

	/**
	 * Integrates one tick of gravity (and radiation pressure) from [start] with velocity [velocity] (blocks/s, updated
	 * in place), sub-stepped with leapfrog finely enough to resolve the local orbit (~1/40 of an orbital period): a
	 * tight orbit near a hole takes only a few ticks, and one kick per tick throws things out. Returns the end point.
	 */
	private fun integrateTick(entity: Entity, start: Vector3d, velocity: Vector3d, diskDrag: Boolean): Vector3d {
		val level = entity.level()
		val holes = SpacetimeField.blackHoles(level)
		val c = net.kernelpanicsoft.relativity.config.RelativityConfig.speedOfLight(level.dimension())
		var period = Double.POSITIVE_INFINITY
		var inDisk = false
		for (hole in holes) {
			val gm = c * c * hole.mass
			val r = maxOf(start.distance(hole.x, hole.y, hole.z), hole.schwarzschildRadius)
			period = minOf(period, 2.0 * PI * kotlin.math.sqrt(r * r * r / gm))
			if (r < 20.0 * hole.schwarzschildRadius) inDisk = true
		}
		val dt = 1.0 / TICKS_PER_SECOND
		val steps = kotlin.math.ceil(dt / (period / 40.0)).toInt().coerceIn(1, MAX_SUBSTEPS)
		val h = dt / steps
		val coupling = couplingOf(entity)
		val position = Vector3d(start)
		val accel = Vector3d()
		val push = Vector3d()

		fun pull(at: Vector3d, v: Vector3d): Vector3d {
			val fieldVelocity = Vector3d(v)
			if (fieldVelocity.length() > MAX_FIELD_SPEED * c) fieldVelocity.normalize(MAX_FIELD_SPEED * c)
			SpacetimeField.acceleration(level, at, fieldVelocity, accel)
			return accel.add(SpacetimeField.radiationAcceleration(level, at, coupling, push))
		}

		repeat(steps) {
			velocity.fma(h / 2.0, pull(position, velocity))
			position.fma(h, velocity)
			velocity.fma(h / 2.0, pull(position, velocity))
			if (diskDrag && inDisk) velocity.mul(kotlin.math.exp(-DISK_DRAG * h))
			// The horizon is one-way: nothing inside moves outward.
			val inside = SpacetimeField.insideHorizon(level, position)
			if (inside != null) {
				val outward = Vector3d(position).sub(inside.x, inside.y, inside.z).normalize()
				val radial = velocity.dot(outward)
				if (radial > 0.0) velocity.sub(outward.mul(radial))
			}
		}
		return position
	}

	/** True if [entity] ended up where the last tick's integration said it would. */
	private fun onTrack(entity: Entity, carried: Trajectory?): Boolean =
		carried != null && entity.position().distanceToSqr(carried.expected) < 0.01

	/**
	 * Loose things (debris, items) whose movement this owns outright: the motion is set to the exact displacement for
	 * the tick, so vanilla movement (and the client, which receives it) follows the precise path, and the true
	 * velocity is carried into the next tick unless a collision moved the entity elsewhere.
	 */
	private fun drift(entity: Entity, center: Vector3d) {
		val carried = trajectories[entity]
		val velocity = if (onTrack(entity, carried)) Vector3d(carried!!.velocity) else motionPerSecond(entity)
		val end = integrateTick(entity, center, velocity, diskDrag = true)
		val displacement = end.sub(center)
		// Vanilla adds its own gravity before moving; take it back out so the entity lands exactly where integrated.
		val vanillaGravity = if (entity.isNoGravity) 0.0 else entity.gravity
		entity.deltaMovement = Vec3(displacement.x, displacement.y + vanillaGravity, displacement.z)
		entity.hasImpulse = true
		trajectories[entity] = Trajectory(entity.position().add(displacement.x, displacement.y, displacement.z), velocity)
	}

	/**
	 * Mobs and players, whose movement also goes through vanilla (input, AI, friction, collisions). Gravity is
	 * integrated exactly over the tick and handed to vanilla as the chord (x₁ − x₀) per tick: the exact path as one
	 * segment, which vanilla then adds input to and collides. The part of the velocity change the chord doesn't contain
	 * (v₁ − chord) is carried into the next tick, and dropped if the entity didn't arrive where expected (it hit
	 * something, was knocked back or teleported).
	 */
	private fun fall(entity: Entity, center: Vector3d) {
		val carried = trajectories[entity]
		val start = motionPerSecond(entity)
		if (onTrack(entity, carried)) start.add(carried!!.velocity)
		val velocity = Vector3d(start)
		val end = integrateTick(entity, center, velocity, diskDrag = false)
		val chord = Vector3d(end).sub(center)
		entity.deltaMovement = Vec3(chord.x, chord.y, chord.z)
		entity.hasImpulse = true
		val leftover = Vector3d(velocity).sub(Vector3d(chord).mul(TICKS_PER_SECOND))
		trajectories[entity] = Trajectory(entity.position().add(chord.x, chord.y, chord.z), leftover)
	}

	private fun motionPerSecond(entity: Entity) =
		Vector3d(entity.deltaMovement.x, entity.deltaMovement.y, entity.deltaMovement.z).mul(TICKS_PER_SECOND)

	/** Proper time falls behind world time at (1 − dτ/dt) seconds per second. */
	private fun tickClock(player: ServerPlayer, center: Vector3d) {
		val velocity = Vector3d(player.x - player.xo, player.y - player.yo, player.z - player.zo).mul(TICKS_PER_SECOND)
		val rate = SpacetimeField.properTimeRate(player.level(), center, velocity)
		val lag = SpacetimeAttachments.properTimeLag
		lag.set(player, lag.get(player) + (1.0 - rate) / TICKS_PER_SECOND)
	}

	/** Mass swallowed with [entity], in Sable units: its block's for debris, a little per item, body volume otherwise. */
	private fun massOf(entity: Entity): Double = when (entity) {
		is FallingBlockEntity -> PhysicsBlockPropertyHelper.getMass(entity.level(), entity.blockPosition(), entity.blockState)
		is ItemEntity -> 0.05 * entity.item.count
		else -> (entity.bbWidth * entity.bbWidth * entity.bbHeight).toDouble()
	}

	/** How strongly radiation pushes [entity] compared with how gravity pulls it. */
	private fun couplingOf(entity: Entity): Double =
		if (entity is FallingBlockEntity || entity is ItemEntity) Accretion.DEBRIS_COUPLING else Accretion.CREATURE_COUPLING

	private fun crush(entity: Entity) {
		if (entity is LivingEntity) {
			val type = entity.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
				.getHolderOrThrow(SINGULARITY.random())
			entity.hurt(DamageSource(type), Float.MAX_VALUE)
			if (entity.isAlive) entity.kill() // totems and the like don't survive a singularity
		} else {
			entity.discard()
		}
	}
}
