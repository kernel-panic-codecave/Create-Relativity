package net.kernelpanicsoft.relativity.create

import com.simibubi.create.content.kinetics.KineticNetwork
import com.simibubi.create.content.kinetics.base.KineticBlockEntity
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.mixin.create.TorquePropagatorAccessor
import net.kernelpanicsoft.relativity.time.LocalClocks
import net.kernelpanicsoft.relativity.time.ProperTimeHolder
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.event.tick.LevelTickEvent
import kotlin.math.abs

/**
 * A rotating network whose blocks sit at different clock rates can't all turn together: the parts deeper in a gravity
 * well (or on a fast ship) run slow, so the shaft twists. Each member adds k × |its dτ/dt − the source's| × |speed| of
 * stress; long shafts strung down a well overstress unless they're short or the hole is gentle.
 */
object ShaftTorsion {
	private const val REFRESH_TICKS = 20L

	private fun rateOf(blockEntity: KineticBlockEntity): Double =
		(blockEntity as ProperTimeHolder).`create_relativity$getLocalTime`()?.rate ?: 1.0

	/** Torsion stress to add to [network]'s total (server side). */
	@JvmStatic
	fun extraStress(network: KineticNetwork): Float {
		val k = RelativityConfig.Server.Time.shaftTorsion
		if (k <= 0.0 || !LocalClocks.enabled) return 0f
		val source = network.sources.keys.maxByOrNull { it.calculateAddedStressCapacity() } ?: return 0f
		val reference = rateOf(source)
		var stress = 0.0
		for (member in network.members.keys) {
			val difference = abs(rateOf(member) - reference)
			if (difference > 1e-3) stress += k * difference * abs(member.theoreticalSpeed)
		}
		return stress.toFloat()
	}

	/** Rates drift as holes grow and ships move; networks with dilated members re-total their stress every second. */
	fun onLevelTick(event: LevelTickEvent.Post) {
		val level = event.level as? ServerLevel ?: return
		if (level.gameTime % REFRESH_TICKS != 0L) return
		val networks = TorquePropagatorAccessor.`create_relativity$getNetworks`()[level] ?: return
		for (network in networks.values.toList()) {
			if (network.members.keys.any { (it as ProperTimeHolder).`create_relativity$getLocalTime`() != null }) {
				network.updateStress()
			}
		}
	}
}
