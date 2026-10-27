package net.kernelpanicsoft.relativity.config

import net.kernelpanicsoft.archie.config.CategorySpec
import net.kernelpanicsoft.archie.config.ConfigContainer
import net.kernelpanicsoft.archie.config.ConfigSpec
import net.kernelpanicsoft.archie.gui.util.KColor
import net.kernelpanicsoft.archie.gui.util.KGradient
import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.physics.SpacetimeUnits
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

object RelativityConfig : ConfigContainer(CreateRelativity.MOD) {
	/** Per-world physics; synced to clients so rendering agrees with the server. */
	object Server : ConfigSpec.Server(CreateRelativity.MOD) {
		object Physics : CategorySpec(Component.translatable("config.create_relativity.physics"), "physics") {
			val speedOfLight by double(
				title = Component.translatable("config.create_relativity.physics.speed_of_light"),
				comment = Component.translatable("config.create_relativity.physics.speed_of_light.comment"),
				default = SpacetimeUnits.DEFAULT_C,
			)
			val lightFollowsPhysics by boolean(
				title = Component.translatable("config.create_relativity.physics.light_follows_physics"),
				comment = Component.translatable("config.create_relativity.physics.light_follows_physics.comment"),
				default = true,
			)
			val lightBlocksPerStep by double(
				title = Component.translatable("config.create_relativity.physics.light_blocks_per_step"),
				comment = Component.translatable("config.create_relativity.physics.light_blocks_per_step.comment"),
				default = 1.6,
			)
			val speedOfLightOverrides by doubleMap(
				title = Component.translatable("config.create_relativity.physics.speed_of_light_overrides"),
				comment = Component.translatable("config.create_relativity.physics.speed_of_light_overrides.comment"),
				default = emptyMap(),
			)
			val unitRadiusMass by double(
				title = Component.translatable("config.create_relativity.physics.unit_radius_mass"),
				comment = Component.translatable("config.create_relativity.physics.unit_radius_mass.comment"),
				default = SpacetimeUnits.DEFAULT_UNIT_RADIUS_MASS,
			)
			val planckLength by double(
				title = Component.translatable("config.create_relativity.physics.planck_length"),
				comment = Component.translatable("config.create_relativity.physics.planck_length.comment"),
				default = 1.0 / 64.0,
			)
		}

		object BlackHoles : CategorySpec(Component.translatable("config.create_relativity.black_holes"), "black_holes") {
			val eatBudget by intSlider(
				title = Component.translatable("config.create_relativity.black_holes.eat_budget"),
				comment = Component.translatable("config.create_relativity.black_holes.eat_budget.comment"),
				min = 0, max = 4096, default = 64,
			)
			val ripBudget by intSlider(
				title = Component.translatable("config.create_relativity.black_holes.rip_budget"),
				comment = Component.translatable("config.create_relativity.black_holes.rip_budget.comment"),
				min = 0, max = 256, default = 4,
			)
			val ripAcceleration by double(
				title = Component.translatable("config.create_relativity.black_holes.rip_acceleration"),
				comment = Component.translatable("config.create_relativity.black_holes.rip_acceleration.comment"),
				default = 32.0,
			)
			val eddingtonRatePerBlock by double(
				title = Component.translatable("config.create_relativity.black_holes.eddington_rate"),
				comment = Component.translatable("config.create_relativity.black_holes.eddington_rate.comment"),
				default = 100.0,
			)
			val maxSchwarzschildRadius by double(
				title = Component.translatable("config.create_relativity.black_holes.max_radius"),
				comment = Component.translatable("config.create_relativity.black_holes.max_radius.comment"),
				default = 32.0,
			)
			val bodyTolerance by double(
				title = Component.translatable("config.create_relativity.black_holes.body_tolerance"),
				comment = Component.translatable("config.create_relativity.black_holes.body_tolerance.comment"),
				default = 20.0,
			)
			val shipTidalStrength by double(
				title = Component.translatable("config.create_relativity.black_holes.ship_tidal_strength"),
				comment = Component.translatable("config.create_relativity.black_holes.ship_tidal_strength.comment"),
				default = 200.0,
			)
			val hawking by boolean(
				title = Component.translatable("config.create_relativity.black_holes.hawking"),
				comment = Component.translatable("config.create_relativity.black_holes.hawking.comment"),
				default = true,
			)
		}

		object Time : CategorySpec(Component.translatable("config.create_relativity.time"), "time") {
			val dilateTicks by boolean(
				title = Component.translatable("config.create_relativity.time.dilate_ticks"),
				comment = Component.translatable("config.create_relativity.time.dilate_ticks.comment"),
				default = true,
			)
			val shaftTorsion by double(
				title = Component.translatable("config.create_relativity.time.shaft_torsion"),
				comment = Component.translatable("config.create_relativity.time.shaft_torsion.comment"),
				default = 4.0,
			)
			val maxFrameSpeedup by double(
				title = Component.translatable("config.create_relativity.time.max_frame_speedup"),
				comment = Component.translatable("config.create_relativity.time.max_frame_speedup.comment"),
				default = 8.0,
			)
		}

		object Trains : CategorySpec(Component.translatable("config.create_relativity.trains"), "trains") {
			val maglevTopSpeed by double(
				title = Component.translatable("config.create_relativity.trains.maglev_top_speed"),
				comment = Component.translatable("config.create_relativity.trains.maglev_top_speed.comment"),
				default = 3.0,
			)
			val maglevTurningSpeed by double(
				title = Component.translatable("config.create_relativity.trains.maglev_turning_speed"),
				comment = Component.translatable("config.create_relativity.trains.maglev_turning_speed.comment"),
				default = 2.0,
			)
			val krasnikovSpeed by double(
				title = Component.translatable("config.create_relativity.trains.krasnikov_speed"),
				comment = Component.translatable("config.create_relativity.trains.krasnikov_speed.comment"),
				default = 20.0,
			)
			val maxWarp by double(
				title = Component.translatable("config.create_relativity.trains.max_warp"),
				comment = Component.translatable("config.create_relativity.trains.max_warp.comment"),
				default = 9.0,
			)
			val maglevAcceleration by double(
				title = Component.translatable("config.create_relativity.trains.maglev_acceleration"),
				comment = Component.translatable("config.create_relativity.trains.maglev_acceleration.comment"),
				default = 2.0,
			)
		}
	}

	/** Per-player client settings. */
	object Client : ConfigSpec.Client(CreateRelativity.MOD) {
		object Render : CategorySpec(Component.translatable("config.create_relativity.render"), "render") {
			val lensing by boolean(
				title = Component.translatable("config.create_relativity.render.lensing"),
				comment = Component.translatable("config.create_relativity.render.lensing.comment"),
				default = true,
			)
			val lightDelay by boolean(
				title = Component.translatable("config.create_relativity.render.light_delay"),
				comment = Component.translatable("config.create_relativity.render.light_delay.comment"),
				default = true,
			)
			var diskScheme by enumSelector(
				title = Component.translatable("config.create_relativity.render.disk_scheme"),
				comment = Component.translatable("config.create_relativity.render.disk_scheme.comment"),
				kclass = DiskScheme::class,
				default = DiskScheme.REALISTIC,
			)
			val diskGradient by gradient(
				title = Component.translatable("config.create_relativity.render.disk_gradient"),
				comment = Component.translatable("config.create_relativity.render.disk_gradient.comment"),
				default = KGradient.even(KColor.ofRgb(0xFFFFFF), KColor.ofRgb(0x9FD8FF), KColor.ofRgb(0x7B4DFF), KColor.ofRgb(0x2A0A5E)),
			)
		}
	}

	/**
	 * How the accretion disk is coloured: [REALISTIC] its blackbody light, Doppler and gravitationally shifted and
	 * beamed; [MOVIE] the film look (Interstellar's): warm orange to white by heat alone, the shift and beaming left
	 * out but a little brightening on the approaching side; [CUSTOM] the gradient set in the config, hot inner edge to
	 * cool outer, with the realistic shifts.
	 */
	enum class DiskScheme { REALISTIC, MOVIE, CUSTOM }

	/** Speed of light in [dimension]: its override if one is set, otherwise the default. */
	fun speedOfLight(dimension: ResourceKey<Level>): Double =
		Server.Physics.speedOfLightOverrides[dimension.location().toString()] ?: defaultSpeedOfLight()

	/**
	 * The speed of light where no override sets it: with Light Follows Physics, as fast as the physics can follow
	 * smoothly, [Server.Physics.lightBlocksPerStep] blocks per Sable physics substep (20 x its substeps a second: 64 at
	 * its default 2 and 1.6); else [Server.Physics.speedOfLight].
	 */
	fun defaultSpeedOfLight(): Double {
		if (!Server.Physics.lightFollowsPhysics) return Server.Physics.speedOfLight
		val substeps = try {
			dev.ryanhcode.sable.SableServerConfig.SUB_LEVEL_SUBSTEPS_PER_TICK.getAsInt()
		} catch (_: Exception) {
			2 // (its config not loaded yet)
		}
		return Server.Physics.lightBlocksPerStep * 20.0 * substeps.coerceAtLeast(1)
	}

	/** Ṁ_Edd for a hole of Schwarzschild radius [rs] (blocks): the feeding rate (mass/s) where radiation balances gravity. */
	fun eddingtonRate(rs: Double): Double = Server.BlackHoles.eddingtonRatePerBlock * rs

	/** Units for [dimension] from the current server config. */
	fun units(dimension: ResourceKey<Level>): SpacetimeUnits =
		SpacetimeUnits.withUnitRadiusMass(speedOfLight(dimension), Server.Physics.unitRadiusMass)
}
