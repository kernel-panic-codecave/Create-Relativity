package net.kernelpanicsoft.relativity.entity

import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper
import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.accretion.Accretion
import net.kernelpanicsoft.relativity.spacetime.SpacetimeAttachments
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
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
		if (entity.isSpectator || entity.isPassenger) return
		if (SpacetimeField.blackHoles(level).isEmpty()) return
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
		}

		// Creative flight is a building tool, not a way to hover over a hole; survival flight (mods) still falls.
		val creativeFlying = entity is Player && entity.isCreative && entity.abilities.flying
		if (entity.isControlledByLocalInstance && !creativeFlying) fall(entity, center)
	}

	private fun fall(entity: Entity, center: Vector3d) {
		val level = entity.level()
		val c = net.kernelpanicsoft.relativity.config.RelativityConfig.speedOfLight(level.dimension())
		val motion = entity.deltaMovement
		val velocity = Vector3d(motion.x, motion.y, motion.z).mul(TICKS_PER_SECOND)
		if (velocity.length() > MAX_FIELD_SPEED * c) velocity.normalize(MAX_FIELD_SPEED * c)
		// Leapfrog: take the pull at the middle of this tick's motion, not its start. One kick per tick from the start
		// point pumps energy into tight orbits near a hole and flings debris out; the midpoint conserves it.
		val midpoint = Vector3d(velocity).mul(0.5 / TICKS_PER_SECOND).add(center)
		val accel = SpacetimeField.acceleration(level, midpoint, velocity, Vector3d())
		accel.add(SpacetimeField.radiationAcceleration(level, midpoint, couplingOf(entity), Vector3d()))
		if (accel.lengthSquared() == 0.0) return
		// blocks/s² over one tick, expressed in blocks/tick per tick.
		val perTick = 1.0 / (TICKS_PER_SECOND * TICKS_PER_SECOND)
		var next = motion.add(accel.x * perTick, accel.y * perTick, accel.z * perTick)

		// The horizon is one-way: nothing inside moves outward.
		val inside = SpacetimeField.insideHorizon(level, center)
		if (inside != null) {
			val outward = Vec3(center.x - inside.x, center.y - inside.y, center.z - inside.z).normalize()
			val radial = next.dot(outward)
			if (radial > 0.0) next = next.subtract(outward.scale(radial))
		}
		entity.deltaMovement = next
		entity.hasImpulse = true
	}

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
