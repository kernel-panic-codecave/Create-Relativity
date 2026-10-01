package net.kernelpanicsoft.relativity.accretion

import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper
import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.physics.SpacetimeUnits
import net.kernelpanicsoft.relativity.physics.accretion.Hawking
import net.kernelpanicsoft.relativity.physics.accretion.ThinDisk
import net.kernelpanicsoft.relativity.spacetime.BlackHole
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.neoforged.neoforge.event.tick.LevelTickEvent
import org.joml.Vector3d
import org.joml.Vector3dc
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How black holes gain and lose mass, server side: eating blocks inside the horizon, tearing nearby blocks loose
 * into debris that spirals in, swallowing whatever crosses the horizon (with its angular momentum, which spins the
 * hole), a steady test feed through the disk, the Eddington limit, and Hawking evaporation to a final burst.
 *
 * Mass is in Sable mass units (default block = 1), converted to the hole's geometric mass with the level's units.
 */
object Accretion {
	val GROWTH: GameRules.Key<GameRules.BooleanValue> =
		GameRules.register("relativityBlackHoleGrowth", GameRules.Category.MISC, GameRules.BooleanValue.create(true))

	private const val TICKS_PER_SECOND = 20.0
	private const val SYNC_INTERVAL_TICKS = 10
	/** Smoothing time of the accretion rate the disk and radiation pressure see. */
	private const val RATE_SMOOTHING_SECONDS = 2.0
	/** Horizon positions examined per tick per hole while hunting for blocks to eat. */
	private const val SCAN_PER_TICK = 4096
	/** Random positions in the rip shell tried per tick per hole (mostly air around big holes). */
	private const val RIP_SAMPLES = 96

	/** Gravity of the world blocks are anchored to, blocks/s² (vanilla's, near enough). */
	private const val WORLD_GRAVITY = 32.0

	private class Swallowed(var mass: Double = 0.0, var angularMomentum: Double = 0.0)

	private val swallowed = HashMap<String, Swallowed>()
	private val scanCursor = HashMap<String, Int>()

	fun init() = Unit

	/**
	 * Records mass swallowed by [hole]: [mass] (Sable units) crossing the horizon at [position] with [velocity]
	 * (blocks/s). Its angular momentum about the spin axis goes into the hole too.
	 */
	fun capture(level: Level, hole: BlackHole, mass: Double, position: Vector3dc, velocity: Vector3dc) {
		if (mass <= 0.0) return
		val units = RelativityConfig.units(level.dimension())
		val rel = Vector3d(position).sub(hole.x, hole.y, hole.z)
		// Specific angular momentum about world up (the spin axis), in geometric units (blocks).
		val specific = (rel.z * velocity.x() - rel.x * velocity.z()) / units.c
		val entry = swallowed.getOrPut(hole.id) { Swallowed() }
		entry.mass += mass
		entry.angularMomentum += geometricMass(units, mass) * specific
	}

	fun onLevelTick(event: LevelTickEvent.Post) {
		val level = event.level as? ServerLevel ?: return
		val holes = SpacetimeField.blackHoles(level)
		if (holes.isEmpty()) return
		val units = RelativityConfig.units(level.dimension())
		val growth = level.gameRules.getBoolean(GROWTH)
		for (hole in holes) tickHole(level, hole, units, growth)
		if (level.gameTime % SYNC_INTERVAL_TICKS == 0L) SpacetimeField.syncDirty(level)
	}

	private fun tickHole(level: ServerLevel, hole: BlackHole, units: SpacetimeUnits, growth: Boolean) {
		val config = RelativityConfig.Server.BlackHoles
		val eaten = swallowed.remove(hole.id) ?: Swallowed()
		val eddington = RelativityConfig.eddingtonRate(hole.schwarzschildRadius)
		val ratio = if (eddington > 0.0) hole.accretionRate / eddington else 0.0

		// Matter swallowed straight through the horizon adds mass but radiates nothing (no disk to heat); only what
		// arrives from orbit (captured debris, entities, ships, the feed) counts toward the disk's luminosity.
		val luminous = eaten.mass
		if (growth) {
			eaten.mass += eatInsideHorizon(level, hole, config.eatBudget)
			if (ratio < 1.0) ripLooseGround(level, hole, units, (config.ripBudget * (1.0 - ratio)).toInt())
		}

		var m = hole.mass
		var j = hole.spin * m * m
		m += geometricMass(units, eaten.mass)
		j += eaten.angularMomentum

		// Steady feed through the disk, capped at Eddington: matter arrives from the ISCO, radiating 1 − E_isco of
		// its rest mass, and brings the ISCO's angular momentum (prograde, so it spins the hole up).
		val fed = min(hole.feedRate, eddington) / TICKS_PER_SECOND
		if (fed > 0.0) {
			val disk = ThinDisk(abs(hole.spin).coerceAtMost(BlackHole.MAX_SPIN))
			val dm = geometricMass(units, fed)
			val sense = if (hole.spin < 0.0) -1.0 else 1.0
			j += sense * disk.angularMomentum(disk.innerRadius) * m * dm
			m += disk.energy(disk.innerRadius) * dm
		}

		if (RelativityConfig.Server.BlackHoles.hawking) {
			m = Hawking(RelativityConfig.Server.Physics.planckLength).evaporate(m, units.c / TICKS_PER_SECOND)
			if (2.0 * m <= RelativityConfig.Server.Physics.planckLength) {
				evaporated(level, hole, units)
				return
			}
		}

		val rs = min(2.0 * m, config.maxSchwarzschildRadius)
		val spin = (j / (m * m)).coerceIn(-BlackHole.MAX_SPIN, BlackHole.MAX_SPIN)
		val rate = (luminous + fed) * TICKS_PER_SECOND
		val alpha = 1.0 / (RATE_SMOOTHING_SECONDS * TICKS_PER_SECOND)
		val smoothed = hole.accretionRate + (rate - hole.accretionRate) * alpha
		if (rs == hole.schwarzschildRadius && spin == hole.spin && abs(smoothed - hole.accretionRate) < 1e-9) return
		SpacetimeField.updateBlackHole(level, hole.id, sync = false) {
			it.copy(schwarzschildRadius = rs, spin = spin, accretionRate = if (smoothed < 1e-6) 0.0 else smoothed)
		}
	}

	/** Removes up to [budget] blocks whose centres are inside the horizon; returns their mass. */
	private fun eatInsideHorizon(level: ServerLevel, hole: BlackHole, budget: Int): Double {
		if (budget <= 0) return 0.0
		val reach = ceil(hole.equatorialExtent(hole.horizonRadius)).toInt() + 1
		val side = 2 * reach + 1
		val volume = side * side * side
		var cursor = scanCursor.getOrDefault(hole.id, 0) % volume
		var mass = 0.0
		var eaten = 0
		val pos = BlockPos.MutableBlockPos()
		val center = Vector3d()
		repeat(min(SCAN_PER_TICK, volume)) {
			val dx = cursor % side - reach
			val dy = (cursor / side) % side - reach
			val dz = cursor / (side * side) - reach
			cursor = (cursor + 1) % volume
			pos.set(kotlin.math.floor(hole.x).toInt() + dx, kotlin.math.floor(hole.y).toInt() + dy, kotlin.math.floor(hole.z).toInt() + dz)
			if (!level.isLoaded(pos)) return@repeat
			center.set(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
			if (SpacetimeField.insideHorizon(level, center)?.id != hole.id) return@repeat
			val state = level.getBlockState(pos)
			if (state.isAir || state.getDestroySpeed(level, pos) < 0f) return@repeat
			mass += PhysicsBlockPropertyHelper.getMass(level, pos, state)
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)
			if (++eaten >= budget) {
				scanCursor[hole.id] = cursor
				return mass
			}
		}
		scanCursor[hole.id] = cursor
		return mass
	}

	/**
	 * Tears up to [budget] blocks loose around the hole. A block in the ground isn't in free fall (tides are what
	 * tear apart falling things, M6): it's held to the world, so it goes when the hole's pull plus the world's gravity
	 * exceeds how strongly it's held, which grows with hardness. Debris gets a prograde sideways kick, so it spirals
	 * in and forms the disk rather than dropping straight in.
	 */
	private fun ripLooseGround(level: ServerLevel, hole: BlackHole, units: SpacetimeUnits, budget: Int) {
		if (budget <= 0) return
		val gm = units.c * units.c * hole.mass
		val strength = RelativityConfig.Server.BlackHoles.ripAcceleration
		// Farthest anything can be torn loose: the weakest block (hardness 0), with the world pulling the other way.
		val ripRadius = sqrt(gm / strength)
		val inner = hole.equatorialExtent(hole.horizonRadius)
		if (ripRadius <= inner) return
		val random = level.random
		var ripped = 0
		repeat(RIP_SAMPLES) {
			if (ripped >= budget) return
			// Uniform in the shell's volume.
			val r = cbrt(inner * inner * inner + random.nextDouble() * (ripRadius * ripRadius * ripRadius - inner * inner * inner))
			val dir = Vector3d(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalize()
			val pos = BlockPos.containing(hole.x + dir.x * r, hole.y + dir.y * r, hole.z + dir.z * r)
			if (!level.isLoaded(pos)) return@repeat
			val state = level.getBlockState(pos)
			if (state.isAir || !state.fluidState.isEmpty) return@repeat
			val hardness = state.getDestroySpeed(level, pos)
			if (hardness < 0f) return@repeat
			// Net pull on the block: toward the hole, plus the world's gravity down.
			val toHole = Vector3d(hole.x, hole.y, hole.z).sub(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
			val d2 = toHole.lengthSquared()
			val pull = toHole.normalize(gm / d2).add(0.0, -WORLD_GRAVITY, 0.0).length()
			if (pull < strength * (1.0 + hardness)) return@repeat
			val debris = FallingBlockEntity.fall(level, pos, state)
			// Prograde tangential kick, a fraction of the circular speed: debris spirals in, forming the disk.
			val sense = if (hole.spin < 0.0) -1.0 else 1.0
			val tangent = Vector3d(dir.z, 0.0, -dir.x).mul(sense)
			if (tangent.lengthSquared() > 1e-6) {
				tangent.normalize(sqrt(gm / r) * (0.3 + 0.4 * random.nextDouble()) / TICKS_PER_SECOND)
				debris.deltaMovement = debris.deltaMovement.add(tangent.x, tangent.y, tangent.z)
			}
			ripped++
		}
	}

	/** The hole reached the Planck length: what's left goes out in a flash. */
	private fun evaporated(level: ServerLevel, hole: BlackHole, units: SpacetimeUnits) {
		val remaining = units.massForRadius(RelativityConfig.Server.Physics.planckLength)
		val power = min(8.0, 1.0 + 2.0 * kotlin.math.log10(1.0 + remaining)).toFloat()
		level.sendParticles(ParticleTypes.FLASH, hole.x, hole.y, hole.z, 4, 0.2, 0.2, 0.2, 0.0)
		level.sendParticles(ParticleTypes.END_ROD, hole.x, hole.y, hole.z, 200, 0.5, 0.5, 0.5, 0.6)
		level.explode(null, hole.x, hole.y, hole.z, power, Level.ExplosionInteraction.TNT)
		SpacetimeField.removeBlackHoles(level) { it.id == hole.id }
		swallowed.remove(hole.id)
		scanCursor.remove(hole.id)
		CreateRelativity.LOGGER.info("Black hole {} evaporated", hole.id)
	}

	private fun geometricMass(units: SpacetimeUnits, mass: Double) = units.schwarzschildRadius(mass) / 2.0

	/** Radiation coupling of debris relative to gravity (see [SpacetimeField.radiationAcceleration]). */
	const val DEBRIS_COUPLING = 1.0
	const val CREATURE_COUPLING = 0.3
	const val SHIP_COUPLING = 0.1
}
