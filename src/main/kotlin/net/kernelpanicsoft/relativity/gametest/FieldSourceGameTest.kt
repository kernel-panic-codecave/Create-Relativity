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
}
