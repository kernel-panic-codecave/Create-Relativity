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
		if (!ClientTestInput.selected("kerrTableMatchesFullMarch")) return
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
	 * The lens with nearby ground: a hole (static, then spinning) above a checkerboard floor with red posts, seen from
	 * several poses and compared with [LensReference]'s CPU trace of the same view, with and without the geometry
	 * round the hole for the sky's bent paths.
	 */
	@ClientGameTest
	fun ClientGameTestContext.groundAroundHole() {
		if (!ClientTestInput.selected("groundAroundHole")) return
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
	 * Traced geometry respects the render distance: a red wall behind the hole shows in its Einstein ring with the wall
	 * inside the render distance, and not with it outside. And it's lit by the world's light through the game's
	 * lightmap: a screenshot at midnight with a torch by the hole, for a look.
	 */
	@ClientGameTest
	fun ClientGameTestContext.renderDistanceAndLight() {
		if (!ClientTestInput.selected("renderDistanceAndLight")) return
		runOnClient { minecraft ->
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
			// A red wall 48 blocks past the hole, across the line of sight.
			server.runOnServer { minecraft ->
				val level = minecraft.overworld()
				for (z in -12..12) for (y in -60..-48) level.setBlock(BlockPos(48, y, z), Blocks.RED_CONCRETE.defaultBlockState(), 2)
			}
			command("relativity bh create 0.5 -54 0.5 2 0")
			command("tick freeze")
			val reds = HashMap<Int, Double>()
			for (chunks in listOf(8, 2)) {
				runOnClient { it.options.renderDistance().set(chunks) }
				command("tp @a -11.5 -54 0.5 -90 0")
				runOnClient { it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO }
				waitTicks(80)
				// Red anywhere: at 8 chunks the wall shows directly and in the ring; at 2 (32 blocks) it's beyond the render
				// distance but still loaded (the client keeps a few chunks more), so only the lens's cut keeps it out.
				reds[chunks] = fraction(takeScreenshot("render_distance_$chunks")) { r, g, b -> r > 50 && r > 3 * g && r > 3 * b }
			}
			CreateRelativity.LOGGER.info("Red (the wall) on screen: render distance 8 → {}%, 2 → {}%", "%.2f".format(reds.getValue(8) * 100), "%.2f".format(reds.getValue(2) * 100))
			command("time set midnight")
			server.runOnServer { minecraft -> minecraft.overworld().setBlock(BlockPos(4, -60, 0), Blocks.TORCH.defaultBlockState(), 2) }
			runOnClient { it.options.renderDistance().set(6) }
			command("tp @a -9.5 -46 0.5 -90 45")
			runOnClient { it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO }
			waitTicks(80)
			takeScreenshot("midnight")
			runOnClient {
				it.options.hideGui = false
				SpacetimeDebugRenderer.hidden = false
			}
			assertTrue(reds.getValue(8) > 0.002) { "The wall within the render distance doesn't show (${reds.getValue(8) * 100}% red)" }
			assertTrue(reds.getValue(2) < 0.0002) { "The wall beyond the render distance shows (${reds.getValue(2) * 100}% red)" }
		}
	}

	/**
	 * Flywheel's instanced parts bend with the vertex lens (its shaders get the lens too): a wall of Create shafts
	 * across the lensed view, drawn by Flywheel and then (visualization off) through vanilla's renderers, which the
	 * vertex lens is known to bend; the two screenshots should match.
	 */
	@ClientGameTest
	fun ClientGameTestContext.flywheelBends() {
		if (!ClientTestInput.selected("flywheelBends")) return
		runOnClient { minecraft ->
			minecraft.options.renderDistance().set(6)
			minecraft.options.hideGui = true
			SpacetimeDebugRenderer.hidden = true
			minecraft.options.bobView().set(false)
		}
		withTestWorld {
			waitTicks(60)
			for (command in listOf(
				"gamemode spectator @a", "difficulty peaceful", "gamerule doMobSpawning false", "kill @e[type=!player]",
				"time set noon", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "weather clear",
				"gamerule relativityBlackHoleGrowth false",
			)) command(command)
			val shaft = com.simibubi.create.AllBlocks.SHAFT.get().defaultBlockState()
				.setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, net.minecraft.core.Direction.Axis.Z)
			server.runOnServer { minecraft ->
				val level = minecraft.overworld()
				for (z in -8..8) for (y in -60..-50 step 2) level.setBlock(BlockPos(6, y, z), shaft, 2)
			}
			command("relativity bh create 0.5 -55 0.5 2 0")
			command("tick freeze")
			command("tp @a -12.5 -55 0.5 -90 0")
			runOnClient { it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO }
			waitTicks(40)
			settle()
			val instanced = takeScreenshot("flywheel_instanced")
			// Flywheel drops the block entities it draws from sections as they're built: rebuild them all.
			runOnClient {
				VertexLens.flywheelOff = true
				it.levelRenderer.allChanged()
			}
			waitTicks(40)
			settle()
			val vanilla = takeScreenshot("flywheel_vanilla")
			runOnClient {
				VertexLens.flywheelOff = false
				it.levelRenderer.allChanged()
				it.options.hideGui = false
				SpacetimeDebugRenderer.hidden = false
			}
			val differing = Files.newInputStream(instanced).use { NativeImage.read(it) }.use { a ->
				Files.newInputStream(vanilla).use { NativeImage.read(it) }.use { b ->
					var count = 0L
					for (y in 0 until a.height) for (x in 0 until a.width) {
						val p = a.getPixelRGBA(x, y)
						val q = b.getPixelRGBA(x, y)
						var diff = 0
						for (k in 0..2) diff = maxOf(diff, Math.abs((p shr (8 * k) and 0xFF) - (q shr (8 * k) and 0xFF)))
						if (diff > 40) count += 1
					}
					count.toDouble() / (a.width.toLong() * a.height)
				}
			}
			CreateRelativity.LOGGER.info("Flywheel vs vanilla shafts under the vertex lens: {}% of pixels differ", "%.2f".format(differing * 100))
			assertTrue(differing < 0.01) { "Flywheel's shafts aren't bent like vanilla's: ${differing * 100}% of pixels differ" }
		}
	}

	/**
	 * For a look at how the lens fits a shader pack's rendering (run with -PwithIris): shadows cast by walls near the
	 * hole under a low sun, sky and clouds round it, colours; the same views without the hole. Nothing asserted.
	 */
	@ClientGameTest
	fun ClientGameTestContext.packScene() {
		if (!ClientTestInput.selected("packScene")) return
		runOnClient { minecraft ->
			minecraft.options.renderDistance().set(8)
			minecraft.options.hideGui = true
			SpacetimeDebugRenderer.hidden = true
			minecraft.options.bobView().set(false)
		}
		withTestWorld {
			waitTicks(60)
			for (command in listOf(
				"gamemode spectator @a", "difficulty peaceful", "gamerule doMobSpawning false", "kill @e[type=!player]",
				"time set 2000", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "weather clear",
				"gamerule relativityBlackHoleGrowth false",
			)) command(command)
			server.runOnServer { minecraft ->
				val level = minecraft.overworld()
				for (x in -32..32) for (z in -32..32) {
					val block = if (Math.floorMod(Math.floorDiv(x, 2) + Math.floorDiv(z, 2), 2) == 0) Blocks.WHITE_CONCRETE else Blocks.BLUE_CONCRETE
					level.setBlock(BlockPos(x, -61, z), block.defaultBlockState(), 2)
				}
				// Walls and pillars that throw shadows across the floor round the hole.
				for (z in -10..10) for (y in -60..-55) level.setBlock(BlockPos(8, y, z), Blocks.RED_CONCRETE.defaultBlockState(), 2)
				for (x in -12..12 step 6) for (y in -60..-52) level.setBlock(BlockPos(x, y, -8), Blocks.OAK_LOG.defaultBlockState(), 2)
			}
			command("tick freeze")
			for ((pose, at) in listOf("side" to "-14.5 -54 0.5 -90 5", "up" to "-10.5 -57 0.5 -90 -30")) {
				for (withHole in listOf(false, true)) {
					command("relativity bh clear")
					if (withHole) command("relativity bh create 0.5 -54 0.5 2 0")
					command("tp @a $at")
					runOnClient { it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO }
					waitTicks(40)
					settle()
					takeScreenshot("pack_${pose}_" + if (withHole) "hole" else "plain")
					if (withHole) {
						runOnClient { BlackHoleLens.marchDebug = true }
						waitTicks(5)
						takeScreenshot("pack_${pose}_outcome")
						runOnClient { BlackHoleLens.marchDebug = false }
					}
				}
			}
			runOnClient {
				it.options.hideGui = false
				SpacetimeDebugRenderer.hidden = false
			}
		}
	}

	/** Debug look at the Kerr vertex lens: where the path table places vertices, and where it falls back. */
	@ClientGameTest
	fun ClientGameTestContext.kerrVertex() {
		if (!ClientTestInput.selected("kerrVertex")) return
		runOnClient { minecraft ->
			minecraft.options.renderDistance().set(6)
			minecraft.options.hideGui = true
			SpacetimeDebugRenderer.hidden = true
			minecraft.options.bobView().set(false)
		}
		withTestWorld {
			waitTicks(60)
			for (command in listOf(
				"gamemode spectator @a", "difficulty peaceful", "gamerule doMobSpawning false", "kill @e[type=!player]",
				"time set noon", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "weather clear",
				"gamerule relativityBlackHoleGrowth false",
			)) command(command)
			server.runOnServer { minecraft ->
				val level = minecraft.overworld()
				for (x in -80..48) for (z in -48..48) {
					val block = if (Math.floorMod(Math.floorDiv(x, 2) + Math.floorDiv(z, 2), 2) == 0) Blocks.WHITE_CONCRETE else Blocks.BLUE_CONCRETE
					level.setBlock(BlockPos(x, -61, z), block.defaultBlockState(), 2)
				}
			}
			command("relativity bh create 0.5 -54 0.5 2 0.9")
			command("tick freeze")
			command("tp @a -14.5 -54 0.5 -90 10")
			runOnClient { it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO }
			waitTicks(40)
			settle()
			val kerrShot = takeScreenshot("kerr_vertex")
			// Against the CPU trace of the same view (fog-free range only: past it colours fade into the fog).
			run {
				val columns = 160
				val rows = computeOnClient { columns * it.window.height / it.window.width }
				val reference = Array(rows) { arrayOfNulls<LensReference.Kind>(columns) }
				for (first in 0 until rows step 8) {
					val part = computeOnClient { minecraft ->
						LensReference.trace(minecraft, SpacetimeField.blackHoles(minecraft.level!!).single(), columns, rows, 200.0, 85.0, first until minOf(first + 8, rows))
					}
					for (row in first until minOf(first + 8, rows)) reference[row] = part[row]
				}
				val wrong = LensReference.compare(reference, kerrShot)
				CreateRelativity.LOGGER.info("Kerr vertex lens vs reference: {}% of pixels in the wrong class", "%.1f".format(wrong * 100))
			}
			runOnClient { BlackHoleLens.profileFlags = 8 }
			waitTicks(10)
			takeScreenshot("kerr_vertex_uncut")
			runOnClient { BlackHoleLens.profileFlags = 0 }
			// (The post pass's tracing off, so what the vertex lens leaves out shows as sky.)
			for ((mode, label) in listOf(1 to "placed", 2 to "inbound", 3 to "outbound")) {
				runOnClient {
					VertexLens.kerrDebug = mode
					BlackHoleLens.unseen = BlackHoleLens.Unseen.NONE
				}
				waitTicks(10)
				takeScreenshot("kerr_vertex_$label")
			}
			runOnClient {
				VertexLens.kerrDebug = 0
				BlackHoleLens.unseen = BlackHoleLens.Unseen.VOXELS
				VertexLens.kerrOff = true
			}
			waitTicks(10)
			takeScreenshot("kerr_vertex_schwarzschild")
			runOnClient { VertexLens.kerrOff = false }
			// The vertex lens alone (no grid trace for the sky), spinning and not: holes it leaves show as sky.
			runOnClient { BlackHoleLens.unseen = BlackHoleLens.Unseen.NONE }
			waitTicks(10)
			takeScreenshot("kerr_vertex_nogrid")
			// The vertex lens's own solve on the GPU for the floor (y -60) round the hole at (0.5, 0.5): the same map as
			// KerrVertexLensTest's (o found, s Schwarzschild, ? hidden, . no Kerr solve).
			val map = computeOnClient { minecraft ->
				val camera = minecraft.gameRenderer.mainCamera
				val view = org.joml.Quaternionf(camera.rotation()).conjugate()
				val rows = (96 downTo -96 step 6).toList()
				val columns = (-14..96 step 4).toList()
				val points = ArrayList<FloatArray>()
				for (gy in rows) for (gx in columns) {
					val v = view.transform(org.joml.Vector3f((gx - camera.position.x).toFloat(), (-60 - camera.position.y).toFloat(), (gy - camera.position.z).toFloat()))
					points += floatArrayOf(v.x, v.y, v.z)
				}
				val results = KerrSolveCheck.run(points, VertexLens.lastProjection)
				rows.indices.joinToString("\n") { r ->
					columns.indices.joinToString("") { c ->
						when (results.getOrNull(r * columns.size + c)?.get(0)?.toInt()) { 1 -> "o"; 0 -> "s"; -1 -> "?"; 2 -> "."; else -> " " }
					}
				}
			}
			CreateRelativity.LOGGER.info("Kerr solve on the GPU, floor round the hole:\n{}", map)
			// The GPU's path table and its parameters, for KerrVertexLensTest to check against its CPU mirror.
			runOnClient {
				val data = net.kernelpanicsoft.relativity.client.lens.KerrPaths.readBack()
				val out = java.io.DataOutputStream(java.io.BufferedOutputStream(Files.newOutputStream(Path.of("kerr_paths_dump.bin"))))
				out.use {
					it.writeInt(net.kernelpanicsoft.relativity.client.lens.KerrPaths.width)
					it.writeInt(net.kernelpanicsoft.relativity.client.lens.KerrPaths.height)
					for (v in net.kernelpanicsoft.relativity.client.lens.KerrPaths.lastDraw) it.writeFloat(v)
					val m = net.kernelpanicsoft.relativity.client.lens.KerrPaths.lastViewToGame
					for (c in 0..2) for (r in 0..2) it.writeFloat(m.get(c, r))
					for (v in data) it.writeFloat(v)
				}
			}
			runOnClient { VertexLens.kerrDebug = 4 }
			waitTicks(10)
			takeScreenshot("kerr_vertex_nogrid_fallback")
			runOnClient { VertexLens.kerrDebug = 0 }
			runOnClient { VertexLens.kerrOff = true }
			waitTicks(10)
			takeScreenshot("kerr_vertex_nogrid_schwarzschild")
			runOnClient {
				VertexLens.kerrOff = false
				BlackHoleLens.unseen = BlackHoleLens.Unseen.VOXELS
			}
			// From farther off, where the edge of the path table's reach (and the switch to the Schwarzschild image)
			// falls on screen.
			command("tp @a -60.5 -50 0.5 -90 5")
			runOnClient { it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO }
			waitTicks(40)
			settle()
			takeScreenshot("kerr_vertex_far")
			runOnClient {
				it.options.hideGui = false
				SpacetimeDebugRenderer.hidden = false
			}
		}
	}

	/**
	 * A user's scene: r_s 10 at the edge of an ocean, the camera 1.4 r_s from it. Screenshots for a look (the lens, how
	 * its sky pixels' paths end, the CPU reference's picture); nothing asserted.
	 */
	@ClientGameTest
	fun ClientGameTestContext.oceanScene() {
		if (!ClientTestInput.selected("oceanScene")) return
		runOnClient { minecraft ->
			minecraft.options.renderDistance().set(12)
			minecraft.options.hideGui = true
			SpacetimeDebugRenderer.hidden = true
			minecraft.options.bobView().set(false)
		}
		withTestWorld(seed = "-3745315768298803473") {
			waitTicks(60)
			for (command in listOf(
				"gamemode spectator @a", "difficulty peaceful", "gamerule doMobSpawning false", "kill @e[type=!player]",
				"time set noon", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "weather clear",
				"gamerule relativityBlackHoleGrowth false",
			)) command(command)
			command("relativity bh create -15.63 82.64 -146.02 10.03 0")
			command("tick freeze")
			for ((pose, at) in listOf("pose" to "-2.38 81.47 -149.14 -135 26", "save" to "-2.38 81.47 -149.14 -135 5.25")) {
				command("tp @a $at")
				runOnClient { it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO }
				waitTicks(40)
				settle()
				val vertexShot = takeScreenshot("ocean_$pose")
				runOnClient { BlackHoleLens.marchDebug = true }
				waitTicks(10)
				takeScreenshot("ocean_${pose}_outcome")
				runOnClient { BlackHoleLens.marchDebug = false }
				// The CPU reference picture of the same view (coarse grid, map colours).
				val columns = 240
				val rows = computeOnClient { columns * it.window.height / it.window.width }
				val picture = IntArray(columns * rows)
				for (first in 0 until rows step 4) {
					val part = computeOnClient { minecraft ->
						LensReference.picture(minecraft, SpacetimeField.blackHoles(minecraft.level!!).single(), columns, rows, 400.0, first until minOf(first + 4, rows))
					}
					for (row in first until minOf(first + 4, rows)) System.arraycopy(part, row * columns, picture, row * columns, columns)
				}
				NativeImage(columns, rows, false).use { image ->
					for (row in 0 until rows) for (column in 0 until columns) {
						val argb = picture[row * columns + column]
						image.setPixelRGBA(column, row, (argb and 0xFF00FF00.toInt()) or ((argb shr 16) and 0xFF) or ((argb and 0xFF) shl 16))
					}
					image.writeToFile(vertexShot.resolveSibling("ocean_${pose}_reference_picture.png"))
				}
			}
			runOnClient {
				it.options.hideGui = false
				SpacetimeDebugRenderer.hidden = false
			}
		}
	}

	/**
	 * GPU time of the post pass fullscreen (the display's resolution), split by stage: the pipeline's fixed cost (tables
	 * skipped, frame passed through), the tables (scene and Kerr), the lens pass; and for comparison marching every sky
	 * pixel. Logged, not asserted (it's for profiling; clocks vary).
	 */
	@ClientGameTest
	fun ClientGameTestContext.performance() {
		if (!ClientTestInput.selected("performance")) return
		runOnClient { minecraft ->
			minecraft.options.renderDistance().set(12)
			minecraft.options.hideGui = true
			SpacetimeDebugRenderer.hidden = true
			minecraft.options.bobView().set(false)
		}
		withTestWorld {
			waitTicks(60)
			for (command in listOf(
				"gamemode spectator @a", "difficulty peaceful", "gamerule doMobSpawning false", "kill @e[type=!player]",
				"time set noon", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "weather clear",
				"gamerule relativityBlackHoleGrowth false",
			)) command(command)
			server.runOnServer { minecraft ->
				val level = minecraft.overworld()
				val random = java.util.Random(11)
				val colours = listOf(Blocks.WHITE_CONCRETE, Blocks.BLUE_CONCRETE, Blocks.RED_CONCRETE)
				for (x in -48..48) for (z in -48..48) for (y in -60..-30) {
					if ((x * x + (y + 54) * (y + 54) + z * z) < 400 || random.nextFloat() > 0.03f) continue
					level.setBlock(BlockPos(x, y, z), colours[random.nextInt(3)].defaultBlockState(), 2)
				}
			}
			command("tick freeze")
			val size = computeOnClient { "${it.window.width}x${it.window.height}" }
			val lines = mutableListOf<String>()
			for ((name, rs, spin, at) in listOf(
				listOf("small", 2.0, 0.0, "-14.5 -54 0.5 -90 5"),
				listOf("small_spin", 2.0, 0.9, "-14.5 -54 0.5 -90 5"),
				listOf("large", 6.0, 0.0, "-20.5 -54 0.5 -90 5"),
				listOf("large_spin", 6.0, 0.9, "-20.5 -54 0.5 -90 5"),
			)) {
				command("relativity bh clear")
				command("relativity bh create 0.5 -54 0.5 $rs $spin")
				command("tp @a $at")
				runOnClient { it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO }
				waitTicks(40)
				settle()
				fun time(setup: () -> Unit): Double {
					runOnClient {
						BlackHoleLens.rawFrame = false
						BlackHoleLens.skipTables = false
						BlackHoleLens.marchEveryPixel = false
						BlackHoleLens.profileFlags = 0
						setup()
					}
					return measure(BlackHoleLens.KerrMode.TABLE)
				}
				val fixed = time { BlackHoleLens.rawFrame = true; BlackHoleLens.skipTables = true }
				val tables = time { BlackHoleLens.rawFrame = true }
				val lens = time {}
				val full = time { BlackHoleLens.marchEveryPixel = true }
				// The march's parts (every pixel marched): without geometry, Euler bending, neither.
				val parts = listOf(2, 4, 6).map { flags -> time { BlackHoleLens.marchEveryPixel = true; BlackHoleLens.profileFlags = flags } }
				runOnClient {
					BlackHoleLens.profileFlags = 0
					BlackHoleLens.marchStepsDebug = true
				}
				waitTicks(5)
				val stepsShot = takeScreenshot("performance_${name}_steps")
				runOnClient { BlackHoleLens.marchStepsDebug = false }
				val meanSteps = Files.newInputStream(stepsShot).use { NativeImage.read(it) }.use { image ->
					var sum = 0.0
					for (y in 0 until image.height) for (x in 0 until image.width) sum += (image.getPixelRGBA(x, y) and 0xFF) / 255.0 * 64
					sum / (image.width.toLong() * image.height)
				}
				takeScreenshot("performance_$name")
				runOnClient { BlackHoleLens.remarchDebug = true }
				waitTicks(5)
				val shot = takeScreenshot("performance_${name}_remarched")
				runOnClient { BlackHoleLens.remarchDebug = false }
				val sceneRemarched = fraction(shot) { r, _, _ -> r > 128 }
				val kerrRemarched = fraction(shot) { _, _, b -> b > 128 }
				lines += ("%s: fixed %.2f, tables %.2f, lens pass %.2f (total %.2f; re-marched %.1f%% scene, %.1f%% Kerr);" +
					" march every pixel %.2f (%.1f steps a pixel; without geometry %.2f, Euler %.2f, neither %.2f)").format(
					name, fixed, tables - fixed, lens - tables, lens, sceneRemarched * 100, kerrRemarched * 100,
					full, meanSteps, parts[0], parts[1], parts[2],
				)
			}
			runOnClient {
				BlackHoleLens.rawFrame = false
				BlackHoleLens.skipTables = false
				BlackHoleLens.marchEveryPixel = false
				BlackHoleLens.timeGpu = false
				it.options.hideGui = false
				SpacetimeDebugRenderer.hidden = false
			}
			CreateRelativity.LOGGER.info("Lens GPU ms at {}:\n{}", size, lines.joinToString("\n"))
		}
	}

	/**
	 * The geometry the lens traces where the frame can't show (LensVoxels) is every block's full render: block models
	 * of any shape, cutout plants, a block entity's renderer (a chest), and Flywheel-drawn Create shafts and cogs.
	 * Each gets quads in the grid; screenshots of the hole in front of them show how they come out bent.
	 */
	@ClientGameTest
	fun ClientGameTestContext.modelsAroundHole() {
		if (!ClientTestInput.selected("modelsAroundHole")) return
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
			takeScreenshot("models")
			runOnClient {
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
	 * the lens, with each source for what the sky's bent paths meet (voxels, the default; nothing). Logs accuracy and
	 * the post pass's cost (medians over 20 frames); adds the default's misses to [failures].
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
		// The lens, with and without the geometry round the hole for the sky's bent paths.
		val modes = listOf("lens" to BlackHoleLens.Unseen.VOXELS, "lens_none" to BlackHoleLens.Unseen.NONE)
		for ((mode, unseen) in modes) {
			runOnClient {
				BlackHoleLens.unseen = unseen
				BlackHoleLens.timeGpu = true
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
		runOnClient { BlackHoleLens.unseen = BlackHoleLens.Unseen.VOXELS }
		// Within 4%, or (where the reference's classes are rough, e.g. shaded block sides) a point of the lens without
		// the geometry.
		val wrong = wrongBy.getValue("lens")
		if (wrong > maxOf(0.04, wrongBy.getValue("lens_none") + 0.01)) failures += "$name: ${wrong * 100}% of pixels in the wrong class"
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
	private fun ClientGameTestContext.withTestWorld(seed: String? = null, block: TestSingleplayerContext.() -> Unit) {
		runOnClient { ClientTestInput.lock(it) }
		waitTicks(20) // the window resizes
		// Superflat (seed 1), or the default world type with a given seed.
		val builder = worldBuilder()
		if (seed != null) builder.setUseConsistentSettings(false).adjustSettings { it.seed = seed }
		val world = builder.create()
		val save = world.saveDirectory
		try {
			world.block()
		} finally {
			world.close()
			runOnClient { ClientTestInput.unlock(it) }
			if (save.parent?.fileName?.toString() == "saves") save.toFile().deleteRecursively()
		}
	}

	/** Waits (up to 30 s) until every chunk section in view is drawn and the lens's grids are in, then a second more. */
	private fun ClientGameTestContext.settle() {
		for (i in 0 until 600) {
			if (computeOnClient { it.levelRenderer.hasRenderedAllSections() && LensVoxels.ready && LensVoxels.behind.ready }) break
			waitTick()
		}
		waitTicks(20)
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
