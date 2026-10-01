package net.kernelpanicsoft.relativity.config

import net.kernelpanicsoft.archie.config.CategorySpec
import net.kernelpanicsoft.archie.config.ConfigContainer
import net.kernelpanicsoft.archie.config.ConfigSpec
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
			val speedOfLightOverrides by doubleMap(
				title = Component.translatable("config.create_relativity.physics.speed_of_light_overrides"),
				comment = Component.translatable("config.create_relativity.physics.speed_of_light_overrides.comment"),
				default = mapOf(PLANETOID_DIMENSION to PLANETOID_C),
			)
			val unitRadiusMass by double(
				title = Component.translatable("config.create_relativity.physics.unit_radius_mass"),
				comment = Component.translatable("config.create_relativity.physics.unit_radius_mass.comment"),
				default = SpacetimeUnits.DEFAULT_UNIT_RADIUS_MASS,
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
			val environmentCapture by boolean(
				title = Component.translatable("config.create_relativity.render.environment_capture"),
				comment = Component.translatable("config.create_relativity.render.environment_capture.comment"),
				default = true,
			)
			val captureDistance by intSlider(
				title = Component.translatable("config.create_relativity.render.capture_distance"),
				comment = Component.translatable("config.create_relativity.render.capture_distance.comment"),
				min = 2, max = 16, default = 8,
			)
		}

		object Debug : CategorySpec(Component.translatable("config.create_relativity.debug"), "debug") {
			val showRadii by boolean(
				title = Component.translatable("config.create_relativity.debug.show_radii"),
				comment = Component.translatable("config.create_relativity.debug.show_radii.comment"),
				default = true,
			)
		}
	}

	/** Planetoid space is far from Minecraft-scale c: block planets would otherwise be near-black-holes. */
	const val PLANETOID_DIMENSION = "create_relativity:planetoid"
	const val PLANETOID_C = 512.0

	/** Speed of light in [dimension]: its override if one is set, otherwise the default. */
	fun speedOfLight(dimension: ResourceKey<Level>): Double =
		Server.Physics.speedOfLightOverrides[dimension.location().toString()] ?: Server.Physics.speedOfLight

	/** Units for [dimension] from the current server config. */
	fun units(dimension: ResourceKey<Level>): SpacetimeUnits =
		SpacetimeUnits.withUnitRadiusMass(speedOfLight(dimension), Server.Physics.unitRadiusMass)
}
