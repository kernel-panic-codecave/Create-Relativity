package net.kernelpanicsoft.relativity.gametest

import com.mojang.blaze3d.platform.NativeImage
import net.kernelpanicsoft.archie.gametest.ClientGameTest
import net.kernelpanicsoft.archie.gametest.ClientGameTestContext
import net.kernelpanicsoft.archie.gametest.TestSingleplayerContext
import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.client.SpacetimeDebugRenderer
import net.kernelpanicsoft.relativity.client.lens.BlackHoleLens
import net.kernelpanicsoft.relativity.client.lens.KerrImageMap
import net.kernelpanicsoft.relativity.client.lens.KerrSlots
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

	/**
	 * Close to a large spinning hole (r_s 6, spin 0.9, the camera 3.5 r_s out on a plain floor): the vertex lens alone
	 * (the post pass cut to a copy) and the whole lens, and a map of which floor corners the Kerr image map places,
	 * hides, or leaves to the Schwarzschild image.
	 */
	@ClientGameTest
	fun ClientGameTestContext.kerrClose() {
		if (!ClientTestInput.selected("kerrClose")) return
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
			command("relativity bh create 0.5 -54 0.5 6 0.9")
			command("tick freeze")
			command("tp @a -20.5 -54 0.5 -90 5")
			runOnClient { it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO }
			waitTicks(40)
			settle()
			takeScreenshot("kerr_close")
			runOnClient { BlackHoleLens.rawFrame = true }
			waitTicks(10)
			takeScreenshot("kerr_close_vertex")
			runOnClient { VertexLens.kerrOff = true }
			waitTicks(10)
			takeScreenshot("kerr_close_vertex_schwarzschild")
			runOnClient { VertexLens.kerrOff = false }
			runOnClient { BlackHoleLens.rawFrame = false }
			// Floor corners (top at y -60) round the camera: o placed, ? hidden, s Schwarzschild, . nothing to bend.
			val map = computeOnClient { minecraft ->
				val camera = minecraft.gameRenderer.mainCamera
				val view = org.joml.Quaternionf(camera.rotation()).conjugate()
				val rows = (30 downTo -30 step 2).toList()
				val columns = (-40..40 step 2).toList()
				val points = ArrayList<FloatArray>()
				for (gz in rows) for (gx in columns) {
					val v = view.transform(org.joml.Vector3f((gx - camera.position.x).toFloat(), (-60 - camera.position.y).toFloat(), (gz - camera.position.z).toFloat()))
					points += floatArrayOf(v.x, v.y, v.z)
				}
				val results = points.chunked(1000).flatMap { KerrSolveCheck.run(it, VertexLens.lastProjection) }
				"x -40..40 across, z 30 (top) .. -30; camera at x -20.5, hole at x 0.5\n" + rows.indices.joinToString("\n") { r ->
					columns.indices.joinToString("") { c ->
						val res = results[r * columns.size + c]
						if (res[3] > 99f && res[0].toInt() == 1) "x" // placed, but behind the camera or past the far plane
						else when (res[0].toInt()) { 1 -> "o"; 0 -> "s"; -1 -> "?"; 2 -> "."; else -> " " }
					}
				}
			}
			CreateRelativity.LOGGER.info("Kerr close, floor corners:\n{}", map)
			// Every image map texel without an image (debug 5: no camera-side fallback), by where its point is.
			runOnClient { VertexLens.kerrDebug = 5; KerrImageMap.invalidate() }
			waitTicks(20)
			val failures = computeOnClient { minecraft ->
				val data = KerrImageMap.readBack()
				val hole = SpacetimeField.blackHoles(minecraft.level!!).single()
				val camera = minecraft.gameRenderer.mainCamera.position
				val rs = hole.schwarzschildRadius
				val a = hole.spin * rs / 2
				val horizon = rs / 2 + kotlin.math.sqrt(maxOf(rs * rs / 4 - a * a, 0.0))
				val rIn = 1.05 * horizon
				val rOut = maxOf(80 * rs, camera.distanceTo(hole.center) + 1.5 * minecraft.gameRenderer.renderDistance)
				val counts = java.util.TreeMap<String, Int>()
				var total = 0
				for (k in 0 until KerrImageMap.DEPTH) for (j in 0 until KerrImageMap.HEIGHT) for (i in 0 until KerrImageMap.WIDTH) {
					val index = ((k * KerrImageMap.HEIGHT + j) * KerrImageMap.WIDTH + i) * 4
					if (data[index + 3] > 0.5f) continue
					val azimuth = ((i + 0.5) / KerrImageMap.WIDTH - 0.5) * 2 * Math.PI
					val cosine = j.toDouble() / (KerrImageMap.HEIGHT - 1) * 2 - 1
					val sine = kotlin.math.sqrt(maxOf(1 - cosine * cosine, 0.0))
					val radius = rIn * Math.pow(rOut / rIn, k.toDouble() / (KerrImageMap.DEPTH - 1))
					val point = hole.center.add(radius * sine * kotlin.math.cos(azimuth), radius * cosine, radius * sine * kotlin.math.sin(azimuth))
					val toCamera = camera.subtract(hole.center).normalize()
					val along = point.subtract(hole.center).dot(toCamera)
					val off = point.subtract(hole.center).subtract(toCamera.scale(along)).length()
					if (along <= 0) continue // the far side: the ring and behind the hole, failures expected
					val r = radius / rs
					val band = when { r < 1.5 -> "r < 1.5 rs"; r < 3 -> "r 1.5-3 rs"; r < 6 -> "r 3-6 rs"; r < 20 -> "r 6-20 rs"; else -> "r > 20 rs" }
					val camDist = point.distanceTo(camera)
					val inView = run {
						val dir = point.subtract(camera).normalize()
						if (dir.dot(net.minecraft.world.phys.Vec3.directionFromRotation(minecraft.gameRenderer.mainCamera.xRot, minecraft.gameRenderer.mainCamera.yRot)) > 0.5) "ahead" else "aside/behind"
					}
					val height = if (cosine > 0.3) "above" else if (cosine < -0.3) "below" else "level"
					counts.merge("camera side, $band, $inView, $height", 1, Int::plus)
					total += 1
				}
				"$total of ${KerrImageMap.WIDTH * KerrImageMap.HEIGHT * KerrImageMap.DEPTH} texels without an image:\n" + counts.entries.joinToString("\n") { "  ${it.key}: ${it.value}" }
			}
			runOnClient { VertexLens.kerrDebug = 0; KerrImageMap.invalidate() }
			CreateRelativity.LOGGER.info("Kerr close, image map failures: {}", failures)
			// The same corners solved directly: o found, s off the table, ? failed, F at a fold, A near the axis,
			// . unbent, - no Schwarzschild image.
			val direct = computeOnClient { minecraft ->
				val camera = minecraft.gameRenderer.mainCamera
				val view = org.joml.Quaternionf(camera.rotation()).conjugate()
				val rows = (30 downTo -30 step 2).toList()
				val columns = (-40..40 step 2).toList()
				val points = ArrayList<FloatArray>()
				for (gz in rows) for (gx in columns) {
					val v = view.transform(org.joml.Vector3f((gx - camera.position.x).toFloat(), (-60 - camera.position.y).toFloat(), (gz - camera.position.z).toFloat()))
					points += floatArrayOf(v.x, v.y, v.z)
				}
				val results = points.chunked(1000).flatMap { KerrSolveCheck.run(it, VertexLens.lastProjection, direct = true) }
				rows.indices.joinToString("\n") { r ->
					columns.indices.joinToString("") { c ->
						when (results[r * columns.size + c][0].toInt()) { 1 -> "o"; 0 -> "s"; -1 -> "?"; 5 -> "F"; 6 -> "A"; 2 -> "."; -2 -> "-"; else -> " " }
					}
				}
			}
			CreateRelativity.LOGGER.info("Kerr close, floor corners solved directly:\n{}", direct)
			// The image map's alpha there: o 1, + partly (a hidden texel round it), 0 none (filtered/nearest).
			val alpha = computeOnClient { minecraft ->
				val camera = minecraft.gameRenderer.mainCamera
				val view = org.joml.Quaternionf(camera.rotation()).conjugate()
				val rows = (30 downTo -30 step 2).toList()
				val columns = (-40..40 step 2).toList()
				val points = ArrayList<FloatArray>()
				for (gz in rows) for (gx in columns) {
					val v = view.transform(org.joml.Vector3f((gx - camera.position.x).toFloat(), (-60 - camera.position.y).toFloat(), (gz - camera.position.z).toFloat()))
					points += floatArrayOf(v.x, v.y, v.z)
				}
				val results = points.chunked(1000).flatMap { KerrSolveCheck.run(it, VertexLens.lastProjection, mapAlpha = true) }
				rows.indices.joinToString("\n") { r ->
					columns.indices.joinToString("") { c ->
						val res = results[r * columns.size + c]
						if (res[0] >= 0.999f) "o" else if (res[1] < 0.5f) "0" else "+"
					}
				}
			}
			CreateRelativity.LOGGER.info("Kerr close, image map alpha at the floor corners:\n{}", alpha)
			// Where the map puts each corner against where a direct solve does: . within 2 px, + within 20, X farther,
			// blank where either has no image.
			val gap = computeOnClient { minecraft ->
				val camera = minecraft.gameRenderer.mainCamera
				val view = org.joml.Quaternionf(camera.rotation()).conjugate()
				val rows = (30 downTo -30 step 2).toList()
				val columns = (-40..40 step 2).toList()
				val points = ArrayList<FloatArray>()
				for (gz in rows) for (gx in columns) {
					val v = view.transform(org.joml.Vector3f((gx - camera.position.x).toFloat(), (-60 - camera.position.y).toFloat(), (gz - camera.position.z).toFloat()))
					points += floatArrayOf(v.x, v.y, v.z)
				}
				val mapped = points.chunked(1000).flatMap { KerrSolveCheck.run(it, VertexLens.lastProjection) }
				val solved = points.chunked(1000).flatMap { KerrSolveCheck.run(it, VertexLens.lastProjection, direct = true) }
				val width = minecraft.window.width
				val height = minecraft.window.height
				rows.indices.joinToString("\n") { r ->
					columns.indices.joinToString("") { c ->
						val m = mapped[r * columns.size + c]
						val d = solved[r * columns.size + c]
						if (d[0].toInt() != 1 || m[3] > 99f || m[0].toInt() % 10 != 1 && m[0].toInt() != -9) " "
						else {
							val px = kotlin.math.hypot((m[1] - d[1]) * width / 2.0, (m[2] - d[2]) * height / 2.0)
							if (px < 2.0) "." else if (px < 20.0) "+" else "X"
						}
					}
				}
			}
			CreateRelativity.LOGGER.info("Kerr close, map vs direct solve on screen:\n{}", gap)
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
			// While the camera moves, the Kerr tables catch up over a few frames: drift it for a second, then compare
			// before they settle.
			run {
				val start = computeOnClient { it.player!!.position() }
				for (tick in 0 until 20) {
					runOnClient { it.player!!.setPos(start.x + 0.04 * tick, start.y + 0.02 * tick, start.z) }
					waitTick()
				}
				val movingShot = takeScreenshot("kerr_vertex_moving")
				val columns = 160
				val rows = computeOnClient { columns * it.window.height / it.window.width }
				val reference = Array(rows) { arrayOfNulls<LensReference.Kind>(columns) }
				for (first in 0 until rows step 8) {
					val part = computeOnClient { minecraft ->
						LensReference.trace(minecraft, SpacetimeField.blackHoles(minecraft.level!!).single(), columns, rows, 200.0, 85.0, first until minOf(first + 8, rows))
					}
					for (row in first until minOf(first + 8, rows)) reference[row] = part[row]
				}
				CreateRelativity.LOGGER.info("Kerr vertex lens while moving vs reference: {}% of pixels in the wrong class", "%.1f".format(LensReference.compare(reference, movingShot) * 100))
				runOnClient { it.player!!.setPos(start.x, start.y, start.z) }
				waitTicks(20)
			}
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
			// Which pixels the world drew (grey) and which the post pass traced (yellow; blue coloured from the drawing).
			runOnClient { BlackHoleLens.marchDebug = true }
			waitTicks(10)
			takeScreenshot("kerr_vertex_sources")
			runOnClient { BlackHoleLens.profileFlags = 32 }
			waitTicks(10)
			takeScreenshot("kerr_vertex_sources_distance") // traced hits by fog distance: black near, yellow at 128
			runOnClient { BlackHoleLens.profileFlags = 0; BlackHoleLens.marchDebug = false }
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
						when (results.getOrNull(r * columns.size + c)?.get(0)?.toInt()?.let { if (it % 10 == 1) 1 else it }) { 1 -> "o"; 0 -> "s"; -1 -> "?"; 2 -> "."; else -> " " }
					}
				}
			}
			CreateRelativity.LOGGER.info("Kerr solve on the GPU, floor round the hole:\n{}", map)
			// No drawn floor quad (top at y -60, every corner placed) stretched across the view: a vertex solved to the
			// wrong image (between two merging ones at a fold) pulls its quads hundreds of pixels past where the lens's
			// magnification takes its neighbours (healthy quads near the ring here stay under about 210 px; near the
			// camera, quads are big unbent too, so those are measured against their unbent size).
			val stretched = computeOnClient { minecraft ->
				val camera = minecraft.gameRenderer.mainCamera
				val view = org.joml.Quaternionf(camera.rotation()).conjugate()
				val span = -130..130
				val points = ArrayList<FloatArray>()
				for (z in span) for (x in span) {
					val v = view.transform(org.joml.Vector3f((x - camera.position.x).toFloat(), (-60 - camera.position.y).toFloat(), (z - camera.position.z).toFloat()))
					points += floatArrayOf(v.x, v.y, v.z)
				}
				val results = points.chunked(1000).flatMap { KerrSolveCheck.run(it, VertexLens.lastProjection) }
				val width = minecraft.window.width.toDouble()
				val height = minecraft.window.height.toDouble()
				val n = span.count()
				fun at(i: Int, j: Int) = results[j * n + i]
				val found = ArrayList<Pair<Double, String>>()
				for (j in 0 until n - 1) for (i in 0 until n - 1) {
					val corners = listOf(at(i, j), at(i + 1, j), at(i + 1, j + 1), at(i, j + 1))
					// Placed by the Kerr solve, and on or near the screen (a corner level with the camera projects
					// anywhere; clipping takes care of it).
					if (corners.any { it[0].toInt() % 10 != 1 || it[3] > 99f || kotlin.math.abs(it[1]) > 3f || kotlin.math.abs(it[2]) > 3f }) continue
					val xs = corners.map { (it[1] + 1.0) / 2.0 * width }
					val ys = corners.map { (1.0 - it[2]) / 2.0 * height }
					val extent = maxOf(xs.max() - xs.min(), ys.max() - ys.min())
					val unbent = listOf(i to j, i + 1 to j, i + 1 to j + 1, i to j + 1).map { (a, b) ->
						val v = points[b * n + a]
						val clip = org.joml.Vector4f(v[0], v[1], v[2], 1f).mul(VertexLens.lastProjection)
						doubleArrayOf(clip.x / clip.w / 2.0 * width, -clip.y / clip.w / 2.0 * height)
					}
					val unbentExtent = maxOf(unbent.maxOf { it[0] } - unbent.minOf { it[0] }, unbent.maxOf { it[1] } - unbent.minOf { it[1] })
					if (extent > 300.0 + 2.0 * unbentExtent) found += extent to "block (${span.first + i}, ${span.first + j}) spans ${extent.toInt()} px (${unbentExtent.toInt()} unbent)"
				}
				found.sortedByDescending { it.first }.take(5).map { it.second }
			}
			assertTrue(stretched.isEmpty()) { "Stretched floor quads: $stretched" }
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
	/**
	 * Several sources together (M10): two holes (one spinning), a positive and a negative weak-field mass and a warp
	 * bubble over a checker floor with red pillars, against the CPU trace through every source
	 * ([LensReference.traceField]); then the vertex lens alone (the post pass's tracing off), where any hole it leaves
	 * in the floor shows as sky.
	 */
	fun ClientGameTestContext.mixedSources() {
		if (!ClientTestInput.selected("mixedSources")) return
		runOnClient { minecraft ->
			minecraft.options.renderDistance().set(6)
			minecraft.options.hideGui = true
			SpacetimeDebugRenderer.hidden = true
			minecraft.options.bobView().set(false)
		}
		val allOthers = listOf(
			net.kernelpanicsoft.relativity.spacetime.FieldSource.Mass(org.joml.Vector3d(-8.5, -52.0, -14.5), 0.5, softening = 1.5),
			net.kernelpanicsoft.relativity.spacetime.FieldSource.Mass(org.joml.Vector3d(-8.5, -52.0, 14.5), -0.5, softening = 1.5),
			net.kernelpanicsoft.relativity.spacetime.FieldSource.Bubble(org.joml.Vector3d(-22.5, -53.0, 6.5), org.joml.Vector3d(0.0, 0.0, 1.0), 0.6, 4.0),
		)
		var others: List<net.kernelpanicsoft.relativity.spacetime.FieldSource> = allOthers
		val provider = net.kernelpanicsoft.relativity.spacetime.FieldSource.Provider { level ->
			if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) emptyList() else others
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
				for ((px, pz) in listOf(-14 to -6, -14 to 6, 6 to -10, 6 to 10, -30 to -3, -30 to 3)) {
					for (y in -60..-55) level.setBlock(BlockPos(px, y, pz), Blocks.RED_CONCRETE.defaultBlockState(), 2)
				}
			}
			command("relativity bh create 0.5 -54 -4.5 2 0.9")
			command("relativity bh create 0.5 -53 9.5 1.5 0")
			command("tick freeze")
			SpacetimeField.addProvider(provider)
			try {
				command("tp @a -44.5 -54 0.5 -90 8")
				runOnClient { it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO }
				waitTicks(40)
				settle()
				val shot = takeScreenshot("mixed_sources")
				val columns = 160
				val rows = computeOnClient { columns * it.window.height / it.window.width }
				val reference = Array(rows) { arrayOfNulls<LensReference.Kind>(columns) }
				for (first in 0 until rows step 8) {
					val part = computeOnClient { minecraft -> LensReference.traceField(minecraft, columns, rows, 200.0, 85.0, first until minOf(first + 8, rows)) }
					for (row in first until minOf(first + 8, rows)) reference[row] = part[row]
				}
				val wrong = LensReference.compare(reference, shot)
				CreateRelativity.LOGGER.info("Mixed sources vs reference: {}% of pixels in the wrong class", "%.1f".format(wrong * 100))
				// Floor corners (top at y -60): o placed, F hidden at a fold, ? hidden (no image found), s outside the map,
				// . nothing to bend; x placed but off screen behind.
				val floor = computeOnClient { minecraft ->
					val camera = minecraft.gameRenderer.mainCamera
					val view = org.joml.Quaternionf(camera.rotation()).conjugate()
					val rows = (40 downTo -40 step 2).toList()
					val columns = (-40..48 step 2).toList()
					val points = ArrayList<FloatArray>()
					for (gz in rows) for (gx in columns) {
						val v = view.transform(org.joml.Vector3f((gx - camera.position.x).toFloat(), (-60 - camera.position.y).toFloat(), (gz - camera.position.z).toFloat()))
						points += floatArrayOf(v.x, v.y, v.z)
					}
					val results = points.chunked(1000).flatMap { KerrSolveCheck.run(it, VertexLens.lastProjection) }
					"x -40..48 across, z 40 (top) .. -40; camera at x -44.5, holes at x 0.5 (z -4.5, 9.5)\n" + rows.indices.joinToString("\n") { r ->
						columns.indices.joinToString("") { c ->
							val res = results[r * columns.size + c]
							val found = Math.floorMod(res[0].toInt(), 10).let { if (it > 2) it - 10 else it }
							val fold = res[3] >= 1000f
							when {
								fold -> "F"
								found == 1 && res[3] == 100f -> "x"
								found == 1 -> "o"
								found == -1 -> "?"
								found == 0 -> "s"
								else -> "."
							}
						}
					}
				}
				CreateRelativity.LOGGER.info("Mixed sources, floor corners:\n{}", floor)
				val statuses = computeOnClient { _ ->
					val data = net.kernelpanicsoft.relativity.client.lens.KerrPaths.readBack()
					val counts = IntArray(5)
					// Layer 0 holds each texel's head: (r_min, status, ...).
					for (i in 0 until net.kernelpanicsoft.relativity.client.lens.KerrPaths.width * net.kernelpanicsoft.relativity.client.lens.KerrPaths.height) counts[(data[i * 4 + 1] / 8192f).toInt().coerceIn(0, 4)] += 1
					"unmarched ${counts[0]}, escaped ${counts[1]}, captured ${counts[2]}, out of steps ${counts[3]}"
				}
				CreateRelativity.LOGGER.info("Mixed sources, path table: {}", statuses)
				// The same corners solved directly from the first guess: o found, s off the table, ? failed, F at a fold, A
				// near the axis, . unbent, - no first guess.
				val direct = computeOnClient { minecraft ->
					val camera = minecraft.gameRenderer.mainCamera
					val view = org.joml.Quaternionf(camera.rotation()).conjugate()
					val points = ArrayList<FloatArray>()
					for (gz in 40 downTo -40 step 2) for (gx in -40..48 step 2) {
						val v = view.transform(org.joml.Vector3f((gx - camera.position.x).toFloat(), (-60 - camera.position.y).toFloat(), (gz - camera.position.z).toFloat()))
						points += floatArrayOf(v.x, v.y, v.z)
					}
					val results = points.chunked(1000).flatMap { KerrSolveCheck.run(it, VertexLens.lastProjection, direct = true) }
					results.chunked(45).joinToString("\n") { row ->
						row.joinToString("") { when (it[0].toInt()) { 1 -> "o"; 0 -> "s"; -1 -> "?"; 5 -> "F"; 6 -> "A"; 2 -> "."; -2 -> "-"; else -> " " } }
					}
				}
				CreateRelativity.LOGGER.info("Mixed sources, floor corners solved directly:\n{}", direct)
				runOnClient { BlackHoleLens.unseen = BlackHoleLens.Unseen.NONE }
				waitTicks(10)
				val vertexOnly = takeScreenshot("mixed_sources_vertex_only")
				CreateRelativity.LOGGER.info("Mixed sources, vertex lens alone vs reference: {}% of pixels in the wrong class", "%.1f".format(LensReference.compare(reference, vertexOnly) * 100))
				runOnClient { BlackHoleLens.unseen = BlackHoleLens.Unseen.VOXELS }
				// Which sources break the floor behind the holes (x 4 .. 48): the share of its corners left unplaced.
				fun unplacedBehind(): Double = computeOnClient { minecraft ->
					val camera = minecraft.gameRenderer.mainCamera
					val view = org.joml.Quaternionf(camera.rotation()).conjugate()
					val points = ArrayList<FloatArray>()
					for (gz in 30 downTo -30 step 2) for (gx in 4..48 step 2) {
						val v = view.transform(org.joml.Vector3f((gx - camera.position.x).toFloat(), (-60 - camera.position.y).toFloat(), (gz - camera.position.z).toFloat()))
						points += floatArrayOf(v.x, v.y, v.z)
					}
					val results = points.chunked(1000).flatMap { KerrSolveCheck.run(it, VertexLens.lastProjection) }
					results.count { Math.floorMod(it[0].toInt(), 10).let { f -> if (f > 2) f - 10 else f } == -1 }.toDouble() / results.size
				}
				val variants = listOf(
					"spinning hole alone" to (false to emptyList()),
					"both holes" to (true to emptyList()),
					"spinning hole and masses" to (false to allOthers.take(2)),
					"spinning hole and bubble" to (false to allOthers.drop(2)),
					"everything" to (true to allOthers),
				)
				for ((label, variant) in variants) {
					command("relativity bh clear")
					command("relativity bh create 0.5 -54 -4.5 2 0.9")
					if (variant.first) command("relativity bh create 0.5 -53 9.5 1.5 0")
					others = variant.second
					runOnClient { KerrImageMap.invalidate() }
					waitTicks(30)
					settle()
					CreateRelativity.LOGGER.info("Mixed sources, {}: {}% of the floor behind the holes unplaced", label, "%.1f".format(unplacedBehind() * 100))
					if (label == "spinning hole and masses" || label == "both holes") {
						// Ground truth for a few floor points behind: the view direction whose light (traced on the CPU through
						// every source) passes through each, from the straight line of sight; then the GPU's solve started
						// right there (code: 1 found, -1 failed, 5 fold; and how far from the truth it lands).
						val (eye, rotation) = computeOnClient { minecraft ->
							val camera = minecraft.gameRenderer.mainCamera
							camera.position to org.joml.Quaternionf(camera.rotation()).conjugate()
						}
						val sources = computeOnClient { minecraft ->
							val level = minecraft.level!!
							val at = org.joml.Vector3d(eye.x, eye.y, eye.z)
							SpacetimeField.blackHoles(level).map { hole ->
								val p = DoubleArray(3)
								net.kernelpanicsoft.relativity.spacetime.GameFrame.toPhysics(org.joml.Vector3d(hole.x, hole.y, hole.z).sub(at), p)
								net.kernelpanicsoft.relativity.physics.spacetime.field.KerrSource(p[0], p[1], p[2], hole.mass, hole.spin * hole.mass) as net.kernelpanicsoft.relativity.physics.spacetime.field.MetricSource
							} + SpacetimeField.fieldSources(level).map { it.toPhysics(at) }
						}
						val targets = listOf(10, 20, 30).flatMap { x -> listOf(-10, 0, 10).map { z -> net.minecraft.world.phys.Vec3(x.toDouble(), -60.0, z.toDouble()) } }
						val truths = targets.map { target -> LensReference.imageDirection(sources, eye, target) }
						val views = targets.map { t -> rotation.transform(org.joml.Vector3f((t.x - eye.x).toFloat(), (t.y - eye.y).toFloat(), (t.z - eye.z).toFloat())).let { floatArrayOf(it.x, it.y, it.z) } }
						val guesses = truths.map { d -> if (d == null) floatArrayOf(0f, 0f, -1f) else rotation.transform(org.joml.Vector3f(d.x.toFloat(), d.y.toFloat(), d.z.toFloat())).let { floatArrayOf(it.x, it.y, it.z) } }
						val solved = computeOnClient { KerrSolveCheck.run(views, VertexLens.lastProjection, guesses = guesses) }
						// The image map's thin-lens first guess (kerr_image_map.fsh's weakImage, on the CPU) and the solve from it.
						val lenses = computeOnClient { minecraft ->
							SpacetimeField.blackHoles(minecraft.level!!).map { doubleArrayOf(it.x - eye.x, it.y - eye.y, it.z - eye.z, it.schwarzschildRadius) } +
								SpacetimeField.fieldSources(minecraft.level!!).filterIsInstance<net.kernelpanicsoft.relativity.spacetime.FieldSource.Mass>()
									.map { doubleArrayOf(it.center.x() - eye.x, it.center.y() - eye.y, it.center.z() - eye.z, 2.0 * it.mass) }
						}
						val weak = targets.map { t ->
							val game = org.joml.Vector3d(t.x - eye.x, t.y - eye.y, t.z - eye.z)
							val distance = game.length()
							val straightDir = org.joml.Vector3d(game).normalize()
							val dir = org.joml.Vector3d(straightDir)
							repeat(8) {
								val shifted = org.joml.Vector3d(straightDir)
								for (l in lenses) {
									val along = l[0] * dir.x + l[1] * dir.y + l[2] * dir.z
									if (along <= 0 || along >= distance) continue
									val toward = org.joml.Vector3d(l[0], l[1], l[2]).sub(org.joml.Vector3d(dir).mul(along))
									val b = maxOf(toward.length(), 0.5 * kotlin.math.abs(l[3]) + 1e-3)
									shifted.sub(toward.div(b).mul(2.0 * l[3] / b * (distance - along) / distance))
								}
								dir.lerp(shifted.normalize(), 0.5).normalize()
							}
							net.minecraft.world.phys.Vec3(dir.x, dir.y, dir.z)
						}
						val weakSolved = computeOnClient {
							KerrSolveCheck.run(views, VertexLens.lastProjection, guesses = weak.map { d -> rotation.transform(org.joml.Vector3f(d.x.toFloat(), d.y.toFloat(), d.z.toFloat())).let { floatArrayOf(it.x, it.y, it.z) } })
						}
						val report = targets.indices.joinToString("\n") { i ->
							val truth = truths[i]
							val straight = targets[i].subtract(eye).normalize()
							if (truth == null) "  ${targets[i]}: no image found on the CPU"
							else "  ${targets[i]}: image %.3f rad from straight; GPU solve from it: %d, lands %.4f rad off, magnification %.1f; thin-lens guess %.3f rad off, solve from it %d".format(
								kotlin.math.acos(truth.dot(straight).coerceIn(-1.0, 1.0)), solved[i][0].toInt(), solved[i][1], solved[i][3],
								kotlin.math.acos(truth.dot(weak[i]).coerceIn(-1.0, 1.0)), weakSolved[i][0].toInt())
						}
						CreateRelativity.LOGGER.info("Mixed sources, {}, true images:\n{}", label, report)
					}
				}
			} finally {
				SpacetimeField.removeProvider(provider)
			}
		}
	}

	/**
	 * Where the vertex lens puts world points, against the light itself (#27): per scene, floor corners, a cloud of
	 * points round the hole and points just in front of the camera; each placed point's light, traced back on the CPU
	 * from the direction the GPU chose, must pass through it (error in pixels); each hidden one is searched for an image
	 * on the CPU (a sample of them): found means a hole in the picture.
	 */
	@ClientGameTest
	fun ClientGameTestContext.vertexAccuracy() {
		if (!ClientTestInput.selected("vertexAccuracy")) return
		runOnClient { minecraft ->
			minecraft.options.renderDistance().set(6)
			minecraft.options.hideGui = true
			SpacetimeDebugRenderer.hidden = true
			minecraft.options.bobView().set(false)
		}
		// (hole x y z, r_s, spin; camera x y z, yaw, pitch)
		val scenes = listOf(
			"spinning, from 7 r_s" to doubleArrayOf(0.5, -54.0, 0.5, 2.0, 0.9, -13.5, -54.0, 0.5, -90.0, 10.0),
			// Exactly on a baked slot (distance 1.5 x 1.09^18 r_s, height angle 1.5 rad): the blend is that slot alone.
			"spinning, on a slot" to doubleArrayOf(0.5, -54.0, 0.5, 2.0, 0.9, -13.615911902353254, -54.61897230525466, 0.5, -90.0, 10.0),
			"spinning, from 4 r_s" to doubleArrayOf(0.5, -48.0, 0.5, 6.0, 0.9, -23.5, -48.0, 0.5, -90.0, 15.0),
			"still, from 3 r_s" to doubleArrayOf(0.5, -50.0, 0.5, 4.0, 0.0, -11.5, -50.0, 0.5, -90.0, 20.0),
			// The performance test's large spinning hole among floating blocks (where giant slabs showed).
			"floating blocks, spinning, from 3.5 r_s" to doubleArrayOf(0.5, -54.0, 0.5, 6.0, 0.9, -20.5, -54.0, 0.5, -90.0, 5.0),
		)
		withTestWorld {
			waitTicks(60)
			for (command in listOf(
				"gamemode spectator @a", "difficulty peaceful", "gamerule doMobSpawning false", "kill @e[type=!player]",
				"time set noon", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "weather clear",
				"gamerule relativityBlackHoleGrowth false", "tick freeze",
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
			for ((label, s) in scenes) {
				command("relativity bh clear")
				command("relativity bh create ${s[0]} ${s[1]} ${s[2]} ${s[3]} ${s[4]}")
				command("tp @a ${s[5]} ${s[6]} ${s[7]} ${s[8]} ${s[9]}")
				runOnClient { it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO; KerrImageMap.invalidate() }
				waitTicks(40)
				settle()
				// A spinning hole: until the baked camera slots round the camera take over (KerrSlots).
				if (s[4] != 0.0) {
					runOnClient { KerrSlots.measureBakes = true; KerrSlots.bakeCosts.clear() }
					var waited = 0
					while (waited < 600 && !computeOnClient { net.kernelpanicsoft.relativity.client.lens.KerrSlots.blending }) { waitTicks(10); waited += 10 }
					CreateRelativity.LOGGER.info("Vertex accuracy, {}: blending baked slots: {} after {} ticks ({} baked)", label, computeOnClient { net.kernelpanicsoft.relativity.client.lens.KerrSlots.blending }, waited, computeOnClient { net.kernelpanicsoft.relativity.client.lens.KerrSlots.bakedSlots })
					val costs = computeOnClient { KerrSlots.bakeCosts.entries.joinToString("; ") { (phase, list) -> "%s: %d calls, median %.1f ms, max %.1f ms".format(phase, list.size, list.sorted()[list.size / 2], list.max()) } }
					runOnClient { KerrSlots.measureBakes = false }
					CreateRelativity.LOGGER.info("Vertex accuracy, {}: bake calls: {}", label, costs)
					waitTicks(10)
				}
				val shotName = "vertex_accuracy_${label.replace(Regex("[^a-z0-9]+"), "_")}"
				takeScreenshot(shotName)
				// The vertex lens alone (no post pass), with and without the pixel check: what it removes.
				runOnClient { BlackHoleLens.rawFrame = true; BlackHoleLens.skipTables = true; VertexLens.pixelCheck = false }
				waitTicks(5)
				val unchecked = takeScreenshot("${shotName}_vertex_unchecked")
				runOnClient { VertexLens.pixelCheck = true }
				waitTicks(5)
				val checked = takeScreenshot("${shotName}_vertex_checked")
				runOnClient { BlackHoleLens.rawFrame = false; BlackHoleLens.skipTables = false }
				val removed = Files.newInputStream(unchecked).use { NativeImage.read(it) }.use { a ->
					Files.newInputStream(checked).use { NativeImage.read(it) }.use { b ->
						var differ = 0L
						for (y in 0 until a.height) for (x in 0 until a.width) if (a.getPixelRGBA(x, y) != b.getPixelRGBA(x, y)) differ += 1
						differ.toDouble() / (a.width.toLong() * a.height)
					}
				}
				CreateRelativity.LOGGER.info("Vertex accuracy, {}: the pixel check changes {}% of the vertex lens's picture", label, "%.2f".format(removed * 100))
				val (eye, rotation) = computeOnClient { minecraft ->
					val camera = minecraft.gameRenderer.mainCamera
					camera.position to org.joml.Quaternionf(camera.rotation()).conjugate()
				}
				val hole = net.minecraft.world.phys.Vec3(s[0], s[1], s[2])
				val random = java.util.Random(11)
				val points = ArrayList<Pair<String, net.minecraft.world.phys.Vec3>>()
				for (x in -40..40 step 3) for (z in -40..40 step 3) points += "floor" to net.minecraft.world.phys.Vec3(hole.x + x, -60.0, hole.z + z)
				repeat(500) {
					val d = net.minecraft.world.phys.Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalize()
					points += "round the hole" to hole.add(d.scale(s[3] * (1.3 + random.nextDouble() * 12.0)))
				}
				repeat(300) {
					val d = net.minecraft.world.phys.Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalize()
					points += "near the camera" to eye.add(d.scale(0.5 + random.nextDouble() * 8.0))
				}
				// The GPU's verdict per point: placed (and where on screen) or hidden.
				val views = points.map { (_, p) -> rotation.transform(org.joml.Vector3f((p.x - eye.x).toFloat(), (p.y - eye.y).toFloat(), (p.z - eye.z).toFloat())).let { floatArrayOf(it.x, it.y, it.z) } }
				val (results, projection) = computeOnClient { _ ->
					views.chunked(1000).flatMap { KerrSolveCheck.run(it, VertexLens.lastProjection) } to org.joml.Matrix4f(VertexLens.lastProjection)
				}
				val gameFromView = org.joml.Quaternionf(rotation).conjugate()
				val sources = computeOnClient { minecraft -> LensReference.sourcesAround(minecraft, eye) }
				val ray = net.kernelpanicsoft.relativity.physics.geodesic.HamiltonianRay(net.kernelpanicsoft.relativity.physics.spacetime.field.ComposedSpacetime(sources), rtol = 1e-9, atol = 1e-11)
				val pixel = computeOnClient { minecraft -> 2.0 * kotlin.math.tan(Math.toRadians(minecraft.options.fov().get().toDouble()) / 2.0) / minecraft.window.height }
				val errors = HashMap<String, ArrayList<Double>>()
				val worst = ArrayList<Triple<Double, String, net.minecraft.world.phys.Vec3>>()
				val hidden = HashMap<String, ArrayList<net.minecraft.world.phys.Vec3>>()
				for ((i, entry) in points.withIndex()) {
					val (kind, p) = entry
					val view = views[i]
					if (view[2] > -0.05f) continue // behind the camera (in view space -z is ahead)
					val r = results[i]
					if (r[3] >= 99.5f && r[3] < 1000f) { hidden.getOrPut(kind) { ArrayList() } += p; continue }
					if (r[3] >= 1000f) { hidden.getOrPut(kind) { ArrayList() } += p; continue } // at a fold
					// Off screen: not drawn either way.
					if (kotlin.math.abs(r[1]) > 1.0f || kotlin.math.abs(r[2]) > 1.0f) continue
					val dirView = org.joml.Vector3f(r[1] / projection.m00(), r[2] / projection.m11(), -1f).normalize()
					val dirGame = gameFromView.transform(dirView)
					val placed = net.minecraft.world.phys.Vec3(dirGame.x.toDouble(), dirGame.y.toDouble(), dirGame.z.toDouble())
					val truth = LensReference.nearestImage(ray, eye, p, placed)
					// (9999: no true image near where it's drawn.)
					val px = if (truth == null) 9999.0 else kotlin.math.acos(truth.dot(placed.normalize()).coerceIn(-1.0, 1.0)) / pixel
					errors.getOrPut(kind) { ArrayList() } += px
					worst += Triple(px, kind, p)
				}
				// A sample of the hidden: whether they have an image after all.
				val missing = HashMap<String, Int>()
				val searched = HashMap<String, Int>()
				val reasons = java.util.TreeMap<String, Int>()
				for ((kind, list) in hidden) for (p in list.shuffled(random).take(15)) {
					searched.merge(kind, 1, Int::plus)
					val image = LensReference.imageDirection(sources, eye, p) ?: continue
					// Why the lens hid it.
					val view = rotation.transform(org.joml.Vector3f((p.x - eye.x).toFloat(), (p.y - eye.y).toFloat(), (p.z - eye.z).toFloat()))
					val why = computeOnClient { KerrSolveCheck.run(listOf(floatArrayOf(view.x, view.y, view.z)), VertexLens.lastProjection, why = true) }.single()
					val reason = when {
						why[1] > 0.5f -> "near the axis behind the hole"
						why[2] < -0.5f -> {
							// The GPU's solve started at the CPU's image.
							val iv = rotation.transform(org.joml.Vector3f(image.x.toFloat(), image.y.toFloat(), image.z.toFloat()))
							val fromTruth = computeOnClient { KerrSolveCheck.run(listOf(floatArrayOf(view.x, view.y, view.z)), VertexLens.lastProjection, guesses = listOf(floatArrayOf(iv.x, iv.y, iv.z))) }.single()
							"map: " + mapTexels(hole, s[3], s[4], eye, p) + "; solve from the true image: " + when (fromTruth[0].toInt()) {
								1 -> "found (%.4f rad off)".format(fromTruth[1]); 5 -> "fold (magnification %.0f)".format(fromTruth[3]); 0 -> "off the table"; else -> "fails"
							}
						}
						why[0] < -0.5f && why[2] > 1.5f -> "Schwarzschild table: no image"
						why[0] < -0.5f && why[2] in -0.5f..0.5f -> "outside the map, Schwarzschild: no image"
						else -> "other (Schwarzschild ${why[0]}, map ${why[2]})"
					}
					// Only if the image would be on screen.
					val v = rotation.transform(org.joml.Vector3f(image.x.toFloat(), image.y.toFloat(), image.z.toFloat()))
					if (v.z >= 0f) continue
					val ndc = org.joml.Vector3f(projection.m00() * v.x / -v.z, projection.m11() * v.y / -v.z, 0f)
					if (kotlin.math.abs(ndc.x) <= 1f && kotlin.math.abs(ndc.y) <= 1f) {
						missing.merge(kind, 1, Int::plus)
						// Where it is: behind the hole (far side) or not, how far off the camera-hole line, how far out.
						val toCamera = eye.subtract(hole).normalize()
						val rel = p.subtract(hole)
						val along = rel.dot(toCamera)
						val off = rel.subtract(toCamera.scale(along)).length() / s[3]
						val where = "%s, %.1f r_s off the axis, %.1f r_s out".format(if (along < 0) "far side" else "camera side", off, rel.length() / s[3])
						reasons.merge("$where: $reason", 1, Int::plus)
					}
				}
				worst.sortByDescending { it.first }
				val report = errors.entries.joinToString("\n") { (kind, list) ->
					list.sort()
					val nowhere = list.count { it >= 9999.0 }
					val real = list.filter { it < 9999.0 }
					"  $kind: ${list.size} placed on screen, $nowhere with no image near; error median %.1f px, 95%% %.1f px, 99%% %.1f px, worst %.1f px; hidden ${hidden[kind]?.size ?: 0}, of ${searched[kind] ?: 0} searched ${missing[kind] ?: 0} have an image on screen".format(
						real.getOrElse(real.size / 2) { 0.0 }, real.getOrElse((real.size * 0.95).toInt().coerceAtMost(real.size - 1)) { 0.0 },
						real.getOrElse((real.size * 0.99).toInt().coerceAtMost(real.size - 1)) { 0.0 }, real.lastOrNull() ?: 0.0)
				} + "\n  worst: " + worst.take(6).joinToString("; ") { "%.0f px %s at (%.1f, %.1f, %.1f)".format(it.first, it.second, it.third.x, it.third.y, it.third.z) }
				CreateRelativity.LOGGER.info("Vertex accuracy, {} (camera at {}):\n{}\n  hidden with an image, why:\n    {}", label, eye, report, reasons.entries.joinToString("\n    ") { "${it.key} (${it.value})" })
			}
		}
	}

	/**
	 * Whether baked camera slots (the image map for a few camera positions, blended) can stand in for solving every
	 * frame: the image map read with the camera at two neighbouring slot positions (same direction from the hole, one
	 * slot step apart in distance, or in height angle), blended for the camera between them, against the map solved
	 * there; error in pixels, and images the blend loses.
	 */
	@ClientGameTest
	fun ClientGameTestContext.slotBlend() {
		if (!ClientTestInput.selected("slotBlend")) return
		runOnClient { minecraft ->
			minecraft.options.renderDistance().set(4)
			minecraft.options.hideGui = true
			SpacetimeDebugRenderer.hidden = true
			minecraft.options.bobView().set(false)
		}
		withTestWorld {
			waitTicks(60)
			for (command in listOf(
				"gamemode spectator @a", "difficulty peaceful", "gamerule doMobSpawning false", "kill @e[type=!player]",
				"time set noon", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "weather clear",
				"gamerule relativityBlackHoleGrowth false", "tick freeze",
			)) command(command)
			val hole = net.minecraft.world.phys.Vec3(0.5, -20.0, 0.5)
			val rs = 2.0
			command("relativity bh create ${hole.x} ${hole.y} ${hole.z} $rs 0.9")
			val pixel = 2.0 * kotlin.math.tan(Math.toRadians(70.0) / 2.0) / 1504.0
			// The settled map with the camera at distance r (r_s) and height angle theta (from up) from the hole.
			fun mapAt(r: Double, theta: Double): Pair<FloatArray, net.minecraft.world.phys.Vec3> {
				val eye = hole.add(-r * rs * kotlin.math.sin(theta), r * rs * kotlin.math.cos(theta), 0.0)
				command("tp @a ${eye.x} ${eye.y - 1.62} ${eye.z} -90 0")
				runOnClient { it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO; KerrImageMap.invalidate() }
				waitTicks(80)
				val camera = computeOnClient { it.gameRenderer.mainCamera.position }
				return computeOnClient { KerrImageMap.readBack() } to camera
			}
			val rIn = run { val m = rs / 2; val a = 0.9 * m; 1.05 * (m + kotlin.math.sqrt(m * m - a * a)) }
			val rOut = 80.0 * rs
			for ((label, steps) in listOf(
				"7 r_s, distance step 1.19x" to listOf(7.0 to 1.4, 7.0 * 1.19 to 1.4),
				"7 r_s, distance step 1.09x" to listOf(7.0 to 1.4, 7.0 * 1.09 to 1.4),
				"3 r_s, distance step 1.09x" to listOf(3.0 to 1.4, 3.0 * 1.09 to 1.4),
				"7 r_s, height step 0.2 rad" to listOf(7.0 to 1.3, 7.0 to 1.5),
				"7 r_s, height step 0.1 rad" to listOf(7.0 to 1.35, 7.0 to 1.45),
			)) {
				val (a, eyeA) = mapAt(steps[0].first, steps[0].second)
				val (b, eyeB) = mapAt(steps[1].first, steps[1].second)
				val (m, eyeM) = mapAt(kotlin.math.sqrt(steps[0].first * steps[1].first), (steps[0].second + steps[1].second) / 2)
				val errors = ArrayList<Double>()
				var lost = 0
				var compared = 0
				for (k in 0 until KerrImageMap.DEPTH) for (j in 0 until KerrImageMap.HEIGHT) for (i in 0 until KerrImageMap.WIDTH) {
					val index = ((k * KerrImageMap.HEIGHT + j) * KerrImageMap.WIDTH + i) * 4
					if (m[index + 3] < 0.5f) continue
					compared += 1
					if (a[index + 3] < 0.5f || b[index + 3] < 0.5f) { lost += 1; continue }
					// The texel's point, and each camera's view of it.
					val azimuth = ((i + 0.5) / KerrImageMap.WIDTH - 0.5) * 2 * Math.PI
					val cosine = j.toDouble() / (KerrImageMap.HEIGHT - 1) * 2 - 1
					val sine = kotlin.math.sqrt(maxOf(1 - cosine * cosine, 0.0))
					val radius = rIn * Math.pow(rOut / rIn, k.toDouble() / (KerrImageMap.DEPTH - 1))
					val point = hole.add(radius * sine * kotlin.math.cos(azimuth), radius * cosine, radius * sine * kotlin.math.sin(azimuth))
					val game = point.subtract(eyeM)
					val span = game.length()
					// (Near the camera the vertex lens follows the light itself, not the map.)
					if (span < 0.35 * eyeM.distanceTo(hole)) { compared -= 1; continue }
					val straight = game.normalize()
					val wa = 0.5
					val blended = net.minecraft.world.phys.Vec3(
						(a[index] * wa + b[index] * (1 - wa)).toDouble(), (a[index + 1] * wa + b[index + 1] * (1 - wa)).toDouble(), (a[index + 2] * wa + b[index + 2] * (1 - wa)).toDouble())
					val truth = net.minecraft.world.phys.Vec3(m[index].toDouble(), m[index + 1].toDouble(), m[index + 2].toDouble())
					val seenBlend = straight.add(blended.scale(span)).normalize()
					val seenTrue = straight.add(truth.scale(span)).normalize()
					errors += kotlin.math.acos(seenBlend.dot(seenTrue).coerceIn(-1.0, 1.0)) / pixel
				}
				errors.sort()
				CreateRelativity.LOGGER.info("Slot blend, {}: {} texels with an image; the blend loses {} ({}%); error median {} px, 95% {} px, 99% {} px",
					label, compared, lost, "%.1f".format(100.0 * lost / compared), "%.2f".format(errors[errors.size / 2]),
					"%.1f".format(errors[(errors.size * 0.95).toInt()]), "%.1f".format(errors[(errors.size * 0.99).toInt()]))
			}
		}
	}

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
			// Frame rate as fast as it goes (the whole frame: the game, the vertex lens everywhere, the passes).
			minecraft.options.enableVsync().set(false)
			minecraft.window.updateVsync(false)
			minecraft.options.framerateLimit().set(260)
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
			// Average frames a second (the game's counter, over three seconds).
			fun ClientGameTestContext.fps(): Double {
				waitTicks(30)
				return (0 until 3).map { waitTicks(20); computeOnClient { it.fps }.toDouble() }.average()
			}
			// The same while the camera moves (a few centimetres a tick, back and forth), so what depends on where it
			// is relative to the hole is redrawn.
			fun ClientGameTestContext.fpsMoving(): Double {
				val start = computeOnClient { it.player!!.position() }
				val samples = ArrayList<Double>()
				for (tick in 0 until 90) {
					val offset = 0.05 * (tick % 20 - 10)
					runOnClient { it.player!!.setPos(start.x, start.y + offset, start.z) }
					waitTick()
					if (tick >= 30 && tick % 20 == 0) samples += computeOnClient { it.fps }.toDouble()
				}
				runOnClient { it.player!!.setPos(start.x, start.y, start.z) }
				return samples.average()
			}
			command("relativity bh clear")
			command("tp @a -14.5 -54 0.5 -90 5")
			settle()
			lines += "no hole: %.0f fps".format(fps())
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
				// The parts: the post pass cut to a copy; then the vertex lens's Kerr solve off, then the Kerr path pass too.
				runOnClient { BlackHoleLens.timeGpu = true }
				val fpsLens = fps()
				val fpsLensMoving = fpsMoving()
				val kerrMs = computeOnClient { "last drawn: Kerr paths %.1f ms, image map %.1f ms; slots: bake %.1f ms, blend %.1f ms, %d baked, blending %s".format(
					VertexLens.pathsTimer.millis, VertexLens.mapTimer.millis, KerrSlots.bakeTimer.millis, KerrSlots.blendTimer.millis, KerrSlots.bakedSlots, KerrSlots.blending) }
				runOnClient { BlackHoleLens.rawFrame = true; BlackHoleLens.skipTables = true }
				val fpsVertex = fps()
				runOnClient { VertexLens.pixelCheck = false }
				val fpsNoCheck = fps()
				runOnClient { KerrSlots.bakeNew = false }
				val fpsNoBaking = fps()
				runOnClient { KerrSlots.enabled = false }
				val fpsNoSlots = fps()
				runOnClient { VertexLens.pixelCheck = true; KerrSlots.enabled = true; KerrSlots.bakeNew = true }
				lines += "%s, post pass cut: pixel check off %.0f fps; and no baking %.0f fps (blending %s); and slots off %.0f fps".format(name, fpsNoCheck, fpsNoBaking, computeOnClient { KerrSlots.blending }, fpsNoSlots)
				runOnClient { VertexLens.kerrSolveOff = true }
				val fpsPaths = fps()
				runOnClient { VertexLens.kerrSolveOff = false; VertexLens.kerrOff = true }
				val fpsSchwarzschild = fps()
				runOnClient { VertexLens.kerrOff = false; BlackHoleLens.rawFrame = false; BlackHoleLens.skipTables = false }
				lines += "%s: %.0f fps with the lens (moving %.0f); post pass cut: %.0f; and no Kerr solve: %.0f; and no Kerr paths: %.0f (%s)".format(name, fpsLens, fpsLensMoving, fpsVertex, fpsPaths, fpsSchwarzschild, kerrMs)
				val fixed = time { BlackHoleLens.rawFrame = true; BlackHoleLens.skipTables = true }
				val tables = time { BlackHoleLens.rawFrame = true }
				val lens = time {}
				// The lens pass's parts: without the full-resolution re-march, with a plain sky, without the disk, none.
				val parts = listOf(64, 128, 256, 64 or 128 or 256, 2048, 4096).map { flags -> time { BlackHoleLens.profileFlags = flags } }
				// The tables' parts: the half-resolution one never marching; no geometry in any march.
				val tableParts = listOf(1024, 2).map { flags -> time { BlackHoleLens.rawFrame = true; BlackHoleLens.profileFlags = flags } - fixed }
				// Back to the whole lens (the timings above leave their switches set) for the pictures.
				runOnClient {
					BlackHoleLens.rawFrame = false
					BlackHoleLens.skipTables = false
					BlackHoleLens.marchEveryPixel = false
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
					" %.1f steps a pixel; lens pass without re-march %.2f, plain sky %.2f, no disk %.2f, none %.2f, drawn pixels untouched %.2f, sky pixels untouched %.2f; tables with no half-res march %.2f, without geometry %.2f").format(
					name, fixed, tables - fixed, lens - tables, lens, sceneRemarched * 100, kerrRemarched * 100, meanSteps,
					parts[0] - tables, parts[1] - tables, parts[2] - tables, parts[3] - tables, parts[4] - tables, parts[5] - tables,
					tableParts[0], tableParts[1],
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
			// Filling (until both grids are full; they share a frame's budget): the most a frame spends on it.
			var worst = 0.0
			for (i in 0 until 600) {
				waitTick()
				worst = maxOf(worst, computeOnClient { LensVoxels.updateMillis })
				if (i >= 60 && computeOnClient { LensVoxels.ready && LensVoxels.behind.ready }) break
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
			// Debug switches back to normal, so a failed test doesn't leave the lens half off for the next.
			runOnClient {
				BlackHoleLens.rawFrame = false
				BlackHoleLens.skipTables = false
				BlackHoleLens.marchEveryPixel = false
				BlackHoleLens.marchDebug = false
				BlackHoleLens.marchStepsDebug = false
				BlackHoleLens.remarchDebug = false
				BlackHoleLens.profileFlags = 0
				BlackHoleLens.timeGpu = false
				BlackHoleLens.unseen = BlackHoleLens.Unseen.VOXELS
				VertexLens.kerrOff = false
				VertexLens.kerrSolveOff = false
				VertexLens.kerrDebug = 0
				KerrImageMap.debugMode = 0
			}
			world.close()
			runOnClient { ClientTestInput.unlock(it) }
			if (save.parent?.fileName?.toString() == "saves") save.toFile().deleteRecursively()
		}
	}

	/** Waits (up to 30 s) until every chunk section in view is drawn and the lens's grids are in, then a second more. */
	/**
	 * The image map texels round world point [p] (hole at [hole], r_s [rs], spin [spin]; camera at [eye]): how many of
	 * the 8 have an image, how many were searched and found none, how many found one at a fold.
	 */
	private fun ClientGameTestContext.mapTexels(hole: net.minecraft.world.phys.Vec3, rs: Double, spin: Double, eye: net.minecraft.world.phys.Vec3, p: net.minecraft.world.phys.Vec3): String =
		computeOnClient { minecraft ->
			val map = KerrImageMap.readBack()
			val paths = KerrImageMap.readBackPaths()
			val m = rs / 2
			val a = spin * m
			val rIn = 1.05 * (m + kotlin.math.sqrt(maxOf(m * m - a * a, 0.0)))
			val rOut = maxOf(80.0 * rs, eye.distanceTo(hole) + 1.5 * minecraft.gameRenderer.renderDistance)
			val d = p.subtract(hole)
			val r = d.length()
			val u = (kotlin.math.atan2(d.z, d.x) / (2 * Math.PI) + 0.5) * KerrImageMap.WIDTH - 0.5
			val v = (d.y / r * 0.5 + 0.5) * (KerrImageMap.HEIGHT - 1)
			val w = kotlin.math.ln(r / rIn) / kotlin.math.ln(rOut / rIn) * (KerrImageMap.DEPTH - 1)
			var ok = 0; var none = 0; var fold = 0; var other = 0
			for (corner in 0 until 8) {
				val i = Math.floorMod(kotlin.math.floor(u).toInt() + (corner and 1), KerrImageMap.WIDTH)
				val j = (kotlin.math.floor(v).toInt() + ((corner shr 1) and 1)).coerceIn(0, KerrImageMap.HEIGHT - 1)
				val k = (kotlin.math.floor(w).toInt() + ((corner shr 2) and 1)).coerceIn(0, KerrImageMap.DEPTH - 1)
				val index = (k * KerrImageMap.HEIGHT + j) * KerrImageMap.WIDTH + i
				val code = paths[index]
				when {
					map[index * 4 + 3] > 0.5f -> ok += 1
					code < -98.9f -> none += 1
					code < -98.0f -> fold += 1
					else -> other += 1
				}
			}
			"$ok with an image, $none none found, $fold at a fold, $other other"
		}

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
