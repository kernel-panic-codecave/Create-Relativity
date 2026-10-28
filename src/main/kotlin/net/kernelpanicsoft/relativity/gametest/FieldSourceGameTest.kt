package net.kernelpanicsoft.relativity.gametest

import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.relativity.spacetime.FieldSource
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.kernelpanicsoft.relativity.time.LocalClocks
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d

/** Sources other than holes (M10): weak-field masses of either sign, through the same field as holes. */
@Suppress("unused")
class FieldSourceGameTest {
	/**
	 * A mass of [mass] (geometric, blocks) in the middle of the test, debris let go at rest [distance] blocks to its +x
	 * side; [check] gets the debris's distance from the mass after [ticks] (or null if it's gone) and the clock rate at
	 * the debris's start.
	 */
	private fun GameTestHelper.fallNear(mass: Double, distance: Double, ticks: Long, check: (Double?, Double) -> Unit) {
		val level = level as ServerLevel
		val center = absoluteVec(Vec3(10.5, 4.5, 10.5))
		val provider = FieldSource.Provider { asked ->
			if (asked === level) listOf(FieldSource.Mass(Vector3d(center.x, center.y, center.z), mass, softening = 0.5)) else emptyList()
		}
		val setupTick = 2L // chunks start entity-ticking a moment after the test does
		var tick = 0L
		var debris: FallingBlockEntity? = null
		var rate = Double.NaN
		onEachTick {
			tick++
			if (tick == setupTick) {
				SpacetimeField.addProvider(provider)
				val start = Vec3(center.x + distance, center.y, center.z)
				rate = LocalClocks.rateAt(level, Vector3d(start.x, start.y, start.z))
				debris = FallingBlockEntity.fall(level, BlockPos.containing(start), Blocks.SAND.defaultBlockState()).also {
					it.isNoGravity = true
					it.setPos(start.x, start.y - 0.49, start.z)
					it.deltaMovement = Vec3.ZERO
				}
			}
			if (tick == setupTick + ticks) {
				SpacetimeField.removeProvider(provider)
				val d = debris?.takeIf { !it.isRemoved }?.let { Vec3(it.x, it.y + 0.49, it.z).distanceTo(center) }
				debris?.takeIf { !it.isRemoved }?.discard()
				check(d, rate)
				succeed()
			}
		}
	}

	@GameTest(template = MEDIUM, batch = "relativity_mass_positive", timeoutTicks = 40)
	fun GameTestHelper.positiveMassAttractsAndSlowsClocks() = fallNear(mass = 0.01, distance = 4.0, ticks = 6) { d, rate ->
		assertTrue(d != null && d < 3.9) { "Debris let go 4 blocks from a positive mass ended $d blocks away; expected pulled in" }
		assertTrue(rate < 1.0) { "A clock 4 blocks from a positive mass ran at $rate; expected slower than 1" }
	}

	@GameTest(template = MEDIUM, batch = "relativity_mass_negative", timeoutTicks = 40)
	fun GameTestHelper.negativeMassRepels() = fallNear(mass = -0.01, distance = 4.0, ticks = 6) { d, _ ->
		assertTrue(d != null && d > 4.1) { "Debris let go 4 blocks from a negative mass ended $d blocks away; expected pushed out" }
	}

	/** Once the sections round a mass are sampled ([net.kernelpanicsoft.relativity.spacetime.FieldGrid]), free fall through them matches the exact field. */
	@GameTest(template = MEDIUM, batch = "relativity_mass_grid", timeoutTicks = 200)
	fun GameTestHelper.sampledFieldMatchesTheMass() {
		val level = level as ServerLevel
		val center = absoluteVec(Vec3(10.5, 4.5, 10.5))
		val mass = FieldSource.Mass(Vector3d(center.x, center.y, center.z), 0.05, softening = 1.5)
		val provider = FieldSource.Provider { asked -> if (asked === level) listOf(mass) else emptyList() }
		SpacetimeField.addProvider(provider)
		val probes = listOf(Vector3d(center.x + 3.3, center.y + 0.7, center.z - 1.2), Vector3d(center.x - 5.1, center.y - 2.2, center.z + 4.6))
		val c = net.kernelpanicsoft.relativity.config.RelativityConfig.speedOfLight(level.dimension())
		probes.forEach { SpacetimeField.acceleration(level, it, Vector3d(), Vector3d()) } // starts the builds
		onEachTick {
			if (net.kernelpanicsoft.relativity.spacetime.FieldGrid.builtSections(level) < 8) return@onEachTick
			try {
				for (probe in probes) {
					val sampled = SpacetimeField.acceleration(level, probe, Vector3d(), Vector3d())
					val p = DoubleArray(3)
					net.kernelpanicsoft.relativity.spacetime.GameFrame.toPhysics(Vector3d(mass.center).sub(probe), p)
					val exact = DoubleArray(3)
					net.kernelpanicsoft.relativity.physics.geodesic.CoordinateAcceleration(
						net.kernelpanicsoft.relativity.physics.spacetime.field.ComposedSpacetime(listOf(net.kernelpanicsoft.relativity.physics.spacetime.field.PointMass(p[0], p[1], p[2], mass.mass, mass.softening))),
					).compute(DoubleArray(4), DoubleArray(3), exact)
					val expected = net.kernelpanicsoft.relativity.spacetime.GameFrame.toGame(exact, 0, Vector3d()).mul(c * c)
					val error = Vector3d(sampled).sub(expected).length() / expected.length()
					assertTrue(error < 0.02) { "Free fall at $probe through the sampled field is off by ${"%.1f".format(error * 100)}% (sampled $sampled, exact $expected)" }
				}
			} finally {
				SpacetimeField.removeProvider(provider)
			}
			succeed()
		}
	}

	/**
	 * A bubble fitted to a box (ShipWarp.around, as a ship's or `relativity bubble add`'s) leaves the box flat whatever
	 * its warp: at points all through it, corners included, the field pulls nothing to speak of (under a hundredth of a
	 * block/s², against the hundreds of thousands its wall can), at warp 0.5 and at warp 5.
	 */
	@GameTest(template = MEDIUM, batch = "relativity_bubble_flat", timeoutTicks = 40)
	fun GameTestHelper.bubbleBoxIsFlat() {
		val level = level as ServerLevel
		val centre = absoluteVec(Vec3(10.5, 4.5, 10.5))
		val halves = listOf(Vector3d(3.0, 0.0, 0.0), Vector3d(0.0, 1.5, 0.0), Vector3d(0.0, 0.0, 2.0))
		var worst = 0.0
		var where = ""
		for (warp in listOf(0.5, 5.0)) {
			val bubble = net.kernelpanicsoft.relativity.sable.ShipWarp.around(Vector3d(centre.x, centre.y, centre.z), halves, Vector3d(1.0, 0.0, 0.0), net.kernelpanicsoft.relativity.physics.WarpScale.speed(warp))
			val provider = FieldSource.Provider { asked -> if (asked === level) listOf(bubble) else emptyList() }
			SpacetimeField.addProvider(provider)
			try {
				for (i in -2..2) for (j in -2..2) for (k in -2..2) {
					val at = Vector3d(centre.x + 3.0 * i / 2, centre.y + 1.5 * j / 2, centre.z + 2.0 * k / 2)
					val pull = SpacetimeField.acceleration(level, at, Vector3d(), Vector3d()).length()
					if (pull > worst) { worst = pull; where = "warp $warp at ($i, $j, $k) of the box" }
				}
			} finally {
				SpacetimeField.removeProvider(provider)
			}
		}
		assertTrue(worst < 0.01) { "Inside a bubble fitted to its box the field pulls ${"%.3g".format(worst)} blocks/s² ($where)" }
		succeed()
	}

	/**
	 * A living thing dropped into a warp bubble standing still (as `relativity bubble add` places one: warp 5, moving diagonally):
	 * its wall's pull is huge and sharp, and once flung players hundreds of thousands of blocks a tick. The field takes
	 * nothing past c: it never moves faster than that. And clocks inside run (at rest against a push faster than light,
	 * they ride it instead): one at rest at the centre, and one moving with the push, run at world time.
	 */
	@GameTest(template = MEDIUM, batch = "relativity_bubble_fall", timeoutTicks = 80)
	fun GameTestHelper.bubbleDoesNotFling() {
		val level = level as ServerLevel
		val centre = absoluteVec(Vec3(10.5, 4.5, 10.5))
		val bubble = net.kernelpanicsoft.relativity.sable.ShipWarp.around(
			Vector3d(centre.x, centre.y, centre.z),
			listOf(Vector3d(2.5, 0.0, 0.0), Vector3d(0.0, 2.0, 0.0), Vector3d(0.0, 0.0, 2.5)),
			Vector3d(-0.52, -0.67, -0.53), net.kernelpanicsoft.relativity.physics.WarpScale.speed(5.0),
		)
		val provider = FieldSource.Provider { asked -> if (asked === level) listOf(bubble) else emptyList() }
		val c = net.kernelpanicsoft.relativity.config.RelativityConfig.speedOfLight(level.dimension()) / 20.0
		var tick = 0L
		var mob: net.minecraft.world.entity.LivingEntity? = null
		var fastest = 0.0
		onEachTick {
			tick++
			if (tick == 2L) {
				SpacetimeField.addProvider(provider)
				val at = Vector3d(centre.x, centre.y, centre.z)
				val still = LocalClocks.rateAt(level, at)
				val c0 = net.kernelpanicsoft.relativity.config.RelativityConfig.speedOfLight(level.dimension())
				val riding = SpacetimeField.properTimeRate(level, at, Vector3d(bubble.direction).mul(bubble.speed * c0))
				assertTrue(kotlin.math.abs(still - 1.0) < 1e-3 && kotlin.math.abs(riding - 1.0) < 1e-3) {
					"Clocks inside a warp 5 bubble ran at $still (at rest) and $riding (riding it); expected 1"
				}
				// Above the bubble, falling in through its top.
				// (An armour stand: a living thing that falls, as a player does; a mob without AI doesn't move.)
				mob = net.minecraft.world.entity.EntityType.ARMOR_STAND.create(level)!!.also {
					it.setPos(centre.x + 0.3, centre.y + 6.0, centre.z - 0.2)
					level.addFreshEntity(it)
				}
			}
			mob?.let { m -> if (tick > 3L) fastest = maxOf(fastest, Vec3(m.x - m.xo, m.y - m.yo, m.z - m.zo).length()) }
			if (tick == 60L) {
				SpacetimeField.removeProvider(provider)
				mob?.discard()
				assertTrue(fastest <= c * 1.05) { "It moved %.1f blocks in a tick through the bubble; c is %.1f a tick".format(fastest, c) }
				succeed()
			}
		}
	}
}
