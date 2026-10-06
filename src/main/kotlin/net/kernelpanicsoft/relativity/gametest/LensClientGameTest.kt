package net.kernelpanicsoft.relativity.gametest

import com.mojang.blaze3d.platform.NativeImage
import net.kernelpanicsoft.archie.gametest.ClientGameTest
import net.kernelpanicsoft.archie.gametest.ClientGameTestContext
import net.kernelpanicsoft.archie.gametest.TestSingleplayerContext
import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.client.SpacetimeDebugRenderer
import net.kernelpanicsoft.relativity.client.lens.BlackHoleLens
import net.kernelpanicsoft.relativity.client.lens.LensVoxels
import net.kernelpanicsoft.relativity.client.lens.VertexLens
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import java.nio.file.Files
import java.nio.file.Path

/** The lens as drawn (client GameTests: a real world, rendered). */
@Suppress("unused")
class LensClientGameTest {
	/**
	 * A spinning, feeding hole in view: the quarter-resolution Kerr table plus edge re-marching draws what marching every
	 * pixel draws, in a fraction of the GPU time.
	 */
	@ClientGameTest
	fun ClientGameTestContext.kerrTableMatchesFullMarch() {
		runOnClient { minecraft ->
			minecraft.options.renderDistance().set(6)
			minecraft.options.enableVsync().set(false)
			minecraft.options.hideGui = true // no chat or HUD text changing between the screenshots
			SpacetimeDebugRenderer.hidden = true
		}
		withTestWorld { // superflat: the ground is at y = -61
			waitTicks(60) // (waitForChunksRender never settles here)
			for (command in listOf(
				"gamemode spectator @a", "difficulty peaceful", "gamerule doMobSpawning false", "kill @e[type=!player]", "time set noon", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false",
				"weather clear", "gamerule relativityBlackHoleGrowth false", "tp @a 0.5 -50 0.5 -90 8",
				"relativity bh create 30.5 -54 0.5 3 0.9", "relativity bh feed 1000000",
			)) command(command)
			waitTicks(100) // the disk brightens as the feed's rate builds up
			// Feeding grows the hole; freezing the world holds it (and every screenshot) still.
			command("tick freeze")
			waitTicks(40)
			val frozenHole = hole()

			val table = measure(BlackHoleLens.KerrMode.TABLE)
			val tableShot = takeScreenshot("kerr_table")
			measure(BlackHoleLens.KerrMode.TABLE_DEBUG)
			val remarched = fraction(takeScreenshot("kerr_table_remarched")) { r, g, b -> r == 255 && g == 0 && b == 0 }
			measure(BlackHoleLens.KerrMode.TABLE_ERROR)
			val errorShot = takeScreenshot("kerr_table_error")
			// Error channels (see black_hole_lens.fsh): red 64 per sample spacing of exit direction (a pixel, or more where
			// the lens squeezes the background), green a different fate or
			// disk image, blue 64 per percent of the disk hit point's radius.
			val exitOff = fraction(errorShot) { r, _, _ -> r > 64 }
			val fateOff = fraction(errorShot) { _, g, _ -> g > 128 }
			val diskOff = fraction(errorShot) { _, _, b -> b > 64 }
			val full = measure(BlackHoleLens.KerrMode.FULL_MARCH)
			val fullShot = takeScreenshot("kerr_full_march")
			runOnClient {
				BlackHoleLens.kerrMode = BlackHoleLens.KerrMode.TABLE
				BlackHoleLens.timeGpu = false
				it.options.hideGui = false
				SpacetimeDebugRenderer.hidden = false
			}
			CreateRelativity.LOGGER.info(
				"Kerr lens GPU time: table {} ms ({}% of pixels re-marched), full march {} ms. Table error: exit direction off by over a sample spacing in {}% of pixels, fate or disk image wrong in {}%, disk hit point off by over 1% in {}% (screenshots {}, {}, {})",
				"%.2f".format(table), "%.1f".format(remarched * 100), "%.2f".format(full), "%.3f".format(exitOff * 100),
				"%.3f".format(fateOff * 100), "%.3f".format(diskOff * 100), tableShot, fullShot, errorShot,
			)
			val laterHole = hole()
			assertTrue(laterHole == frozenHole) { "The fed hole changed while the world was frozen: $frozenHole → $laterHole" }
			assertTrue(table.isFinite() && full.isFinite()) { "No GPU timings (table $table, full $full): is the lens running?" }
			assertTrue(exitOff < 0.0002) { "Exit direction off by over a sample spacing in ${exitOff * 100}% of pixels" }
			assertTrue(fateOff < 0.001) { "Fate or disk image wrong in ${fateOff * 100}% of pixels" }
			// Up to 0.1%: a one-pixel line where the view grazes the disk plane (the hit radius changes faster than the
			// table's curvature test assumes) and secondary disk images on the shadow's rim; ~0.07% here.
			assertTrue(diskOff < 0.001) { "Disk hit point off by over 1% in ${diskOff * 100}% of pixels" }
			// At this small window fixed costs dominate and GPU clocks vary; at 1080p the table took a quarter as long.
			assertTrue(table < 0.75 * full) { "Table ($table ms) isn't much faster than the full march ($full ms)" }
		}
	}

	/**
	 * Fragment (post-pass) lens with nearby ground: a hole (static, then spinning) above a checkerboard floor with red
	 * posts, seen from several poses and compared with [LensReference]'s CPU trace of the same view: with the post pass
	 * alone, and with vertex lensing (static holes only; it leaves spinning ones to the post pass). (The old two-leg
	 * model was 13–41% wrong here.)
	 */
	@ClientGameTest
	fun ClientGameTestContext.groundAroundHole() {
		runOnClient { minecraft ->
			minecraft.options.renderDistance().set(6)
			minecraft.options.hideGui = true
			SpacetimeDebugRenderer.hidden = true
			minecraft.options.bobView().set(false)
			minecraft.options.fovEffectScale().set(0.0)
		}
		withTestWorld { // superflat: the ground is at y = -61
			waitTicks(60)
			for (command in listOf(
				"gamemode spectator @a", "difficulty peaceful", "gamerule doMobSpawning false", "kill @e[type=!player]",
				"time set noon", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "weather clear",
				"gamerule relativityBlackHoleGrowth false",
			)) command(command)
			server.runOnServer { minecraft ->
				val level = minecraft.overworld()
				for (x in -32..32) for (z in -32..32) {
					val block = if (Math.floorMod(Math.floorDiv(x, 2) + Math.floorDiv(z, 2), 2) == 0) Blocks.WHITE_CONCRETE else Blocks.BLUE_CONCRETE
					level.setBlock(BlockPos(x, -61, z), block.defaultBlockState(), 2)
				}
				for (x in -24..24 step 8) for (z in -24..24 step 8) for (y in -60..-58) {
					level.setBlock(BlockPos(x, y, z), Blocks.RED_CONCRETE.defaultBlockState(), 2)
				}
			}
			command("tick freeze")
			val failures = mutableListOf<String>()
			val plain = listOf(
				"side" to "-14.5 -54 0.5 -90 10",      // level with the hole, looking at it
				"above" to "-9.5 -46 0.5 -90 45",      // above and to the side, looking down at it
				"offscreen" to "-4.5 -48 8.5 -160 75", // looking down with the hole just off-screen
			)
			for (spin in listOf(0.0, 0.9)) for ((pose, at) in plain) lensPose("${pose}_spin$spin", spin, at, failures)
			// A busier world: more surfaces off screen and behind others, where the voxel grid answers.
			server.runOnServer { minecraft ->
				val level = minecraft.overworld()
				val random = java.util.Random(11)
				val colours = listOf(Blocks.WHITE_CONCRETE, Blocks.BLUE_CONCRETE, Blocks.RED_CONCRETE)
				for (x in -48..48) for (z in -48..48) for (y in -60..-30) {
					if ((x * x + (y + 54) * (y + 54) + z * z) < 100 || random.nextFloat() > 0.08f) continue
					level.setBlock(BlockPos(x, y, z), colours[random.nextInt(3)].defaultBlockState(), 2)
				}
			}
			waitTicks(40)
			for ((pose, at) in plain.take(2)) lensPose("${pose}_busy", 0.0, at, failures)
			runOnClient {
				BlackHoleLens.marchEveryPixel = false
				BlackHoleLens.timeGpu = false
			}
			runOnClient {
				it.options.hideGui = false
				SpacetimeDebugRenderer.hidden = false
			}
			assertTrue(failures.isEmpty()) { failures.joinToString("; ") }
		}
	}

	/**
	 * The geometry the lens traces where the frame can't show (LensVoxels) is every block's full render: block models
	 * of any shape, cutout plants, a block entity's renderer (a chest), and Flywheel-drawn Create shafts and cogs.
	 * Each gets quads in the grid; screenshots of the hole in front of them show how they come out bent.
	 */
	@ClientGameTest
	fun ClientGameTestContext.modelsAroundHole() {
		runOnClient { minecraft ->
			minecraft.options.renderDistance().set(6)
			minecraft.options.hideGui = true
			SpacetimeDebugRenderer.hidden = true
		}
		withTestWorld { // superflat: the ground is at y = -61
			waitTicks(60)
			for (command in listOf(
				"gamemode spectator @a", "difficulty peaceful", "gamerule doMobSpawning false", "kill @e[type=!player]",
				"time set noon", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "weather clear",
				"gamerule relativityBlackHoleGrowth false",
			)) command(command)
			val shaft = com.simibubi.create.AllBlocks.SHAFT.get().defaultBlockState()
			val cog = com.simibubi.create.AllBlocks.COGWHEEL.get().defaultBlockState()
			val placed = mapOf(
				"stairs" to BlockPos(6, -60, 0) to Blocks.OAK_STAIRS.defaultBlockState(),
				"slab" to BlockPos(6, -60, 2) to Blocks.STONE_SLAB.defaultBlockState(),
				"fence" to BlockPos(6, -60, -2) to Blocks.OAK_FENCE.defaultBlockState(),
				"poppy" to BlockPos(5, -60, 1) to Blocks.POPPY.defaultBlockState(),
				"torch" to BlockPos(5, -60, -1) to Blocks.TORCH.defaultBlockState(),
				"chest" to BlockPos(7, -60, 1) to Blocks.CHEST.defaultBlockState(),
				"shaft" to BlockPos(7, -60, -1) to shaft,
				"cog" to BlockPos(7, -59, -1) to cog,
			)
			server.runOnServer { minecraft ->
				val level = minecraft.overworld()
				for ((key, state) in placed) level.setBlock(key.second, state, 2)
			}
			command("relativity bh create 0.5 -56 0.5 2 0")
			command("tick freeze")
			command("tp @a -10.5 -55 0.5 -90 5")
			runOnClient {
				it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO
				BlackHoleLens.timeGpu = true
			}
			// Filling: the most a frame spends on it.
			var worst = 0.0
			repeat(60) {
				waitTick()
				worst = maxOf(worst, computeOnClient { LensVoxels.updateMillis })
			}
			val counts = computeOnClient { minecraft -> placed.keys.associate { (name, pos) -> name to LensVoxels.quadsAt(minecraft.level!!, pos) } }
			val steady = (0 until 20).map { waitTick(); computeOnClient { LensVoxels.updateMillis } }.sorted()[10]
			CreateRelativity.LOGGER.info("Lens geometry: quads per block {}; filling took up to {} ms a frame, then {} ms", counts, "%.2f".format(worst), "%.2f".format(steady))
			takeScreenshot("models_table")
			runOnClient { VertexLens.forced = true }
			waitTicks(10)
			takeScreenshot("models_vertex")
			runOnClient {
				VertexLens.forced = null
				BlackHoleLens.timeGpu = false
				it.options.hideGui = false
				SpacetimeDebugRenderer.hidden = false
			}
			for ((name, count) in counts) assertTrue(count > 0) { "No quads for the $name in the lens grid ($counts)" }
			assertTrue(counts.getValue("stairs") >= 10) { "The stairs should have more than a cube's quads ($counts)" }
		}
	}

	/**
	 * One view of a hole (r_s 2, [spin]) at (0.5, -54, 0.5) from [at] ("x y z yaw pitch"), traced on the CPU and drawn by
	 * each lens mode: post-pass table and vertex lensing, with each source for what the frame can't show (voxels, the
	 * default; nothing). Logs accuracy and cost (medians over 20 frames); adds the default modes' misses to [failures].
	 */
	private fun TestSingleplayerContext.lensPose(name: String, spin: Double, at: String, failures: MutableList<String>) = with(clientContext) {
		command("relativity bh clear")
		command("relativity bh create 0.5 -54 0.5 2 $spin")
		command("tp @a $at")
		runOnClient { it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO }
		waitTicks(40) // the voxel grid fills over a few frames
		// A fixed grid, whatever the window size, traced a few rows per client call (each has a time limit).
		val columns = 120
		val rows = computeOnClient { columns * it.window.height / it.window.width }
		val reference = Array(rows) { arrayOfNulls<LensReference.Kind>(columns) }
		for (first in 0 until rows step 8) {
			val part = computeOnClient { minecraft ->
				val hole = SpacetimeField.blackHoles(minecraft.level!!).single()
				LensReference.trace(minecraft, hole, columns, rows, 96.0, 60.0, first until minOf(first + 8, rows))
			}
			for (row in first until minOf(first + 8, rows)) reference[row] = part[row]
		}
		val wrongBy = HashMap<String, Double>()
		val traced = camera()
		// Render modes (post-pass table, vertex lensing) by what answers for surfaces the frame can't show.
		val modes = listOf(
			Triple("table", BlackHoleLens.Unseen.VOXELS, false), Triple("vertex", BlackHoleLens.Unseen.VOXELS, true),
			Triple("table_none", BlackHoleLens.Unseen.NONE, false), Triple("vertex_none", BlackHoleLens.Unseen.NONE, true),
		)
		for ((mode, unseen, vertex) in modes) {
			runOnClient {
				BlackHoleLens.unseen = unseen
				BlackHoleLens.timeGpu = true
				VertexLens.forced = vertex
			}
			// The player isn't frozen with the world, and can drift: back to the pose, motion stopped.
			command("tp @a $at")
			runOnClient { it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO }
			waitTicks(20)
			val now = camera()
			if (now.first.distanceTo(traced.first) > 0.01 || now.second.distanceTo(traced.second) > 1e-3) {
				failures += "$name ($mode): the camera moved from the traced pose ($traced → $now)"
			}
			val samples = (0 until 20).map {
				waitTick()
				computeOnClient {
					doubleArrayOf(BlackHoleLens.gpuMillis, if (unseen == BlackHoleLens.Unseen.VOXELS) LensVoxels.updateMillis else 0.0)
				}
			}
			val median = DoubleArray(2) { k -> samples.map { it[k] }.filter { it.isFinite() }.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] } }
			val wrong = LensReference.compare(reference, takeScreenshot("ground_${name}_$mode"))
			wrongBy[mode] = wrong
			CreateRelativity.LOGGER.info(
				"Lens vs reference, {} ({}): {}% of pixels in the wrong class; GPU lens {} ms, voxels CPU {} ms",
				name, mode, "%.1f".format(wrong * 100), "%.2f".format(median[0]), "%.2f".format(median[1]),
			)
		}
		runOnClient {
			BlackHoleLens.unseen = BlackHoleLens.Unseen.VOXELS
			VertexLens.forced = null
		}
		// Within 4%, or (where the reference's classes are rough, e.g. shaded block sides) a point of the best mode.
		val best = wrongBy.values.min()
		for (mode in listOf("table", "vertex")) {
			val wrong = wrongBy.getValue(mode)
			if (wrong > maxOf(0.04, best + 0.01)) failures += "$name ($mode): ${wrong * 100}% of pixels in the wrong class"
		}
	}

	/** The camera's position and look direction. */
	private fun ClientGameTestContext.camera(): Pair<net.minecraft.world.phys.Vec3, net.minecraft.world.phys.Vec3> = computeOnClient {
		val camera = it.gameRenderer.mainCamera
		camera.position to net.minecraft.world.phys.Vec3(camera.lookVector)
	}

	/** The overworld's hole: its Schwarzschild radius and spin. */
	private fun TestSingleplayerContext.hole(): Pair<Double, Double> = server.computeOnServer { minecraft ->
		SpacetimeField.blackHoles(minecraft.overworld()).single().let { it.schwarzschildRadius to it.spin }
	}

	/**
	 * A singleplayer world for [block], deleted afterwards: Archie's withSingleplayer leaves every test world in
	 * run/saves.
	 */
	private fun ClientGameTestContext.withTestWorld(block: TestSingleplayerContext.() -> Unit) {
		val world = worldBuilder().create()
		val save = world.saveDirectory
		try {
			world.block()
		} finally {
			world.close()
			if (save.parent?.fileName?.toString() == "saves") save.toFile().deleteRecursively()
		}
	}

	/** Runs a command as the server (Archie's runCommand fails on its reflective call here). */
	private fun TestSingleplayerContext.command(command: String) = server.runOnServer { minecraft ->
		minecraft.commands.performPrefixedCommand(minecraft.createCommandSourceStack(), command)
	}

	/** Median GPU time of the lens pipeline (ms) over a second or so, in a given Kerr mode. */
	private fun ClientGameTestContext.measure(mode: BlackHoleLens.KerrMode): Double {
		runOnClient {
			BlackHoleLens.kerrMode = mode
			BlackHoleLens.timeGpu = true
		}
		waitTicks(10)
		val samples = ArrayList<Double>()
		repeat(20) {
			waitTick()
			samples += computeOnClient { BlackHoleLens.gpuMillis }
		}
		return samples.filter { it.isFinite() }.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }
	}

	/** Fraction of a screenshot's pixels whose (red, green, blue) pass [test]. */
	private fun fraction(shot: Path, test: (Int, Int, Int) -> Boolean): Double =
		Files.newInputStream(shot).use { NativeImage.read(it) }.use { image ->
			var count = 0L
			for (y in 0 until image.height) for (x in 0 until image.width) {
				val abgr = image.getPixelRGBA(x, y)
				if (test(abgr and 0xFF, abgr shr 8 and 0xFF, abgr shr 16 and 0xFF)) count += 1
			}
			count.toDouble() / (image.width.toLong() * image.height)
		}
}
