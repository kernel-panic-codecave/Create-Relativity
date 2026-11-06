package net.kernelpanicsoft.relativity.lens

import dev.architectury.registry.registries.DeferredRegister
import net.kernelpanicsoft.archie.registries.RegistrarHelper
import net.kernelpanicsoft.archie.registries.RegistryHelper
import net.kernelpanicsoft.relativity.CreateRelativity
import net.minecraft.core.Registry
import net.minecraft.resources.ResourceKey

/** The mod's own registries: [LENS_FIELD_TYPES], the lens's field source types (any mod may add to it). */
object RelativityRegistries : RegistrarHelper(CreateRelativity.MOD_ID) {
	/** The lens's field source types' registry's key: another mod adds a type with a DeferredRegister on it. */
	val LENS_FIELD_TYPE_KEY: ResourceKey<Registry<LensFieldType>> = ResourceKey.createRegistryKey(CreateRelativity.id("lens_field_type"))

	/** The lens's field source types ([LensFieldType]). */
	val LENS_FIELD_TYPES by registry<LensFieldType>("lens_field_type")
}

/** This mod's own field source types. */
object RelativityLensFields : RegistryHelper<LensFieldType>(DeferredRegister.create(CreateRelativity.MOD_ID, RelativityRegistries.LENS_FIELD_TYPE_KEY)) {
	val HOLE by register("hole") { HoleField }
	val MASS by register("mass") { MassField }
	val TUBE by register("tube") { TubeField }
	val WARP_TUBE by register("warp_tube") { WarpTubeField }
	val BUBBLE by register("bubble") { BubbleField }
}
