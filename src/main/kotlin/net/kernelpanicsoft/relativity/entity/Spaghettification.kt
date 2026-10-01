package net.kernelpanicsoft.relativity.entity

import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.spacetime.SpacetimeAttachments
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.LivingEntity
import org.joml.Vector3d

/**
 * Tidal stretching of living things. Across a body of rest length L₀ at distance r the tides pull its ends apart at
 * a = 2GM·L₀/r³ (radial Schwarzschild tidal tensor). Bodies have strength: up to the tolerance S they hold, and past
 * it they stretch in proportion to how far the tide exceeds it, λ_target = 1 + (a/S − 1), easing toward that over
 * ~0.3 s. So stretch tracks how deep in the tides you are (back off and you recover) instead of running away.
 * λ = (L₀ + e)/L₀ is synced for the renderer and HUD; it hurts past 1.5 and kills at 4 (a = 4S).
 */
object Spaghettification {
	/** Identical damage types differing only in death message; one is picked at random. */
	val DAMAGE: List<ResourceKey<DamageType>> = listOf(
		"spaghettification", "spaghettification_noodle", "spaghettification_al_dente",
		"spaghettification_disagreement", "spaghettification_stretch", "spaghettification_extruded",
	).map { ResourceKey.create(Registries.DAMAGE_TYPE, CreateRelativity.id(it)) }

	private val ADVANCEMENT = CreateRelativity.id("the_noodle_incident")

	private const val TICKS_PER_SECOND = 20.0
	private const val HURT_FROM = 1.5
	private const val LETHAL = 4.0
	/** Time constant of the stretch following its target, seconds. */
	private const val RESPONSE_SECONDS = 0.3

	fun tick(entity: LivingEntity, center: Vector3d) {
		val level = entity.level()
		val c = RelativityConfig.speedOfLight(level.dimension())
		// Strongest radial tide among the holes, per block of separation (1/s²).
		var tide = 0.0
		for (hole in SpacetimeField.blackHoles(level)) {
			val r = maxOf(center.distance(hole.x, hole.y, hole.z), 1e-3)
			tide = maxOf(tide, 2.0 * c * c * hole.mass / (r * r * r))
		}
		val stretch = SpacetimeAttachments.bodyStretch
		val lambda = stretch.get(entity)
		val pull = tide * entity.bbHeight / RelativityConfig.Server.BlackHoles.bodyTolerance
		val target = 1.0 + maxOf(0.0, pull - 1.0)
		val next = lambda + (target - lambda) * (1.0 - kotlin.math.exp(-1.0 / (TICKS_PER_SECOND * RESPONSE_SECONDS)))
		val settled = if (kotlin.math.abs(next - 1.0) < 1e-3 && target == 1.0) 1.0 else next
		if (settled != lambda) stretch.set(entity, settled)

		if (settled >= LETHAL) {
			kill(entity)
		} else if (settled > HURT_FROM && entity.tickCount % 5 == 0) {
			entity.hurt(source(entity), ((settled - HURT_FROM) * 2.0).toFloat())
		}
	}

	private fun source(entity: LivingEntity) = DamageSource(
		entity.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(DAMAGE.random())
	)

	private fun kill(entity: LivingEntity) {
		entity.hurt(source(entity), Float.MAX_VALUE)
		if (entity.isAlive) entity.kill()
		if (entity is ServerPlayer) {
			entity.server.advancements.get(ADVANCEMENT)?.let { entity.advancements.award(it, "spaghettified") }
		}
		// The stretch stays for the death animation; a respawned player is a fresh entity at λ = 1.
	}
}
