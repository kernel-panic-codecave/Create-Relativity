package net.kernelpanicsoft.relativity.gametest

import kotlin.math.abs

import com.mojang.blaze3d.platform.NativeImage
import net.kernelpanicsoft.archie.gametest.ClientGameTest
import net.kernelpanicsoft.archie.gametest.ClientGameTestContext
import net.kernelpanicsoft.archie.gametest.TestSingleplayerContext
import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.client.SpacetimeDebugRenderer
import net.kernelpanicsoft.relativity.client.lens.BlackHoleLens
import net.kernelpanicsoft.relativity.client.lens.LightTable
import net.kernelpanicsoft.relativity.client.lens.QuadSubdivision
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
	 * The lens with nearby ground: a hole above a checkerboard floor with red posts, seen from
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
				"offaxis" to "-8.586 -54 2.658 -142.6 7.5", // close, past the hole's side: the Einstein ring off-axis
				"under" to "-17.651 -50.12229 4.946 -81.9 32.7", // above, a pillar under the hole in front of its shadow
			)
			for ((pose, at) in plain) lensPose(pose, 0.0, at, failures)
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
			runOnClient { BlackHoleLens.timeGpu = false }
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
		withTestWorld(ponderflat = false) { // (plain ground behind the shafts: a checkerboard there magnifies by-design differences by the axis)
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
			// (Up to 1.5%: by the camera-hole axis the two differ by design. Vanilla's shaders carry the pixel check and take the
			// fine near-axis cut; Flywheel's don't, and keep the full one.)
			assertTrue(differing < 0.015) { "Flywheel's shafts aren't bent like vanilla's: ${differing * 100}% of pixels differ" }
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
				}
			}
			runOnClient {
				it.options.hideGui = false
				SpacetimeDebugRenderer.hidden = false
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
			// groundAroundHole's side view.
			"ground side" to doubleArrayOf(0.5, -54.0, 0.5, 2.0, 0.0, -14.5, -54.0, 0.5, -90.0, 10.0),
			"from 4 r_s" to doubleArrayOf(0.5, -48.0, 0.5, 6.0, 0.0, -23.5, -48.0, 0.5, -90.0, 15.0),
			"from 3 r_s" to doubleArrayOf(0.5, -50.0, 0.5, 4.0, 0.0, -11.5, -50.0, 0.5, -90.0, 20.0),
			// A large hole among floating blocks (where giant slabs once showed).
			"floating blocks, from 3.5 r_s" to doubleArrayOf(0.5, -54.0, 0.5, 6.0, 0.0, -20.5, -54.0, 0.5, -90.0, 5.0),
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
				runOnClient { it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO }
				waitTicks(40)
				settle()
				val shotName = "vertex_accuracy_${label.replace(Regex("[^a-z0-9]+"), "_")}"
				takeScreenshot(shotName)
				// The vertex lens alone (no post pass), with and without the pixel check: what it removes.
				runOnClient { BlackHoleLens.rawFrame = true; VertexLens.pixelCheck = false }
				waitTicks(5)
				val unchecked = takeScreenshot("${shotName}_vertex_unchecked")
				runOnClient { VertexLens.pixelCheck = true }
				waitTicks(5)
				val checked = takeScreenshot("${shotName}_vertex_checked")
				runOnClient { BlackHoleLens.rawFrame = false }
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
					views.chunked(1000).flatMap { LensCheck.run(it, VertexLens.lastProjection) } to org.joml.Matrix4f(VertexLens.lastProjection)
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
					if (r[0] < 0f) { hidden.getOrPut(kind) { ArrayList() } += p; continue }
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
					val why = computeOnClient { LensCheck.run(listOf(floatArrayOf(view.x, view.y, view.z)), VertexLens.lastProjection, why = true) }.single()
					val reason = when {
						why[1] > 0.5f -> "near the axis behind the hole"
						why[0] < -0.5f -> "Schwarzschild table: no image"
						else -> "other (Schwarzschild ${why[0]})"
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
	 * A showcase flight (not a check: frames for a video): a spinning, fed hole over a coast at sunset, its disk churning
	 * and jets firing, the camera circling it and sinking toward its disk's plane, looking at it. One screenshot a frame
	 * (showcase_NNN, plus _iris with a shader pack), for ffmpeg.
	 */
	@ClientGameTest
	fun ClientGameTestContext.showcase() {
		if (!ClientTestInput.selected("showcase")) return
		runOnClient { minecraft ->
			minecraft.options.renderDistance().set(16)
			minecraft.options.hideGui = true
			SpacetimeDebugRenderer.hidden = true
			minecraft.options.bobView().set(false)
			minecraft.options.fov().set(80)
		}
		withTestWorld(seed = "-3745315768298803473") {
			waitTicks(60)
			for (command in listOf(
				"gamemode spectator @a", "difficulty peaceful", "gamerule doMobSpawning false", "kill @e[type=!player]",
				"time set 12300", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "weather clear",
				"gamerule relativityBlackHoleGrowth false",
			)) command(command)
			val hole = net.minecraft.world.phys.Vec3(-15.5, 96.0, -146.0)
			command("relativity bh create ${hole.x} ${hole.y} ${hole.z} 4 0.9")
			command("relativity bh feed 3000000")
			val suffix = if (net.neoforged.fml.ModList.get().isLoaded("iris")) "_iris" else ""
			val frames = 240
			fun place(frame: Int) {
				val f = frame / (frames - 1.0)
				val angle = Math.toRadians(200.0 + 300.0 * f)
				val distance = 75.0 - 15.0 * f
				val height = 30.0 * (1.0 - f) * (1.0 - f) + 12.0
				val x = hole.x + distance * kotlin.math.cos(angle)
				val z = hole.z + distance * kotlin.math.sin(angle)
				val y = hole.y + height
				val dx = hole.x - x; val dy = hole.y - y; val dz = hole.z - z
				val yaw = Math.toDegrees(kotlin.math.atan2(-dx, dz)).toFloat()
				val pitch = Math.toDegrees(-kotlin.math.atan2(dy, kotlin.math.sqrt(dx * dx + dz * dz))).toFloat()
				runOnClient { minecraft ->
					val player = minecraft.player!!
					player.setPos(x, y - player.eyeHeight, z)
					player.setYRot(yaw); player.yRotO = yaw
					player.setXRot(pitch); player.xRotO = pitch
					player.deltaMovement = net.minecraft.world.phys.Vec3.ZERO
				}
			}
			place(0)
			command("tp @a ${hole.x - 40} ${hole.y + 30} ${hole.z}")
			waitTicks(100) // the disk brightens as the feed's rate builds up
			// The world along the whole path generated and meshed first (stops every 20 frames, each until all is drawn).
			for (frame in 0 until frames step 20) {
				place(frame)
				waitTicks(20)
				for (i in 0 until 600) {
					if (computeOnClient { it.levelRenderer.hasRenderedAllSections() }) break
					waitTick()
				}
			}
			place(0)
			waitTicks(60)
			settle()
			for (frame in 0 until frames) {
				if (frame % 60 == 0) command("relativity bh feed 600000") // (keeps it fed: the rate decays)
				place(frame)
				waitTicks(2)
				takeScreenshot("showcase$suffix" + "_%03d".format(frame))
			}
			runOnClient {
				it.options.hideGui = false
				SpacetimeDebugRenderer.hidden = false
				it.options.fov().set(70)
			}
		}
	}

	/**
	 * The back of your own head: the camera just outside a hole's photon sphere, looking along the circular orbit, sees
	 * light that left its own body and went round the hole (the player's own image round the hole, VertexLens.drawSelfImage).
	 * Screenshots with it and without; how much of the picture it changes.
	 */
	@ClientGameTest
	fun ClientGameTestContext.selfImage() {
		if (!ClientTestInput.selected("selfImage")) return
		runOnClient { minecraft ->
			minecraft.options.renderDistance().set(8)
			minecraft.options.hideGui = true
			SpacetimeDebugRenderer.hidden = true
			minecraft.options.bobView().set(false)
		}
		withTestWorld {
			waitTicks(60)
			for (command in listOf(
				"gamemode creative @a", "difficulty peaceful", "gamerule doMobSpawning false", "kill @e[type=!player]",
				"time set noon", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "weather clear",
				"gamerule relativityBlackHoleGrowth false", "tick freeze",
			)) command(command)
			command("relativity bh clear")
			// A big hole (r_s 32, at y = 40): the tides at the photon sphere go as 1 / r_s^2, and round a small one they tear
			// the player apart. The eye just outside the photon sphere (1.502 r_s: further out the image is a sub-pixel line) on its -x
			// side, looking along +z (the orbit).
			command("relativity bh create 0.5 40 0.5 32.0 0")
			val eyeHeight = computeOnClient { it.player!!.eyeHeight.toDouble() }
			command("tp @a ${0.5 - 1.502 * 32} ${40.0 - eyeHeight} 0.5 0 0")
			runOnClient { it.player!!.abilities.flying = true; it.player!!.deltaMovement = net.minecraft.world.phys.Vec3.ZERO }
			waitTicks(40)
			settle()
			val shots = HashMap<Boolean, java.nio.file.Path>()
			for ((label, yaw) in listOf("along the orbit" to 0f, "toward the hole" to -90f)) {
				runOnClient { it.player!!.yRot = yaw; it.player!!.yRotO = yaw; it.player!!.xRot = 0f; it.player!!.xRotO = 0f }
				for (on in listOf(false, true)) {
					runOnClient { VertexLens.selfImage = on }
					waitTicks(5)
					shots[on] = takeScreenshot("self_image_${label.replace(' ', '_')}_$on")
				}
				val changed = Files.newInputStream(shots[false]!!).use { NativeImage.read(it) }.use { x ->
					Files.newInputStream(shots[true]!!).use { NativeImage.read(it) }.use { y ->
						var differ = 0L
						for (j in 0 until x.height) for (i in 0 until x.width) if (x.getPixelRGBA(i, j) != y.getPixelRGBA(i, j)) differ += 1
						differ.toDouble() / (x.width.toLong() * x.height)
					}
				}
				CreateRelativity.LOGGER.info("Self image, looking {}: the player's secondary image changes {}% of the picture (body stretch {})", label, "%.2f".format(100 * changed),
					computeOnClient { net.kernelpanicsoft.relativity.spacetime.SpacetimeAttachments.bodyStretch.get(it.player!!) })
			}
			runOnClient { VertexLens.selfImage = false }
		}
	}

	/**
	 * Secondary images drawn as geometry (VertexLens.drawSecondImages) round a non-spinning hole: screenshots of the vertex
	 * lens alone and of the whole lens with them on and off, how much of the picture they change (the post pass traced
	 * the same images per pixel before, so the whole lens should barely change), and the frame rate either way.
	 */
	/**
	 * What tessellation costs (run with -Drelativity.tessellation=true): round a lone hole over a floor with blocks and
	 * cubes about, looking at it, the frame rate, the level's GPU time and the triangles it makes (after every stage),
	 * with edges split into at most 1, 4, 16 pieces and as many as the control stage asks.
	 */
	@ClientGameTest
	fun ClientGameTestContext.tessellationCost() {
		if (!ClientTestInput.selected("tessellationCost")) return
		runOnClient { minecraft ->
			minecraft.options.renderDistance().set(12)
			minecraft.options.hideGui = true
			SpacetimeDebugRenderer.hidden = true
			minecraft.options.bobView().set(false)
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
			command("relativity bh clear")
			command("relativity bh create 0.5 -54 0.5 2.0 0")
			command("tp @a -14.5 -54 0.5 -90 5")
			runOnClient { it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO; VertexLens.timeLevel = true; VertexLens.countLevel = true }
			waitTicks(40)
			settle()
			for ((most, skip) in listOf(1f to 0, 0f to 0, 1f to 7)) {
				runOnClient { net.kernelpanicsoft.relativity.client.lens.TessellationStage.most = most; net.kernelpanicsoft.relativity.client.lens.TessellationStage.skip = skip }
				waitTicks(10)
				val samples = (0 until 3).map {
					waitTicks(10)
					computeOnClient { Triple(it.fps.toDouble(), VertexLens.levelTimer.millis, VertexLens.levelCounter.count.toDouble()) }
				}
				CreateRelativity.LOGGER.info("Tessellation cost, edges in at most {} pieces, skipping {}: {} fps, level {} ms on the GPU, {} triangles",
					if (most == 0f) "64 (its own)" else "%.0f".format(most), listOfNotNull("midpoints".takeIf { skip and 1 != 0 }, "image search".takeIf { skip and 2 != 0 }, "copies".takeIf { skip and 4 != 0 }).ifEmpty { listOf("nothing") }.joinToString(" + "), "%.0f".format(samples.map { it.first }.average()),
					"%.1f".format(samples.map { it.second }.average()), "%.0f".format(samples.map { it.third }.average()))
			}
			runOnClient { net.kernelpanicsoft.relativity.client.lens.TessellationStage.most = 0f; net.kernelpanicsoft.relativity.client.lens.TessellationStage.skip = 0; VertexLens.timeLevel = false; VertexLens.countLevel = false }
		}
	}

	@ClientGameTest
	fun ClientGameTestContext.secondImages() {
		if (!ClientTestInput.selected("secondImages")) return
		runOnClient { minecraft ->
			minecraft.options.renderDistance().set(12)
			minecraft.options.hideGui = true
			SpacetimeDebugRenderer.hidden = true
			minecraft.options.bobView().set(false)
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
			// Create's cogwheels and shafts round the hole (Flywheel draws them, instanced): their secondary images too.
			server.runOnServer { minecraft ->
				val level = minecraft.overworld()
				val cog = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("create:large_cogwheel"))
				val shaft = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("create:shaft"))
				for (k in 0 until 12) {
					val angle = k * Math.PI / 6
					val x = kotlin.math.round(10 * kotlin.math.cos(angle)).toInt()
					val z = kotlin.math.round(10 * kotlin.math.sin(angle)).toInt()
					level.setBlock(BlockPos(x, -57, z), cog.defaultBlockState(), 2)
					for (y in -60..-58) level.setBlock(BlockPos(x, y, z), shaft.defaultBlockState(), 2)
				}
				// And entities (vanilla draws them batched, BufferUploader): armour stands in coloured armour, some sheep.
				for (k in 0 until 8) {
					val angle = k * Math.PI / 4 + 0.2
					val stand = net.minecraft.world.entity.EntityType.ARMOR_STAND.create(level) ?: continue
					stand.moveTo(0.5 + 6 * kotlin.math.cos(angle), -60.0, 0.5 + 6 * kotlin.math.sin(angle), 0f, 0f)
					stand.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLDEN_HELMET))
					stand.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE))
					level.addFreshEntity(stand)
					val sheep = net.minecraft.world.entity.EntityType.SHEEP.create(level) ?: continue
					sheep.moveTo(0.5 + 13 * kotlin.math.cos(angle + 0.4), -60.0, 0.5 + 13 * kotlin.math.sin(angle + 0.4), 0f, 0f)
					sheep.setNoAi(true)
					level.addFreshEntity(sheep)
				}
			}
			command("tick freeze")
			for (spin in listOf(0.0)) {
			command("relativity bh clear")
			command("relativity bh create 0.5 -54 0.5 2.0 $spin")
			command("tp @a -14.5 -54 0.5 -90 5")
			runOnClient { it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO }
			waitTicks(40)
			settle()
			fun ClientGameTestContext.fps(): Double {
				waitTicks(30)
				return (0 until 3).map { waitTicks(20); computeOnClient { it.fps }.toDouble() }.average()
			}
			val shots = HashMap<String, java.nio.file.Path>()
			val rates = HashMap<Boolean, Double>()
			for (on in listOf(false, true)) {
				runOnClient { VertexLens.secondImages = on; BlackHoleLens.profileFlags = 4096 }
				waitTicks(5)
				shots["vertex_$on"] = takeScreenshot("second_images_${spin}_vertex_$on")
				runOnClient { BlackHoleLens.profileFlags = 0 }
				waitTicks(5)
				shots["lens_$on"] = takeScreenshot("second_images_${spin}_lens_$on")
				rates[on] = fps()
			}
			fun changed(a: java.nio.file.Path, b: java.nio.file.Path): Double = Files.newInputStream(a).use { NativeImage.read(it) }.use { x ->
				Files.newInputStream(b).use { NativeImage.read(it) }.use { y ->
					var differ = 0L
					for (jj in 0 until x.height) for (ii in 0 until x.width) {
						val p = x.getPixelRGBA(ii, jj)
						val q = y.getPixelRGBA(ii, jj)
						var d = 0
						for (c in 0..2) d = maxOf(d, kotlin.math.abs(((p shr (8 * c)) and 255) - ((q shr (8 * c)) and 255)))
						if (d > 48) differ += 1
					}
					differ.toDouble() / (x.width.toLong() * x.height)
				}
			}
			// What the camera should see (the CPU's traced rays against the blocks), for a look beside the screenshots.
			if (spin == 0.0) {
				val columns = 376
				val rows = 250
				val picture = IntArray(columns * rows)
				for (first in 0 until rows step 5) {
					val part = computeOnClient { minecraft ->
						LensReference.picture(minecraft, SpacetimeField.blackHoles(minecraft.level!!).single(), columns, rows, 400.0, first until minOf(first + 5, rows))
					}
					for (row in first until minOf(first + 5, rows)) System.arraycopy(part, row * columns, picture, row * columns, columns)
				}
				NativeImage(columns, rows, false).use { image ->
					for (row in 0 until rows) for (column in 0 until columns) {
						val argb = picture[row * columns + column]
						image.setPixelRGBA(column, row, (argb and 0xFF00FF00.toInt()) or ((argb shr 16) and 0xFF) or ((argb and 0xFF) shl 16))
					}
					image.writeToFile(shots["vertex_true"]!!.resolveSibling("second_images_reference_picture.png"))
				}
			}
			CreateRelativity.LOGGER.info("Second images, spin {}: they change {}% of the vertex lens's picture and {}% of the whole lens's; {} fps with them, {} without", spin,
				"%.2f".format(100 * changed(shots["vertex_false"]!!, shots["vertex_true"]!!)), "%.2f".format(100 * changed(shots["lens_false"]!!, shots["lens_true"]!!)),
				"%.0f".format(rates[true]), "%.0f".format(rates[false]))
			CreateRelativity.LOGGER.info("Second images, spin {}: the light table's detail: {}", spin, computeOnClient { net.kernelpanicsoft.relativity.client.lens.LightTable.detailBlocks() })
			}
			runOnClient { VertexLens.secondImages = true }
		}

	}

	/** Maglev track: straight beside Create's own, a slope, a diagonal, crossings and curves, for a look (screenshots, nothing asserted). */
	@ClientGameTest
	fun ClientGameTestContext.maglevTrack() {
		if (!ClientTestInput.selected("maglevTrack")) return
		runOnClient { minecraft ->
			minecraft.options.hideGui = true
			SpacetimeDebugRenderer.hidden = true
			minecraft.options.bobView().set(false)
		}
		withTestWorld {
			waitTicks(40)
			for (command in listOf("gamemode spectator @a", "time set noon", "gamerule doDaylightCycle false", "relativity bh clear")) command(command)
			val crossingEnds = ArrayList<BlockPos>()
			server.runOnServer { minecraft ->
				val level = minecraft.overworld()
				val maglev = net.kernelpanicsoft.relativity.content.RelativityBlocks.MAGLEV_TRACK.get().defaultBlockState()
				fun shaped(shape: com.simibubi.create.content.trains.track.TrackShape) = maglev.setValue(com.simibubi.create.content.trains.track.TrackBlock.SHAPE, shape)
				val track = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("create:track")).defaultBlockState()
				for (z in -6..6) {
					level.setBlock(BlockPos(0, -60, z), shaped(com.simibubi.create.content.trains.track.TrackShape.ZO), 2)
					level.setBlock(BlockPos(4, -60, z), track, 2)
				}
				// A bogey on the straight, laid as a train casing lays one.
				val under = BlockPos(0, -60, -3)
				val anchor = (maglev.block as com.simibubi.create.content.trains.track.TrackBlock).getBogeyAnchor(level, under, level.getBlockState(under))
				level.setBlock(under.above(), anchor, 3)
				// A slope up and on, and a diagonal.
				for (k in 0..3) level.setBlock(BlockPos(-4, -60 + k, k), shaped(com.simibubi.create.content.trains.track.TrackShape.AS), 2)
				for (z in -6..-1) level.setBlock(BlockPos(-4, -60, z), shaped(com.simibubi.create.content.trains.track.TrackShape.ZO), 2)
				for (k in 0..5) level.setBlock(BlockPos(-14 + k, -60, -12 + k), shaped(com.simibubi.create.content.trains.track.TrackShape.PD), 2)
				// Crossings, arms out each way: square, and diagonal.
				for (k in -3..3) {
					level.setBlock(BlockPos(-10, -60, 8 + k), shaped(com.simibubi.create.content.trains.track.TrackShape.ZO), 2)
					level.setBlock(BlockPos(-10 + k, -60, 8), shaped(com.simibubi.create.content.trains.track.TrackShape.XO), 2)
					level.setBlock(BlockPos(-18 + k, -60, 8 + k), shaped(com.simibubi.create.content.trains.track.TrackShape.PD), 2)
					level.setBlock(BlockPos(-18 - k, -60, 8 + k), shaped(com.simibubi.create.content.trains.track.TrackShape.ND), 2)
				}
				level.setBlock(BlockPos(-10, -60, 8), shaped(com.simibubi.create.content.trains.track.TrackShape.CR_O), 2)
				level.setBlock(BlockPos(-18, -60, 8), shaped(com.simibubi.create.content.trains.track.TrackShape.CR_D), 2)
				// The diagonal-over-straight ones, in a row further on.
				for ((i, crossing) in listOf("cr_pdx", "cr_pdz", "cr_ndx", "cr_ndz").withIndex()) {
					val c = BlockPos(-34 + 9 * i, -60, 26)
					val shape = com.simibubi.create.content.trains.track.TrackShape.valueOf(crossing.uppercase())
					val diagonal = if (crossing.startsWith("cr_pd")) com.simibubi.create.content.trains.track.TrackShape.PD else com.simibubi.create.content.trains.track.TrackShape.ND
					val straight = if (crossing.endsWith("x")) com.simibubi.create.content.trains.track.TrackShape.XO else com.simibubi.create.content.trains.track.TrackShape.ZO
					for (k in -3..3) {
						level.setBlock(if (diagonal == com.simibubi.create.content.trains.track.TrackShape.PD) c.offset(k, 0, k) else c.offset(-k, 0, k), shaped(diagonal), 2)
						level.setBlock(if (straight == com.simibubi.create.content.trains.track.TrackShape.XO) c.offset(k, 0, 0) else c.offset(0, 0, k), shaped(straight), 2)
					}
					level.setBlock(c, shaped(shape), 2)
				}
				// Curves: from each straight's far end round to a track along x (as Create's placement leaves them), maglev's
				// and Create's.
				fun connect(
					state: net.minecraft.world.level.block.state.BlockState, endA: BlockPos, axisA: net.minecraft.world.phys.Vec3, endB: BlockPos,
					axisB: net.minecraft.world.phys.Vec3, material: com.simibubi.create.content.trains.track.TrackMaterial,
				) {
					fun along(axis: net.minecraft.world.phys.Vec3) = if (axis.x != 0.0) com.simibubi.create.content.trains.track.TrackShape.XO else com.simibubi.create.content.trains.track.TrackShape.ZO
					for ((end, axis) in listOf(endA to axisA, endB to axisB)) {
						if (level.getBlockEntity(end) == null)
							level.setBlock(end, state.setValue(com.simibubi.create.content.trains.track.TrackBlock.SHAPE, along(axis)).setValue(com.simibubi.create.content.trains.track.TrackBlock.HAS_BE, true), 2)
					}
					val block = state.block as com.simibubi.create.content.trains.track.TrackBlock
					val up = net.minecraft.world.phys.Vec3(0.0, 1.0, 0.0)
					val connection = com.simibubi.create.content.trains.track.BezierConnection(
						net.createmod.catnip.data.Couple.create(endA, endB),
						net.createmod.catnip.data.Couple.create(block.getCurveStart(level, endA, level.getBlockState(endA), axisA), block.getCurveStart(level, endB, level.getBlockState(endB), axisB)),
						net.createmod.catnip.data.Couple.create(axisA, axisB),
						net.createmod.catnip.data.Couple.create(up, up),
						true, false, material,
					)
					for ((end, c) in listOf(endA to connection, endB to connection.secondary())) {
						val entity = level.getBlockEntity(end) as com.simibubi.create.content.trains.track.TrackBlockEntity
						entity.addConnection(c)
						entity.notifyUpdate()
					}
				}
				fun curve(state: net.minecraft.world.level.block.state.BlockState, x: Int, material: com.simibubi.create.content.trains.track.TrackMaterial) {
					val endB = BlockPos(x + 6, -60, 13)
					level.setBlock(endB.east(), state.setValue(com.simibubi.create.content.trains.track.TrackBlock.SHAPE, com.simibubi.create.content.trains.track.TrackShape.XO), 2)
					connect(state, BlockPos(x, -60, 6), net.minecraft.world.phys.Vec3(0.0, 0.0, 1.0), endB, net.minecraft.world.phys.Vec3(-1.0, 0.0, 0.0), material)
				}
				// A junction: a straight, curves leaving it either way from one block (a wye), the straight going on.
				for (z in -6..14) level.setBlock(BlockPos(20, -60, z), shaped(com.simibubi.create.content.trains.track.TrackShape.ZO), 2)
				for (k in 1..3) {
					level.setBlock(BlockPos(26 + k, -60, 7), shaped(com.simibubi.create.content.trains.track.TrackShape.XO), 2)
					level.setBlock(BlockPos(14 - k, -60, 7), shaped(com.simibubi.create.content.trains.track.TrackShape.XO), 2)
				}
				level.setBlock(BlockPos(20, -60, 0), shaped(com.simibubi.create.content.trains.track.TrackShape.ZO).setValue(com.simibubi.create.content.trains.track.TrackBlock.HAS_BE, true), 2)
				// And a straight across it all, under both curves at whatever angle they have there.
				for (x in 8..32) level.setBlock(BlockPos(x, -60, 4), shaped(com.simibubi.create.content.trains.track.TrackShape.XO), 2)
				level.setBlock(BlockPos(20, -60, 4), shaped(com.simibubi.create.content.trains.track.TrackShape.CR_O), 2)
				connect(maglev, BlockPos(20, -60, 0), net.minecraft.world.phys.Vec3(0.0, 0.0, 1.0), BlockPos(26, -60, 7), net.minecraft.world.phys.Vec3(-1.0, 0.0, 0.0), net.kernelpanicsoft.relativity.content.Maglev.MATERIAL)
				connect(maglev, BlockPos(20, -60, 0), net.minecraft.world.phys.Vec3(0.0, 0.0, 1.0), BlockPos(14, -60, 7), net.minecraft.world.phys.Vec3(1.0, 0.0, 0.0), net.kernelpanicsoft.relativity.content.Maglev.MATERIAL)
				curve(maglev, 0, net.kernelpanicsoft.relativity.content.Maglev.MATERIAL)
				curve(track, 4, com.simibubi.create.content.trains.track.TrackMaterial.ANDESITE)
				// A two-lane junction: lanes along z four apart, each with curves leaving it both ways from the same z, so
				// each lane's curves cross the other lane (four ways out of two lanes).
				for (lane in listOf(40, 44)) {
					for (z in -10..14) level.setBlock(BlockPos(lane, -60, z), shaped(com.simibubi.create.content.trains.track.TrackShape.ZO), 2)
					level.setBlock(BlockPos(lane, -60, 0), shaped(com.simibubi.create.content.trains.track.TrackShape.ZO).setValue(com.simibubi.create.content.trains.track.TrackBlock.HAS_BE, true), 2)
					for (k in 1..4) {
						level.setBlock(BlockPos(lane + 8 + k, -60, 8), shaped(com.simibubi.create.content.trains.track.TrackShape.XO), 2)
						level.setBlock(BlockPos(lane - 8 - k, -60, 8), shaped(com.simibubi.create.content.trains.track.TrackShape.XO), 2)
					}
					connect(maglev, BlockPos(lane, -60, 0), net.minecraft.world.phys.Vec3(0.0, 0.0, 1.0), BlockPos(lane + 8, -60, 8), net.minecraft.world.phys.Vec3(-1.0, 0.0, 0.0), net.kernelpanicsoft.relativity.content.Maglev.MATERIAL)
					connect(maglev, BlockPos(lane, -60, 0), net.minecraft.world.phys.Vec3(0.0, 0.0, 1.0), BlockPos(lane - 8, -60, 8), net.minecraft.world.phys.Vec3(1.0, 0.0, 0.0), net.kernelpanicsoft.relativity.content.Maglev.MATERIAL)
				}
				// Two-lane roads crossing (lanes 4 apart each way), each corner turned from both lanes: the inner lanes' curves
				// tight, the outer lanes' across the other lanes and through the middle, where they cross each other. Once
				// with Create's usual curves, once with the widest that fit (crossing between the lanes).
				for ((cx, inner) in listOf(80 to 5, 130 to 12)) {
					val cz = 60
					val lanesX = listOf(cx, cx + 4)
					val lanesZ = listOf(cz, cz + 4)
					val reach = inner + 10
					for (x in lanesX) for (z in cz - reach..cz + 4 + reach) level.setBlock(BlockPos(x, -60, z), shaped(com.simibubi.create.content.trains.track.TrackShape.ZO), 2)
					for (z in lanesZ) for (x in cx - reach..cx + 4 + reach) level.setBlock(BlockPos(x, -60, z), shaped(com.simibubi.create.content.trains.track.TrackShape.XO), 2)
					for (x in lanesX) for (z in lanesZ) level.setBlock(BlockPos(x, -60, z), shaped(com.simibubi.create.content.trains.track.TrackShape.CR_O), 2)
					for (sx in listOf(-1, 1)) for (sz in listOf(-1, 1)) {
						// (The lanes on this corner's side are the inner pair, the far ones the outer.)
						val nearX = if (sx > 0) cx + 4 else cx
						val farX = if (sx > 0) cx else cx + 4
						val nearZ = if (sz > 0) cz + 4 else cz
						val farZ = if (sz > 0) cz else cz + 4
						for ((x, z, d) in listOf(Triple(nearX, nearZ, inner), Triple(farX, farZ, inner + 4))) {
							val a = BlockPos(x, -60, z + sz * d)
							val b = BlockPos(x + sx * d, -60, z)
							connect(maglev, a, net.minecraft.world.phys.Vec3(0.0, 0.0, -sz.toDouble()), b, net.minecraft.world.phys.Vec3(-sx.toDouble(), 0.0, 0.0), net.kernelpanicsoft.relativity.content.Maglev.MATERIAL)
							crossingEnds += a
							crossingEnds += b
						}
					}
				}
			}
			waitTicks(5)
			// (Again, now each end has its half: a client drops a curve whose other end hasn't got it yet.)
			server.runOnServer { minecraft ->
				for (p in listOf(BlockPos(0, -60, 6), BlockPos(4, -60, 6), BlockPos(6, -60, 13), BlockPos(10, -60, 13), BlockPos(20, -60, 0), BlockPos(26, -60, 7), BlockPos(14, -60, 7),
						BlockPos(40, -60, 0), BlockPos(44, -60, 0), BlockPos(48, -60, 8), BlockPos(32, -60, 8), BlockPos(52, -60, 8), BlockPos(36, -60, 8)) + crossingEnds)
					(minecraft.overworld().getBlockEntity(p) as? com.simibubi.create.content.trains.track.TrackBlockEntity)?.notifyUpdate()
			}
			waitTicks(20)
			command("tp @a 2.5 -57.5 -10.5 0 25")
			waitTicks(40)
			takeScreenshot("maglev_track")
			command("tp @a 3.5 -50.5 20.5 180 50")
			waitTicks(30)
			takeScreenshot("maglev_curve")
			command("tp @a -13.5 -52.5 1.5 0 50")
			waitTicks(30)
			takeScreenshot("maglev_crossings")
			command("tp @a -20.5 -44.5 13.5 0 55")
			waitTicks(30)
			takeScreenshot("maglev_crossings_diagonal")
			command("tp @a 20.5 -47.5 3.5 0 90")
			waitTicks(30)
			takeScreenshot("maglev_junction")
			command("tp @a 20.5 -56.5 -3.5 0 35")
			waitTicks(30)
			takeScreenshot("maglev_junction_low")
			command("tp @a 2.6 -58.3 -5.4 45 20")
			waitTicks(30)
			takeScreenshot("maglev_bogey")
			command("tp @a 18.6 -55.5 1.4 0 90")
			waitTicks(30)
			takeScreenshot("maglev_close_v")
			command("tp @a -36.2 -57.2 25.6 0 90")
			waitTicks(30)
			takeScreenshot("maglev_close_acute")
			command("tp @a -3.5 -58.6 -9.5 0 5")
			waitTicks(30)
			takeScreenshot("maglev_profile")
			command("tp @a 42.5 -42.5 4.5 0 90")
			waitTicks(30)
			takeScreenshot("maglev_two_lane_junction")
			command("tp @a 42.5 -55.5 -6.5 0 35")
			waitTicks(30)
			takeScreenshot("maglev_two_lane_junction_low")
			command("tp @a 82.5 -38.5 62.5 0 90")
			waitTicks(30)
			takeScreenshot("maglev_crossing_regular")
			command("tp @a 132.5 -30.5 62.5 0 90")
			waitTicks(30)
			takeScreenshot("maglev_crossing_wide")
			command("tp @a 82.5 -52.5 48.5 0 40")
			waitTicks(30)
			takeScreenshot("maglev_crossing_regular_low")
		}
	}

	/**
	 * A train assembled on maglev track (maglev bogeys) beside one on Create's track, as a player assembles them: a
	 * station, two bogeys from a train casing's anchor, casing on top. Screenshots; logs the carriages.
	 */
	@ClientGameTest
	fun ClientGameTestContext.maglevTrain() {
		if (!ClientTestInput.selected("maglevTrain")) return
		runOnClient { minecraft ->
			minecraft.options.hideGui = true
			SpacetimeDebugRenderer.hidden = true
			minecraft.options.bobView().set(false)
		}
		withTestWorld {
			waitTicks(40)
			for (command in listOf("gamemode spectator @a", "time set noon", "gamerule doDaylightCycle false", "relativity bh clear")) command(command)
			val tracks = listOf(
				60 to net.kernelpanicsoft.relativity.content.RelativityBlocks.KRASNIKOV_TRACK.get().defaultBlockState(),
				66 to net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("create:track")).defaultBlockState(),
			)
			server.runOnServer { minecraft ->
				val level = minecraft.overworld()
				// (The maglev line is Krasnikov track, on to a second station 100 blocks north.)
				for ((x, track) in tracks) for (z in (if (x == 60) -110 else -20)..20) level.setBlock(BlockPos(x, -60, z), track.setValue(com.simibubi.create.content.trains.track.TrackBlock.SHAPE, com.simibubi.create.content.trains.track.TrackShape.ZO), 3)
			}
			// (Midway up the line: both stations within ticking distance.)
			command("tp @a 63.5 -55 -50.5")
			waitTicks(20)
			// Stations: selected on the track, placed beside it.
			server.runOnServer { minecraft ->
				val level = minecraft.overworld()
				val player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level)
				for ((x, z) in tracks.map { it.first to 0 } + listOf(60 to -100)) {
					val stack = net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse("create:track_station")))
					player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack)
					// (Facing +z; the far station -z, the way the train arrives.)
					if (z == 0) player.moveTo(x + 0.5, -59.0, z - 2.5, 0f, 30f) else player.moveTo(x + 0.5, -59.0, z + 2.5, 180f, 30f)
					val track = BlockPos(x, -60, z)
					stack.useOn(net.minecraft.world.item.context.UseOnContext(player, net.minecraft.world.InteractionHand.MAIN_HAND,
						net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(track), net.minecraft.core.Direction.UP, track, false)))
					val ground = BlockPos(x + 2, -61, z)
					stack.useOn(net.minecraft.world.item.context.UseOnContext(player, net.minecraft.world.InteractionHand.MAIN_HAND,
						net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(ground).add(0.0, 0.5, 0.0), net.minecraft.core.Direction.UP, ground, false)))
					CreateRelativity.LOGGER.info("Train test: station at x {}: {}", x + 2, level.getBlockState(ground.above()))
				}
			}
			waitTicks(40)
			server.runOnServer { minecraft ->
				val level = minecraft.overworld()
				val player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level)
				for ((x, track) in tracks) {
					val station = level.getBlockEntity(BlockPos(x + 2, -60, 0)) as? com.simibubi.create.content.trains.station.StationBlockEntity
					if (station == null) { CreateRelativity.LOGGER.info("Train test: no station at x {}", x + 2); continue }
					val entered = station.enterAssemblyMode(minecraft.playerList.players.first())
					val dir = station.assemblyDirection
					CreateRelativity.LOGGER.info("Train test: x {} edge point {}, assembly {}, direction {}", x, station.station, entered, dir)
					// Bogeys at the station's marker (the frontmost must be: a block along from its track) and 5 blocks on,
					// casing over them, controls in front.
					for (k in listOf(1, 6)) {
						val at = BlockPos(x, -60, 0).relative(dir, k)
						val anchor = (track.block as com.simibubi.create.content.trains.track.TrackBlock).getBogeyAnchor(level, at, level.getBlockState(at))
						level.setBlock(at.above(), anchor, 3)
					}
					for (k in 1..6) level.setBlock(BlockPos(x, -58, 0).relative(dir, k), net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("create:railway_casing")).defaultBlockState(), 3)
					val controls = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("create:controls")).defaultBlockState()
					level.setBlock(BlockPos(x, -57, 0).relative(dir, 1), controls.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, dir), 3)
					// Glued together, as a player glues a train (only what's on a bogey sticks to it unglued).
					val from = BlockPos(x, -59, 0).relative(dir, 1)
					val to = BlockPos(x, -57, 0).relative(dir, 6)
					level.addFreshEntity(com.simibubi.create.content.contraptions.glue.SuperGlueEntity(level, net.minecraft.world.phys.AABB.encapsulatingFullBlocks(from, to)))
				}
			}
			waitTicks(10)
			server.runOnServer { minecraft ->
				val level = minecraft.overworld()
				val player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level)
				for ((x, _) in tracks) {
					val station = level.getBlockEntity(BlockPos(x + 2, -60, 0)) as? com.simibubi.create.content.trains.station.StationBlockEntity ?: continue
					station.refreshAssemblyInfo()
					fun field(name: String) = com.simibubi.create.content.trains.station.StationBlockEntity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(station)
					CreateRelativity.LOGGER.info("Train test: x {} bogeys {} at {}, length {}", x, field("bogeyCount"), (field("bogeyLocations") as IntArray?)?.toList(), field("assemblyLength"))
					station.assemble(minecraft.playerList.players.first().uuid)
					CreateRelativity.LOGGER.info("Train test: x {} assembly failed: {}", x, (field("lastException") as? com.simibubi.create.content.contraptions.AssemblyException)?.component?.string)
				}
			}
			waitTicks(40)
			server.runOnServer { minecraft ->
				val carriages = minecraft.overworld().getEntities(com.simibubi.create.AllEntityTypes.CARRIAGE_CONTRAPTION.get()) { true }
				CreateRelativity.LOGGER.info("Train test: {} carriage(s) at {}", carriages.size, carriages.map { it.position() })
				// The maglev train (wholly on maglev bogeys) is faster and has less friction than Create's, by the config.
				val trains = com.simibubi.create.Create.RAILWAYS.trains.values
				val maglev = trains.single { net.kernelpanicsoft.relativity.content.MaglevBogey.isMaglev(it) }
				val standard = trains.single { !net.kernelpanicsoft.relativity.content.MaglevBogey.isMaglev(it) }
				val config = net.kernelpanicsoft.relativity.config.RelativityConfig.Server.Trains
				for ((what, ratio, expected) in listOf(
					Triple("top speed", (maglev.maxSpeed() / standard.maxSpeed()).toDouble(), config.maglevTopSpeed),
					Triple("turning speed", (maglev.maxTurnSpeed() / standard.maxTurnSpeed()).toDouble(), config.maglevTurningSpeed),
					Triple("acceleration", (maglev.acceleration() / standard.acceleration()).toDouble(), config.maglevAcceleration),
				)) {
					CreateRelativity.LOGGER.info("Train test: maglev {} x{}", what, "%.2f".format(ratio))
					assertTrue(kotlin.math.abs(ratio - expected) < 1e-3) { "Maglev $what is x$ratio Create's, expected x$expected" }
				}
				// Standing, no tube.
				assertTrue(net.kernelpanicsoft.relativity.content.KrasnikovTubes.tubes(minecraft.overworld()).isEmpty()) { "A Krasnikov tube with no train under way" }
			}
			// Sent to the station up the Krasnikov line (once its edge point is up: its block entity makes it in a tick).
			var sent = false
			for (attempt in 0 until 40) {
				server.runOnServer { minecraft ->
					val station = (minecraft.overworld().getBlockEntity(BlockPos(62, -60, -100)) as com.simibubi.create.content.trains.station.StationBlockEntity).station ?: return@runOnServer
					val maglev = com.simibubi.create.Create.RAILWAYS.trains.values.single { net.kernelpanicsoft.relativity.content.MaglevBogey.isMaglev(it) }
					val path = maglev.navigation.findPathTo(station, Double.MAX_VALUE)
					assertTrue(path != null) { "No route to the far station" }
					maglev.navigation.startNavigation(path)
					// (A block on its route taken at once, before it can be held: it must wait.)
					val blocks = net.kernelpanicsoft.relativity.content.WarpReservations.routeBlocks(maglev)
					assertTrue(blocks.isNotEmpty()) { "The warp train's route has no signal blocks" }
					net.kernelpanicsoft.relativity.content.WarpReservations.blockedForTest += blocks.last()
					sent = true
				}
				if (sent) break
				waitTicks(5)
			}
			assertTrue(sent) { "The far station never got its edge point" }
			// A warp train holds its whole route before it sets off: with a block on it taken, it waits at its station.
			waitTicks(40)
			server.runOnServer { minecraft ->
				val maglev = com.simibubi.create.Create.RAILWAYS.trains.values.single { net.kernelpanicsoft.relativity.content.MaglevBogey.isMaglev(it) }
				CreateRelativity.LOGGER.info("Train test: route blocked: cleared {}, at station {}, speed {}", net.kernelpanicsoft.relativity.content.WarpReservations.isCleared(maglev), maglev.getCurrentStation() != null, maglev.speed)
				assertTrue(!net.kernelpanicsoft.relativity.content.WarpReservations.isCleared(maglev) && maglev.getCurrentStation() != null && maglev.speed == 0.0) { "The warp train set off with its route blocked" }
				net.kernelpanicsoft.relativity.content.WarpReservations.blockedForTest.clear()
			}
			waitTicks(5)
			server.runOnServer { minecraft ->
				val maglev = com.simibubi.create.Create.RAILWAYS.trains.values.single { net.kernelpanicsoft.relativity.content.MaglevBogey.isMaglev(it) }
				assertTrue(net.kernelpanicsoft.relativity.content.WarpReservations.isCleared(maglev)) { "The warp train's route wasn't held once free" }
				val groups = com.simibubi.create.Create.RAILWAYS.signalEdgeGroups
				val held = net.kernelpanicsoft.relativity.content.WarpReservations.routeBlocks(maglev).count { id -> groups[id]?.let { net.kernelpanicsoft.relativity.content.WarpReservations.holds(it, maglev) } == true }
				CreateRelativity.LOGGER.info("Train test: route held: {} blocks", held)
			}
			// Its tube forms: its front running out at c, its tilt easing in over a second; on it, the train may run at up
			// to 90% of the tube's light speed.
			// (It formed while the train waited: on its way at once, and soon there; checked each tick, on the first it's
			// under way, its tube formed, short of its station: the server runs on unevenly beside the client's ticks.)
			var checked = false
			var last = ""
			for (attempt in 0 until 200) {
				server.runOnServer { minecraft ->
					val level = minecraft.overworld()
					val maglev = com.simibubi.create.Create.RAILWAYS.trains.values.single { net.kernelpanicsoft.relativity.content.MaglevBogey.isMaglev(it) }
					val tubes = net.kernelpanicsoft.relativity.content.KrasnikovTubes.tubes(level)
					val k = net.kernelpanicsoft.relativity.content.KrasnikovTubes.k()
					val left = maglev.navigation.distanceToDestination
					last = "tick ${level.gameTime}: tubes ${tubes.map { "%.3f".format(it.k) }}, train speed ${maglev.speed}, $left blocks to go"
					if (maglev.speed == 0.0 || left < 15.0 || tubes.size != 1 || kotlin.math.abs(tubes.single().k - k) > 1e-9) return@runOnServer
					checked = true
					val tube = tubes.single()
					CreateRelativity.LOGGER.info("Train test: {}", last)
					// Along its route to the station (its front long past it).
					val length = tube.spine.zipWithNext().sumOf { (a, b) -> a.distance(b) }
					// (Along what's left of its route to the station: on the way at once, the train's well along it.)
					assertTrue(length > left) { "The tube runs $length blocks, the route ahead $left" }
					// Its tilt eases back to flat toward the station: full round the train, flat where it ends.
					CreateRelativity.LOGGER.info("Train test: tube tilts from {} to {}", "%.3f".format(tube.tilts.first()), "%.3f".format(tube.tilts.last()))
					assertTrue(kotlin.math.abs(tube.tilts.first() - k) < 1e-6 && tube.tilts.last() > 0.9) { "The tube's tilt runs ${tube.tilts.first()} to ${tube.tilts.last()}, expected $k round the train to flat at the station" }
					// Behind it too: the track it's covered since it set off (no signal passed on the way, so none of it dropped).
					val tail = net.kernelpanicsoft.relativity.content.KrasnikovTubes.tail(maglev)!!.getPosition(maglev.graph)
					val behind = tube.curve.first().a.distance(tail.x, tail.y + 1.0, tail.z)
					CreateRelativity.LOGGER.info("Train test: the tube starts {} blocks behind the train's tail", "%.1f".format(behind))
					assertTrue(behind > 5.0) { "The tube starts $behind blocks behind the train's tail: no trail behind it" }
					// Round the whole train, not just ahead of it: every bogey's point on the tube's spine.
					for (carriage in maglev.carriages) for (point in listOf(carriage.leadingPoint, carriage.trailingPoint)) {
						val p = point.getPosition(maglev.graph)
						val off = tube.distance(org.joml.Vector3d(p.x, p.y + 1.0, p.z))
						assertTrue(off < 1.0) { "A bogey of the train is $off blocks off its tube's spine" }
					}
					val top = 0.9 * net.kernelpanicsoft.relativity.config.RelativityConfig.speedOfLight(level.dimension()) / k / 20.0
					assertTrue(kotlin.math.abs(maglev.maxSpeed() / top - 1.0) < 1e-3) { "In its tube, the train's top speed is ${maglev.maxSpeed()}, expected $top" }
				}
				if (checked) break
				waitTicks(1)
			}
			assertTrue(checked) { "The warp train was never under way in its formed tube short of its station; last $last" }
			// The client has it too (its copy of the train knows no route: the server sends it), so the lens draws it,
			// round the client's own train (where it's drawn), not the server's: every bogey on the spine.
			var offs = emptyList<Double>()
			var clientK = emptyList<Double>()
			for (attempt in 0 until 40) {
				val seen = computeOnClient { minecraft ->
					val tubes = net.kernelpanicsoft.relativity.content.KrasnikovTubes.tubes(minecraft.level!!)
					val train = com.simibubi.create.CreateClient.RAILWAYS.trains.values.single { net.kernelpanicsoft.relativity.content.MaglevBogey.isMaglev(it) }
					tubes.map { it.k } to (tubes.singleOrNull()?.let { tube ->
						train.carriages.flatMap { listOf(it.leadingPoint, it.trailingPoint) }.map { point ->
							val p = point.getPosition(train.graph)
							tube.distance(org.joml.Vector3d(p.x, p.y + 1.0, p.z))
						}
					} ?: emptyList())
				}
				clientK = seen.first
				offs = seen.second
				if (clientK.size == 1 && kotlin.math.abs(clientK.single() - net.kernelpanicsoft.relativity.content.KrasnikovTubes.k()) < 1e-6) break
				waitTicks(1)
			}
			CreateRelativity.LOGGER.info("Train test: the client's tubes {}, k {}; its bogeys off its tube's spine {}", clientK.size, clientK.map { "%.3f".format(it) }, offs.map { "%.2f".format(it) })
			assertTrue(clientK.size == 1 && kotlin.math.abs(clientK.single() - net.kernelpanicsoft.relativity.content.KrasnikovTubes.k()) < 1e-6) { "The client has Krasnikov tubes of k $clientK, expected the routed train's" }
			assertTrue(offs.isNotEmpty() && offs.all { it < 1.0 }) { "The client's train's bogeys are $offs blocks off its tube's spine" }
			// The tube from beside, round the train: the ground and the train through its wall.
			val trainZ = computeOnClient { _ -> com.simibubi.create.CreateClient.RAILWAYS.trains.values.single { net.kernelpanicsoft.relativity.content.MaglevBogey.isMaglev(it) }.carriages.first().leadingPoint.getPosition(null).z }
			// (Ahead of it, where its tube has formed along the route: it runs on fast, toward -z.)
			command("tp @a 67.5 -56.5 ${"%.1f".format(trainZ - 60.0)} 90 15")
			waitTicks(5)
			takeScreenshot("maglev_train_tube")
			// The client's copy keeps up (WarpTrainSync): stopped at the station, its wheels where the server's are, a tick
			// or two on (Create's own sync, run after every few ticks, overshot by many blocks and took 30 ticks to settle).
			var stopped = false
			for (attempt in 0 until 100) {
				server.runOnServer { _ ->
					val maglev = com.simibubi.create.Create.RAILWAYS.trains.values.single { net.kernelpanicsoft.relativity.content.MaglevBogey.isMaglev(it) }
					stopped = maglev.speed == 0.0 && maglev.navigation.destination == null
				}
				if (stopped) break
				waitTicks(1)
			}
			assertTrue(stopped) { "The warp train never stopped at its station" }
			waitTicks(3)
			var serverWheels = emptyList<net.minecraft.world.phys.Vec3>()
			server.runOnServer { _ ->
				val maglev = com.simibubi.create.Create.RAILWAYS.trains.values.single { net.kernelpanicsoft.relativity.content.MaglevBogey.isMaglev(it) }
				serverWheels = maglev.carriages.flatMap { listOf(it.leadingPoint, it.trailingPoint) }.map { it.getPosition(maglev.graph) }
			}
			val clientWheels = computeOnClient { _ ->
				val train = com.simibubi.create.CreateClient.RAILWAYS.trains.values.single { net.kernelpanicsoft.relativity.content.MaglevBogey.isMaglev(it) }
				train.carriages.flatMap { listOf(it.leadingPoint, it.trailingPoint) }.map { it.getPosition(train.graph) }
			}
			val apart = serverWheels.zip(clientWheels).map { (a, b) -> a.distanceTo(b) }
			CreateRelativity.LOGGER.info("Train test: stopped, the client's wheels off the server's {}", apart.map { "%.2f".format(it) })
			assertTrue(apart.size == serverWheels.size && apart.all { it < 0.5 }) { "Stopped, the client's train's wheels are $apart blocks off the server's" }
			// The line is one Krasnikov network, one warp factor: set with a wrench at one end (the board let go on warp
			// 4.5: KrasnikovWarpControl), the whole line has it.
			server.runOnServer { minecraft ->
				val level = minecraft.overworld()
				val player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level)
				player.moveTo(60.5, -59.0, -88.5, 0f, 30f)
				net.kernelpanicsoft.relativity.content.KrasnikovWarpControl.set(player, BlockPos(60, -60, -90), 45)
				val network = net.kernelpanicsoft.relativity.content.KrasnikovNetworks.tenthsAt(level, BlockPos(60, -60, 10))
				CreateRelativity.LOGGER.info("Train test: the network's warp set to 4.5 at one end: {} at the other", network)
				assertTrue(network == 45) { "Set to warp 4.5 at one end, the line's other end has $network" }
			}
			// Laying a curve as a player does: maglev track in hand, a start selected on the maglev, looking at maglev track
			// off to the side (Create's preview of the curve), then at Create's track (refused).
			command("gamemode creative @a")
			server.runOnServer { minecraft ->
				val level = minecraft.overworld()
				level.setBlock(BlockPos(54, -60, 20), net.kernelpanicsoft.relativity.content.RelativityBlocks.MAGLEV_TRACK.get().defaultBlockState()
					.setValue(com.simibubi.create.content.trains.track.TrackBlock.SHAPE, com.simibubi.create.content.trains.track.TrackShape.XO), 3)
				val player = minecraft.playerList.players.first()
				val stack = net.minecraft.world.item.ItemStack(net.kernelpanicsoft.relativity.content.RelativityBlocks.MAGLEV_TRACK_ITEM.get(), 64)
				player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack)
				player.teleportTo(60.5, -59.0, 12.5)
				player.setYRot(0f)
				player.setXRot(60f)
				val start = BlockPos(60, -60, 14)
				stack.useOn(net.minecraft.world.item.context.UseOnContext(player, net.minecraft.world.InteractionHand.MAIN_HAND,
					net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(start), net.minecraft.core.Direction.UP, start, false)))
				CreateRelativity.LOGGER.info("Train test: start selected: {}", stack.has(com.simibubi.create.AllDataComponents.TRACK_CONNECTING_FROM))
			}
			runOnClient { minecraft -> minecraft.options.hideGui = false }
			// Eye 1.62 above the feet: aim at the middle of the target's top.
			fun lookAt(fx: Double, fy: Double, fz: Double, tx: Double, ty: Double, tz: Double): String {
				val dx = tx - fx
				val dy = ty - (fy + 1.62)
				val dz = tz - fz
				val yaw = Math.toDegrees(Math.atan2(-dx, dz))
				val pitch = Math.toDegrees(-Math.atan2(dy, Math.hypot(dx, dz)))
				return "tp @a $fx $fy $fz ${"%.2f".format(yaw)} ${"%.2f".format(pitch)}"
			}
			command(lookAt(56.5, -58.0, 17.5, 54.5, -59.9, 20.5))
			waitTicks(30)
			takeScreenshot("maglev_placing")
			command(lookAt(64.5, -58.0, 16.5, 66.5, -59.9, 18.5))
			waitTicks(30)
			takeScreenshot("maglev_placing_refused")
			// Laying it, as the player's client does (the second click on the ground where the curve ends, facing the
			// way it leaves: -x).
			command(lookAt(54.5, -59.0, 22.5, 52.5, -60.0, 22.5))
			waitTicks(5)
			runOnClient { minecraft ->
				val player = minecraft.player!!
				val ground = BlockPos(52, -61, 22)
				player.setYRot(90f)
				val result = minecraft.gameMode!!.useItemOn(player, net.minecraft.world.InteractionHand.MAIN_HAND,
					net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(ground).add(0.0, 0.5, 0.0), net.minecraft.core.Direction.UP, ground, false))
				CreateRelativity.LOGGER.info("Train test: client laid the curve: {}", result)
			}
			waitTicks(20)
			server.runOnServer { minecraft ->
				val entity = minecraft.overworld().getBlockEntity(BlockPos(60, -60, 14)) as? com.simibubi.create.content.trains.track.TrackBlockEntity
				CreateRelativity.LOGGER.info("Train test: server curve {}, end {}", entity?.connections?.keys, minecraft.overworld().getBlockState(BlockPos(52, -60, 22)))
			}
			command(lookAt(62.5, -53.0, 27.5, 56.0, -60.0, 18.0))
			waitTicks(30)
			takeScreenshot("maglev_laid")
			runOnClient { minecraft -> minecraft.options.hideGui = true }
			command("gamemode spectator @a")
			command("tp @a 72.5 -56.5 -3.5 90 20")
			waitTicks(60)
			runOnClient { minecraft ->
				val carriages = minecraft.level!!.entitiesForRendering().filterIsInstance<com.simibubi.create.content.trains.entity.CarriageContraptionEntity>()
				CreateRelativity.LOGGER.info("Train test: client has {} carriage(s): {}", carriages.size, carriages.map { "${it.position()} contraption ${it.contraption?.blocks?.size} carriage ${it.carriage != null}" })
			}
			takeScreenshot("maglev_train")
			command("tp @a 63.5 -58.2 -3.5 90 10")
			waitTicks(30)
			takeScreenshot("maglev_train_close")
		}
	}

	/** A warp drive formed, its value box looked at (screenshot, nothing asserted). */
	@ClientGameTest
	fun ClientGameTestContext.warpDrive() {
		if (!ClientTestInput.selected("warpDrive")) return
		runOnClient { minecraft ->
			SpacetimeDebugRenderer.hidden = true
			minecraft.options.bobView().set(false)
		}
		withTestWorld {
			waitTicks(40)
			for (command in listOf("gamemode creative @a", "time set noon", "gamerule doDaylightCycle false", "relativity bh clear")) command(command)
			server.runOnServer { minecraft ->
				val level = minecraft.overworld()
				val core = BlockPos(0, -59, 5)
				level.setBlock(core, net.kernelpanicsoft.relativity.content.RelativityBlocks.WARP_CORE.get().defaultBlockState()
					.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, net.minecraft.core.Direction.NORTH), 3)
				for (pos in net.kernelpanicsoft.relativity.content.WarpDrive.shell(core)) level.setBlock(pos, net.kernelpanicsoft.relativity.content.RelativityBlocks.WARP_COIL.get().defaultBlockState(), 3)
				// A loose core and coil beside it, unformed.
				level.setBlock(BlockPos(4, -60, 5), net.kernelpanicsoft.relativity.content.RelativityBlocks.WARP_CORE.get().defaultBlockState()
					.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, net.minecraft.core.Direction.NORTH), 3)
				level.setBlock(BlockPos(5, -60, 5), net.kernelpanicsoft.relativity.content.RelativityBlocks.WARP_COIL.get().defaultBlockState(), 3)
			}
			waitTicks(40)
			command("tp @a 1.0 -60.0 1.5 15 12")
			waitTicks(30)
			takeScreenshot("warp_drive")
		}
	}

	/**
	 * The lens's fields (LensFields, the GLSL in lens_fields): the tube registered as a field type, its field where it should
	 * be (inside: its tilt; far out: flat), and its force on light in closed form against differences of H. A bent tube,
	 * points across its wall; near its joint the axis's turning is only approximately differentiated (logged).
	 */
	@ClientGameTest
	fun ClientGameTestContext.lensFields() {
		if (!ClientTestInput.selected("lensFields")) return
		for (type in listOf("hole", "mass", "tube", "warp_tube", "bubble")) {
			assertTrue(net.kernelpanicsoft.relativity.lens.RelativityRegistries.LENS_FIELD_TYPES.contains(CreateRelativity.id(type))) { "$type isn't a registered lens field type" }
		}
		val k = 0.05
		val spine = listOf(org.joml.Vector3d(-60.0, -58.5, 0.5), org.joml.Vector3d(8.0, -58.5, 0.5), org.joml.Vector3d(48.0, -58.5, 40.5))
		val tube = net.kernelpanicsoft.relativity.spacetime.FieldSource.Krasnikov.through(spine, k, 3.0)
		val provider = net.kernelpanicsoft.relativity.spacetime.FieldSource.Provider { level ->
			if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) emptyList() else listOf(tube)
		}
		withTestWorld {
			while (atLength { it.level == null || it.player == null }) Thread.sleep(500)
			repeat(3) { settleClient("Lens fields, world") }
			command("gamemode spectator @a")
			command("tp @a -10.5 -50 -12.5 0 20")
			runOnClient { VertexLens.fields = true }
			SpacetimeField.addProvider(provider)
			try {
				waitTicks(20)
				val (eye, rotation) = computeOnClient { minecraft ->
					val camera = minecraft.gameRenderer.mainCamera
					camera.position to org.joml.Quaternionf(camera.rotation()).conjugate()
				}
				// Points across the wall (rho 0 to 6) round the straight piece and round the joint (x 8).
				class Probe(val world: org.joml.Vector3d, val rho: Double, val nearJoint: Boolean)
				val probes = ArrayList<Probe>()
				for (x in listOf(-40.0, -20.0, -5.0, 6.0, 8.0, 10.0)) for (rho in listOf(0.0, 1.0, 2.0, 2.7, 2.9, 3.0, 3.1, 3.3, 4.0, 6.0, 9.0)) for (angle in listOf(0.3, 2.0, 4.1)) {
					probes += Probe(org.joml.Vector3d(x, -58.5 + rho * kotlin.math.sin(angle), 0.5 + rho * kotlin.math.cos(angle)), rho, kotlin.math.abs(x - 8.0) < 4.0)
				}
				val views = probes.map { p -> rotation.transform(org.joml.Vector3f((p.world.x - eye.x).toFloat(), (p.world.y - eye.y).toFloat(), (p.world.z - eye.z).toFloat())).let { floatArrayOf(it.x, it.y, it.z) } }
				val fields = atLength { LensCheck.run(views, VertexLens.lastProjection, field = true) }
				val forces = atLength { LensCheck.run(views, VertexLens.lastProjection, fieldForce = true) }
				val q = 1.0 / ((1.0 + k) * (1.0 + k))
				val insideA = -4.0 * k * q
				val failures = ArrayList<String>()
				val errors = ArrayList<Double>()
				val jointErrors = ArrayList<Double>()
				for ((i, p) in probes.withIndex()) {
					val f = fields[i]
					if (p.rho <= 1.0 && !p.nearJoint && (kotlin.math.abs(f[3] - 1f) > 0.01 || kotlin.math.abs(f[0] - insideA) > 0.01)) failures += "inside at ${p.world}: A ${f[0]}, weight ${f[3]}"
					if (p.rho >= 6.0 && !p.nearJoint && (f[3] > 1e-3 || kotlin.math.abs(f[0] + 1f) > 1e-3)) failures += "outside at ${p.world}: A ${f[0]}, weight ${f[3]}"
					val force = forces[i]
					val size = kotlin.math.sqrt((force[0] * force[0] + force[1] * force[1] + force[2] * force[2]).toDouble())
					val relative = force[3] / (size + 1e-2)
					if (p.nearJoint) jointErrors += relative else errors += relative
				}
				errors.sort()
				jointErrors.sort()
				CreateRelativity.LOGGER.info("Lens fields: force in closed form against differences, off by (relative) median {}, 90% {}, max {}; near the joint median {}, max {}",
					"%.4f".format(errors[errors.size / 2]), "%.4f".format(errors[errors.size * 9 / 10]), "%.4f".format(errors.last()),
					"%.4f".format(jointErrors[jointErrors.size / 2]), "%.4f".format(jointErrors.last()))
				if (errors.last() > 0.05) failures += "the closed-form force is off by ${errors.last()} (relative) away from the joint"
				assertTrue(failures.isEmpty()) { "Lens fields: ${failures.take(6)}" }
			} finally {
				SpacetimeField.removeProvider(provider)
			}
			// The other types, each on its own: their force in closed form against differences round each, from near it
			// (a hole: half again its horizon's isotropic radius) out past its wall.
			class Case(val name: String, val source: net.kernelpanicsoft.relativity.spacetime.FieldSource, val centre: org.joml.Vector3d, val radii: List<Double>)
			val cases = listOf(
				Case("hole", net.kernelpanicsoft.relativity.spacetime.BlackHole(x = 20.0, y = -55.0, z = 30.0, schwarzschildRadius = 2.0), org.joml.Vector3d(20.0, -55.0, 30.0), listOf(0.75, 1.0, 1.5, 2.0, 3.0, 5.0, 10.0)),
				Case("mass", net.kernelpanicsoft.relativity.spacetime.FieldSource.Mass(org.joml.Vector3d(-25.0, -55.0, 30.0), 1.0, 1.0), org.joml.Vector3d(-25.0, -55.0, 30.0), listOf(0.0, 0.5, 1.0, 2.0, 4.0, 8.0)),
				Case("bubble", net.kernelpanicsoft.relativity.spacetime.FieldSource.Ellipsoid(org.joml.Vector3d(0.0, -55.0, 40.0),
					listOf(org.joml.Vector3d(6.0, 0.0, 0.0), org.joml.Vector3d(0.0, 3.0, 0.0), org.joml.Vector3d(0.0, 0.0, 4.0)), org.joml.Vector3d(1.0, 0.0, 0.0), 1.5),
					org.joml.Vector3d(0.0, -55.0, 40.0), listOf(0.0, 1.0, 2.5, 2.9, 3.0, 3.1, 3.5, 4.0, 5.0, 7.0)),
				Case("bubble, compact", net.kernelpanicsoft.relativity.spacetime.FieldSource.Ellipsoid(org.joml.Vector3d(0.0, -55.0, 40.0),
					listOf(org.joml.Vector3d(6.0, 0.0, 0.0), org.joml.Vector3d(0.0, 3.0, 0.0), org.joml.Vector3d(0.0, 0.0, 4.0)), org.joml.Vector3d(1.0, 0.0, 0.0), 1.5, 0.6),
					org.joml.Vector3d(0.0, -55.0, 40.0), listOf(0.0, 1.0, 2.0, 2.5, 2.9, 3.5, 4.0)),
				Case("warp tube", net.kernelpanicsoft.relativity.spacetime.FieldSource.Tube(listOf(org.joml.Vector3d(-40.0, -55.0, 20.0), org.joml.Vector3d(10.0, -55.0, 20.0)), 1.5, 3.0),
					org.joml.Vector3d(-15.0, -55.0, 20.0), listOf(0.0, 1.0, 2.0, 2.7, 3.0, 3.3, 4.0, 6.0)),
			)
			for (case in cases) {
				val only = net.kernelpanicsoft.relativity.spacetime.FieldSource.Provider { level ->
					if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) emptyList() else listOf(case.source)
				}
				SpacetimeField.addProvider(only)
				try {
					waitTicks(10)
					val (eye, rotation) = computeOnClient { minecraft ->
						val camera = minecraft.gameRenderer.mainCamera
						camera.position to org.joml.Quaternionf(camera.rotation()).conjugate()
					}
					val points = ArrayList<org.joml.Vector3d>()
					for (r in case.radii) for (angle in listOf(0.3, 1.4, 2.6, 4.1, 5.5)) for (lift in listOf(-0.6, 0.0, 0.5)) {
						points += org.joml.Vector3d(case.centre.x + r * kotlin.math.cos(angle) * kotlin.math.cos(lift), case.centre.y + r * kotlin.math.sin(lift), case.centre.z + r * kotlin.math.sin(angle) * kotlin.math.cos(lift))
					}
					val views = points.map { p -> rotation.transform(org.joml.Vector3f((p.x - eye.x).toFloat(), (p.y - eye.y).toFloat(), (p.z - eye.z).toFloat())).let { floatArrayOf(it.x, it.y, it.z) } }
					val fields = atLength { LensCheck.run(views, VertexLens.lastProjection, field = true) }
					val forces = atLength { LensCheck.run(views, VertexLens.lastProjection, fieldForce = true) }
					val errors = forces.map { f -> f[3] / (kotlin.math.sqrt((f[0] * f[0] + f[1] * f[1] + f[2] * f[2]).toDouble()) + 1e-2) }.sorted()
					val bent = fields.count { kotlin.math.abs(it[0] + 1f) > 1e-3 || it[3] > 1e-3 }
					CreateRelativity.LOGGER.info("Lens fields, {}: {} of {} points in its field; force in closed form against differences, off by (relative) median {}, 90% {}, max {}",
						case.name, bent, points.size, "%.4f".format(errors[errors.size / 2]), "%.4f".format(errors[errors.size * 9 / 10]), "%.4f".format(errors.last()))
					assertTrue(bent > points.size / 3) { "Lens fields, ${case.name}: only $bent of ${points.size} points in its field (not uploaded?)" }
					assertTrue(errors.last() < 0.05) { "Lens fields, ${case.name}: the closed-form force is off by ${errors.last()} (relative)" }
				} finally {
					SpacetimeField.removeProvider(only)
				}
			}
			runOnClient { VertexLens.fields = false }
		}
	}

	/**
	 * Frame rate with a long winding Krasnikov tube (as a warp train's, its whole route: many points, many segments):
	 * seen from above, beside and inside, with every point the lens takes and with fewer. Logs only (lens cost, #55).
	 */
	@ClientGameTest
	fun ClientGameTestContext.longTubeFps() {
		if (!ClientTestInput.selected("longTubeFps")) return
		settleClient("Long tube fps, start")
		runOnClient { minecraft ->
			minecraft.options.renderDistance().set(6)
			minecraft.options.hideGui = true
			SpacetimeDebugRenderer.hidden = true
			minecraft.options.bobView().set(false)
		}
		// A track-like curve: S-bends back and forth across the floor, each a cubic (as Create's turns).
		val pieces = ArrayList<net.kernelpanicsoft.relativity.spacetime.Cubic>()
		var x = -60.0
		for (i in 0 until 12) {
			val z0 = if (i % 2 == 0) -12.0 else 12.0
			val z1 = -z0
			pieces += net.kernelpanicsoft.relativity.spacetime.Cubic(
				org.joml.Vector3d(x, -58.5, z0 + 0.5), org.joml.Vector3d(x + 5.0, -58.5, z0 + 0.5),
				org.joml.Vector3d(x + 5.0, -58.5, z1 + 0.5), org.joml.Vector3d(x + 10.0, -58.5, z1 + 0.5),
			)
			x += 10.0
		}
		val tube = net.kernelpanicsoft.relativity.spacetime.FieldSource.Krasnikov(pieces, 0.05, 3.0)
		val provider = net.kernelpanicsoft.relativity.spacetime.FieldSource.Provider { level ->
			if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) emptyList() else listOf(tube)
		}
		withTestWorld {
			while (atLength { it.level == null || it.player == null }) Thread.sleep(500)
			repeat(5) { settleClient("Long tube fps, world") }
			waitTicks(60)
			for (command in listOf("gamemode spectator @a", "time set noon", "gamerule doDaylightCycle false", "relativity bh clear")) command(command)
			command("tick freeze")
			runOnClient { BlackHoleLens.timeGpu = true; VertexLens.timeLevel = true }
			fun ClientGameTestContext.fps(): Double {
				waitTicks(30)
				return (0 until 3).map { waitTicks(20); computeOnClient { it.fps }.toDouble() }.average()
			}
			try {
				for ((name, at) in listOf("above" to "0.5 -30 0.5 0 80", "beside" to "0.5 -54 -30.5 0 25", "inside" to "-55.0 -60.12 -11.5 -90 10")) {
					command("tp @a $at")
					runOnClient { it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO }
					val bare = fps()
					SpacetimeField.addProvider(provider)
					for (budget in listOf(VertexLens.MAX_TUBE_POINTS, 32, 16, 8)) {
						runOnClient { VertexLens.tubePointBudget = budget }
						val rate = fps()
						val points = computeOnClient { VertexLens.lastTubePoints }
						// (The post pass's own GPU time: sky through the tubes, per pixel.)
						val post = (0 until 10).map { waitTicks(1); computeOnClient { BlackHoleLens.gpuMillis } }.filter { it.isFinite() }.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }
						val level = computeOnClient { VertexLens.levelTimer.millis }
						CreateRelativity.LOGGER.info("Long tube fps, {}: {} fps with {} points (budget {}), post pass {} ms, level {} ms; {} without the tube", name, "%.0f".format(rate), points, budget, "%.1f".format(post), "%.1f".format(level), "%.0f".format(bare))
					}
					// (With the light table walked through the tube: the post pass's sky from it, not traced per pixel.)
					runOnClient { VertexLens.tubePointBudget = VertexLens.MAX_TUBE_POINTS; VertexLens.tubeTable = true }
					CreateRelativity.LOGGER.info("Long tube fps, {}: {} fps with the tube table", name, "%.0f".format(fps()))
					runOnClient { VertexLens.tubeTable = false }
					// (Where the cost is: the aim's traces, and each trace's events.)
					for ((traces, events) in listOf(4 to 16, 16 to 4, 4 to 4)) {
						runOnClient { VertexLens.tubePointBudget = VertexLens.MAX_TUBE_POINTS; VertexLens.tubeTraces = traces; VertexLens.tubeEvents = events }
						CreateRelativity.LOGGER.info("Long tube fps, {}: {} fps with {} traces of {} events", name, "%.0f".format(fps()), traces, events)
					}
					runOnClient { VertexLens.tubeTraces = 16; VertexLens.tubeEvents = VertexLens.TUBE_EVENTS }
					// (Where the cost is: without the pixel check, it's the vertices'.)
					runOnClient { VertexLens.tubePointBudget = VertexLens.MAX_TUBE_POINTS; VertexLens.pixelCheck = false }
					CreateRelativity.LOGGER.info("Long tube fps, {}: {} fps without the pixel check", name, "%.0f".format(fps()))
					runOnClient { VertexLens.pixelCheck = true }
					SpacetimeField.removeProvider(provider)
				}
				// The aim alone (LensCheck: the lens core in a bare program, no world shader round it) over the floor under
				// the tube, against what the level's drawing spends on its vertices: how much the world shader adds.
				command("tp @a 0.5 -30 0.5 0 80")
				runOnClient { it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO }
				SpacetimeField.addProvider(provider)
				waitTicks(40)
				val (eye, rotation) = computeOnClient { minecraft ->
					val camera = minecraft.gameRenderer.mainCamera
					camera.position to org.joml.Quaternionf(camera.rotation()).conjugate()
				}
				val floor = ArrayList<FloatArray>()
				for (i in 0 until 128) for (j in 0 until 128) {
					val x = -60.0 + 120.0 * i / 127.0
					val z = -15.0 + 31.0 * j / 127.0
					val v = rotation.transform(org.joml.Vector3f((x - eye.x).toFloat(), (-60.0 - eye.y).toFloat(), (z - eye.z).toFloat()))
					floor += floatArrayOf(v.x, v.y, v.z)
				}
				for (attempt in 0 until 4) {
					val (millis, placed) = atLength {
						org.lwjgl.opengl.GL11.glFinish()
						val start = System.nanoTime()
						val out = floor.chunked(1024).flatMap { LensCheck.run(it, VertexLens.lastProjection, tubes = true) }
						org.lwjgl.opengl.GL11.glFinish()
						(System.nanoTime() - start) / 1e6 to out.count { it[3] > 0f }
					}
					CreateRelativity.LOGGER.info("Long tube aim alone, above: {} floor points in {} ms ({} us a point), {} placed", floor.size, "%.1f".format(millis), "%.2f".format(millis * 1000 / floor.size), placed)
				}
				// (How many of them the aim searches for: their straight line crosses the tube.)
				val crossing = (0 until 128 * 128).count { n ->
					val x = -60.0 + 120.0 * (n / 128) / 127.0
					val z = -15.0 + 31.0 * (n % 128) / 127.0
					(1..63).any { k ->
						val f = k / 64.0
						tube.distance(org.joml.Vector3d(eye.x + (x - eye.x) * f, eye.y + (-60.0 - eye.y) * f, eye.z + (z - eye.z) * f)) < 3.0
					}
				}
				CreateRelativity.LOGGER.info("Long tube aim alone, above: {} of the {} floor points' lines cross the tube", crossing, floor.size)
				// The light table's images of them (built through the tube: its thin walls walked, then its field marched)
				// against the aim's: found, and how far off; and what building it costs.
				val exact = atLength { floor.chunked(1024).flatMap { LensCheck.run(it, VertexLens.lastProjection, tubes = true) } }
				for (march in listOf(false, true)) {
				runOnClient { VertexLens.tubeTable = true; VertexLens.fieldMarch = march; LightTable.timeBuild = true }
				waitTicks(20)
				val build = (0 until 10).map { waitTicks(1); computeOnClient { LightTable.buildTimer.millis } }.filter { it.isFinite() }.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }
				val fromTable = atLength { floor.chunked(1024).flatMap { LensCheck.run(it, VertexLens.lastProjection, tableImage = true) } }
				// (Marched: against the fields' own exact images, not the thin walls' aim.)
				val reference = if (march) atLength { floor.chunked(256).flatMap { LensCheck.run(it, VertexLens.lastProjection, fieldImage = true) } } else exact
				if (march) {
					// The fields' lens: the table finished by tracing where it fails or by a wall; what that costs.
					val pixelAngle = computeOnClient { minecraft -> 2.0 / (VertexLens.lastProjection.m11() * minecraft.window.height) }
					fun timed(run: () -> List<FloatArray>): Pair<Double, List<FloatArray>> = atLength<Pair<Double, List<FloatArray>>> { _ ->
						org.lwjgl.opengl.GL11.glFinish()
						val start = System.nanoTime()
						val out = run()
						org.lwjgl.opengl.GL11.glFinish()
						(System.nanoTime() - start) / 1e6 to out
					}
					val (tableMillis, _) = timed { floor.chunked(1024).flatMap { LensCheck.run(it, VertexLens.lastProjection, tableImage = true) } }
					val (lensMillis, lens) = timed { floor.chunked(1024).flatMap { LensCheck.run(it, VertexLens.lastProjection, fieldLens = true) } }
					val lensOffs = reference.zip(lens).filter { (e, t) -> e[3] > 0f && t[3] > 0f }.map { (e, t) ->
						val cos = (e[0] * t[0] + e[1] * t[1] + e[2] * t[2]) / (kotlin.math.sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2]) * kotlin.math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]))
						kotlin.math.acos(cos.coerceIn(-1f, 1f).toDouble()) / pixelAngle
					}.sorted()
					CreateRelativity.LOGGER.info("Long tube fields' lens, above: table alone {} ms, finished by tracing {} ms ({} us a point); {} by the table, {} traced, {} not found; exact {} found, lens {} ({} both, {} by the exact alone, {} by the lens alone); off the exact (px) median {}, 90% {}, 99% {}, max {}",
						"%.1f".format(tableMillis), "%.1f".format(lensMillis), "%.2f".format(lensMillis * 1000 / floor.size),
						lens.count { it[3] in 0.5f..1.5f }, lens.count { it[3] > 1.5f }, lens.count { it[3] < 0f },
						reference.count { it[3] > 0f }, lens.count { it[3] > 0f }, lensOffs.size,
						reference.zip(lens).count { (e, t) -> e[3] > 0f && t[3] <= 0f }, reference.zip(lens).count { (e, t) -> e[3] <= 0f && t[3] > 0f },
						"%.2f".format(lensOffs.getOrElse(lensOffs.size / 2) { Double.NaN }), "%.2f".format(lensOffs.getOrElse(lensOffs.size * 9 / 10) { Double.NaN }),
						"%.2f".format(lensOffs.getOrElse(lensOffs.size * 99 / 100) { Double.NaN }), "%.2f".format(lensOffs.lastOrNull() ?: Double.NaN))
					// Against physics-core's own tracer (CpuLensReference): a sample of the floor, every image the CPU finds from
					// the straight line, the table's answer and the fields' exact one; the table's answer against the nearest.
					val sample = floor.indices.filter { it % 73 == 0 }
					val truth = CpuLensReference(listOf(tube), org.joml.Vector3d(eye.x, eye.y, eye.z), org.joml.Quaternionf(rotation).conjugate())
					val started = System.nanoTime()
					val truths = sample.parallelStream().map { i ->
						truth.images(floor[i], listOf(fromTable[i], reference[i]).filter { it[3] > 0f }.map { floatArrayOf(it[0], it[1], it[2]) }, pixelAngle)
					}.toList()
					fun angle(a: FloatArray, b: FloatArray): Double {
						val cos = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (kotlin.math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]) * kotlin.math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]))
						return kotlin.math.acos(cos.coerceIn(-1f, 1f).toDouble()) / pixelAngle
					}
					fun judge(label: String, answers: List<FloatArray>) {
						val offs = sample.indices.filter { truths[it].isNotEmpty() && answers[sample[it]][3] > 0f }.map { k -> truths[k].minOf { t -> angle(t, answers[sample[k]]) } }.sorted()
						CreateRelativity.LOGGER.info("CPU truth, {}: {} of {} sampled points have images ({} with several); {} found ({} where the CPU finds none), {} missed; off the nearest image (px) median {}, 90% {}, 99% {}, max {}",
							label, truths.count { it.isNotEmpty() }, sample.size, truths.count { it.size > 1 },
							offs.size, sample.indices.count { truths[it].isEmpty() && answers[sample[it]][3] > 0f }, sample.indices.count { truths[it].isNotEmpty() && answers[sample[it]][3] <= 0f },
							"%.2f".format(offs.getOrElse(offs.size / 2) { Double.NaN }), "%.2f".format(offs.getOrElse(offs.size * 9 / 10) { Double.NaN }),
							"%.2f".format(offs.getOrElse(offs.size * 99 / 100) { Double.NaN }), "%.2f".format(offs.lastOrNull() ?: Double.NaN))
					}
					CreateRelativity.LOGGER.info("CPU truth: {} points in {} s", sample.size, "%.1f".format((System.nanoTime() - started) / 1e9))
					judge("table", fromTable)
					judge("fields' exact (GPU)", reference)
					judge("thin walls' aim", exact)
					// The lens's walls softer than the physics' (TubeField.lensSharpness): the table's image against the physics'.
					for (sharpness in listOf(4.0, 2.0)) {
						runOnClient { net.kernelpanicsoft.relativity.lens.TubeField.lensSharpness = sharpness }
						waitTicks(20)
						val softer = atLength { floor.chunked(1024).flatMap { LensCheck.run(it, VertexLens.lastProjection, tableImage = true) } }
						judge("table, walls' sharpness $sharpness", softer)
					}
					runOnClient { net.kernelpanicsoft.relativity.lens.TubeField.lensSharpness = 8.0 }
					waitTicks(20)
				}
				val pixel = computeOnClient { minecraft -> 2.0 / (VertexLens.lastProjection.m11() * minecraft.window.height) }
				val offs = reference.zip(fromTable).filter { (e, t) -> e[3] > 0f && t[3] > 0f }.map { (e, t) ->
					val c = (e[0] * t[0] + e[1] * t[1] + e[2] * t[2]) / (kotlin.math.sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2]) * kotlin.math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]))
					kotlin.math.acos(c.coerceIn(-1f, 1f).toDouble()) / pixel
				}.sorted()
				CreateRelativity.LOGGER.info("Long tube table, above, {}: built in {} ms; exact {} found, table {} found ({} both, {} by the exact alone); table off the exact (px) median {}, 90% {}, 99% {}",
					if (march) "field marched" else "thin walls walked", "%.1f".format(build),
					reference.count { it[3] > 0f }, fromTable.count { it[3] > 0f }, offs.size, reference.zip(fromTable).count { (e, t) -> e[3] > 0f && t[3] <= 0f },
					"%.2f".format(offs.getOrElse(offs.size / 2) { Double.NaN }), "%.2f".format(offs.getOrElse(offs.size * 9 / 10) { Double.NaN }), "%.2f".format(offs.getOrElse(offs.size * 99 / 100) { Double.NaN }))
				}
				runOnClient { VertexLens.tubeTable = false; VertexLens.fieldMarch = false; LightTable.timeBuild = false }
				SpacetimeField.removeProvider(provider)
				// As in the game: the tube changing every tick (a train's, rebuilt round it as it runs, its tilt easing in
				// and out), and something fast inside it (the train): frame times, not just the rate (stutter).
				fun ClientGameTestContext.frameTimes(label: String, ticks: Int, during: (Int) -> Unit = {}) {
					runOnClient { synchronized(VertexLens.frames) { VertexLens.frames.clear() }; VertexLens.recordFrames = true }
					for (t in 0 until ticks) { during(t); waitTicks(1) }
					val inOrder = computeOnClient { VertexLens.recordFrames = false; synchronized(VertexLens.frames) { VertexLens.frames.toList() } }
					// (Which frames were long, in order: one at the start is the switch; more later, stutter.)
					CreateRelativity.LOGGER.info("Long tube frames, above, {}: long frames {}", label, inOrder.withIndex().filter { it.value > 200.0 }.map { "#${it.index}: ${"%.0f".format(it.value)} ms" })
					val times = inOrder.sorted()
					CreateRelativity.LOGGER.info("Long tube frames, above, {}: {} frames, mean {} ms, 99% {} ms, worst {} ms", label, times.size,
						"%.1f".format(times.average()), "%.1f".format(times.getOrElse(times.size * 99 / 100) { Double.NaN }), "%.1f".format(times.lastOrNull() ?: Double.NaN))
				}
				command("tp @a 0.5 -30 0.5 0 80")
				runOnClient { it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO }
				SpacetimeField.addProvider(provider)
				waitTicks(40)
				frameTimes("static tube", 60)
				SpacetimeField.removeProvider(provider)
				command("tick unfreeze")
				var tick = 0
				val changing = net.kernelpanicsoft.relativity.spacetime.FieldSource.Provider { level ->
					if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) emptyList()
					else listOf(net.kernelpanicsoft.relativity.spacetime.FieldSource.Krasnikov(pieces, 0.05 + 0.001 * (tick % 10), 3.0))
				}
				SpacetimeField.addProvider(changing)
				waitTicks(20)
				frameTimes("changing tube", 60) { tick += 1 }
				runOnClient { VertexLens.tubeTable = true }
				frameTimes("changing tube, with the tube table", 60) { tick += 1 }
				runOnClient { VertexLens.tubeTable = false }
				// (A fast cart along it: 60 blocks a tick, as a warp train.)
				var cart: net.minecraft.world.entity.Entity? = null
				server.runOnServer { minecraft ->
					val level = minecraft.overworld()
					cart = net.minecraft.world.entity.EntityType.MINECART.create(level)!!.also { it.moveTo(-60.0, -60.0, -11.5); it.isNoGravity = true; level.addFreshEntity(it) }
				}
				waitTicks(10)
				frameTimes("changing tube, fast cart in it", 60) { t ->
					tick += 1
					server.runOnServer { _ ->
						val piece = pieces[(t / 2) % pieces.size]
						val at = piece.at(if (t % 2 == 0) 0.0 else 0.5)
						cart?.teleportTo(at.x, at.y - 1.5, at.z)
					}
				}
				SpacetimeField.removeProvider(changing)
				frameTimes("no tube, fast cart", 60) { t ->
					server.runOnServer { _ ->
						val at = pieces[(t / 2) % pieces.size].at(if (t % 2 == 0) 0.0 else 0.5)
						cart?.teleportTo(at.x, at.y - 1.5, at.z)
					}
				}
				server.runOnServer { _ -> cart?.discard() }
			} finally {
				SpacetimeField.removeProvider(provider)
				runOnClient { VertexLens.recordFrames = false; VertexLens.tubePointBudget = VertexLens.MAX_TUBE_POINTS; VertexLens.pixelCheck = true; VertexLens.tubeTable = false; VertexLens.fieldMarch = false; LightTable.timeBuild = false; BlackHoleLens.timeGpu = false; VertexLens.timeLevel = false }
				command("tick unfreeze")
			}
		}
	}

	/**
	 * A Krasnikov tube (20 c) along x over the ground test's checkerboard and pillars, the floor running through it, seen
	 * from inside (forward, back, aside) and from beside it: the shaders' tube aim against the CPU module's for a grid of
	 * points (the same algorithm: they must agree), and the picture against the CPU module's light per pixel. Both follow
	 * light arriving (the spine reversed). Fails on disagreement or a picture far off; logs fps.
	 */
	@ClientGameTest
	fun ClientGameTestContext.krasnikovLens() {
		if (!ClientTestInput.selected("krasnikovLens")) return
		settleClient("Krasnikov lens, start")
		runOnClient { minecraft ->
			minecraft.options.renderDistance().set(6)
			minecraft.options.hideGui = true
			SpacetimeDebugRenderer.hidden = true
			minecraft.options.bobView().set(false)
			minecraft.options.fovEffectScale().set(0.0)
		}
		val k = 0.05
		val radius = 3.0
		val straightSpine = listOf(org.joml.Vector3d(-60.0, -58.5, 0.5), org.joml.Vector3d(60.0, -58.5, 0.5))
		// (And bent: straight to x 8, then 45 degrees off toward +z, as track curves.)
		val bentSpine = listOf(org.joml.Vector3d(-60.0, -58.5, 0.5), org.joml.Vector3d(8.0, -58.5, 0.5), org.joml.Vector3d(48.0, -58.5, 40.5))
		var spine = straightSpine
		val provider = net.kernelpanicsoft.relativity.spacetime.FieldSource.Provider { level ->
			if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) emptyList()
			else listOf(net.kernelpanicsoft.relativity.spacetime.FieldSource.Krasnikov.through(spine, k, radius))
		}
		withTestWorld {
			// (The world's programs compile cold on a changed lens: long, the first frames.)
			while (atLength { it.level == null || it.player == null }) Thread.sleep(500)
			repeat(5) { settleClient("Krasnikov lens, world") }
			waitTicks(60)
			for (command in listOf(
				"gamemode spectator @a", "difficulty peaceful", "gamerule doMobSpawning false", "kill @e[type=!player]",
				"time set noon", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "weather clear", "relativity bh clear",
			)) command(command)
			server.runOnServer { minecraft ->
				val level = minecraft.overworld()
				for (x in -48..48) for (z in -48..48) {
					val block = if (Math.floorMod(Math.floorDiv(x, 2) + Math.floorDiv(z, 2), 2) == 0) Blocks.WHITE_CONCRETE else Blocks.BLUE_CONCRETE
					level.setBlock(BlockPos(x, -61, z), block.defaultBlockState(), 2)
				}
				for (x in -48..48 step 8) for (z in -48..48 step 8) {
					for (y in -60..-57) level.setBlock(BlockPos(x, y, z + 4), Blocks.RED_CONCRETE.defaultBlockState(), 2)
				}
			}
			command("tick freeze")
			SpacetimeField.addProvider(provider)
			val failures = ArrayList<String>()
			try {
				// (Eye 1.62 above the feet: inside, at the spine's height.)
				for ((name, at) in listOf(
					"inside_forward" to "0.5 -60.12 0.5 -90 10", "inside_back" to "0.5 -60.12 0.5 90 10",
					"inside_aside" to "0.5 -60.12 0.5 0 10", "beside" to "0.5 -54 -14.5 0 25", "bent_inside" to "-6.5 -60.12 0.5 -75 10",
				)) {
					spine = if (name.startsWith("bent")) bentSpine else straightSpine
					command("tp @a $at")
					runOnClient { it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO }
					settleClient("Krasnikov lens, $name")
					waitTicks(40)
					val composed = computeOnClient { VertexLens.composed }
					val (eye, rotation) = computeOnClient { minecraft ->
						val camera = minecraft.gameRenderer.mainCamera
						camera.position to org.joml.Quaternionf(camera.rotation()).conjugate()
					}
					// The CPU module as the shaders have it: relative to the eye, the spine reversed.
					val module = net.kernelpanicsoft.relativity.physics.lens.field.TubeModule(
						spine.asReversed().map { net.kernelpanicsoft.relativity.physics.lens.field.V3(it.x - eye.x, it.y - eye.y, it.z - eye.z) },
						net.kernelpanicsoft.relativity.physics.lens.field.TubeModule.Interior.Krasnikov(k), radius,
					)
					// Points: the floor (top), the pillars' faces and points inside the tube.
					val targets = ArrayList<net.minecraft.world.phys.Vec3>()
					for (x in -40..40 step 4) for (z in -40..40 step 4) targets += net.minecraft.world.phys.Vec3(x + 0.3, -60.0, z + 0.7)
					for (x in -40..40 step 3) for (dy in listOf(-2.0, 0.0, 2.0)) for (dz in listOf(-2.0, 0.0, 2.0)) targets += net.minecraft.world.phys.Vec3(x + 0.6, -58.5 + dy, 0.5 + dz)
					val views = targets.map { t -> rotation.transform(org.joml.Vector3f((t.x - eye.x).toFloat(), (t.y - eye.y).toFloat(), (t.z - eye.z).toFloat())).let { floatArrayOf(it.x, it.y, it.z) } }
					val gpu = views.chunked(1000).flatMap { chunk -> atLength { LensCheck.run(chunk, VertexLens.lastProjection, tubes = true) } }
					val pixel = computeOnClient { minecraft -> 2.0 / (VertexLens.lastProjection.m11() * minecraft.window.height) }
					var agree = 0
					var both = 0
					var onlyGpu = 0
					var onlyCpu = 0
					var hidden = 0
					val off = ArrayList<Double>()
					for ((i, t) in targets.withIndex()) {
						val target = net.kernelpanicsoft.relativity.physics.lens.field.V3(t.x - eye.x, t.y - eye.y, t.z - eye.z)
						val cpu = module.quickAim(net.kernelpanicsoft.relativity.physics.lens.field.V3.ZERO, target)
						val g = gpu[i]
						val found = g[3] > 0f
						when {
							cpu != null && found -> {
								both++
								val v = rotation.transform(org.joml.Vector3f(cpu.x.toFloat(), cpu.y.toFloat(), cpu.z.toFloat()))
								val angle = kotlin.math.acos((v.x * g[0] + v.y * g[1] + v.z * g[2]).toDouble().coerceIn(-1.0, 1.0)) / pixel
								off += angle
								if (angle < 2.0) agree++
								else if (off.count { it >= 2.0 } <= 3) CreateRelativity.LOGGER.info("Krasnikov lens, {}: {} off {} px (cpu {}, gpu {} {} {})", name, t, "%.1f".format(angle), cpu, g[0], g[1], g[2])
							}
							cpu != null -> onlyCpu++
							found -> onlyGpu++
							else -> hidden++
						}
					}
					off.sort()
					CreateRelativity.LOGGER.info("Krasnikov lens, {}{}: {} points, {} placed by both ({} within 2 px; median {} px, 95% {} px), {} by the CPU alone, {} by the GPU alone, {} hidden",
						name, if (composed) "" else " (NOT composed)", targets.size, both, agree, "%.2f".format(off.getOrElse(off.size / 2) { 0.0 }),
						"%.2f".format(off.getOrElse((off.size * 0.95).toInt().coerceAtMost(off.size - 1)) { 0.0 }), onlyCpu, onlyGpu, hidden)
					if (!composed) failures += "$name: not composed"
					if (both > 0 && agree < 0.95 * both) failures += "$name: GPU and CPU aims agree for $agree of $both"
					// (The GPU's aim stops at VertexLens.tubeTraces traces, the CPU's not: from outside, light grazing the tube's
					// silhouette or through it at length can take more, and the GPU leaves those hidden.)
					val lostAllowed = if (name.startsWith("inside")) 0.02 else 0.15
					if (onlyCpu + onlyGpu > lostAllowed * targets.size) failures += "$name: ${onlyCpu + onlyGpu} points placed by one of GPU and CPU only"
					// The picture against the CPU module's light.
					val columns = 120
					val rows = computeOnClient { columns * it.window.height / it.window.width }
					val reference = Array(rows) { arrayOfNulls<LensReference.Kind>(columns) }
					for (first in 0 until rows step 8) {
						val part = atLength { minecraft -> LensReference.traceTube(minecraft, module, columns, rows, 200.0, 60.0, first until minOf(first + 8, rows)) }
						for (row in first until minOf(first + 8, rows)) reference[row] = part[row]
					}
					val fps = computeOnClient { it.fps }
					val shot = takeScreenshot("krasnikov_$name")
					val wrong = LensReference.compare(reference, shot)
					CreateRelativity.LOGGER.info("Krasnikov lens, {}: {}% of pixels in the wrong class; {} fps", name, "%.1f".format(wrong * 100), fps)
					if (wrong > 0.06) failures += "$name: ${"%.1f".format(wrong * 100)}% of pixels in the wrong class"
					// The frame rate without the tube, for its cost.
					SpacetimeField.removeProvider(provider)
					waitTicks(40)
					val bare = computeOnClient { it.fps }
					SpacetimeField.addProvider(provider)
					waitTicks(40)
					CreateRelativity.LOGGER.info("Krasnikov lens, {}: {} fps with the tube, {} without", name, computeOnClient { it.fps }, bare)
				}
			} finally {
				SpacetimeField.removeProvider(provider)
				command("tick unfreeze")
			}
			if (failures.isNotEmpty()) throw AssertionError(failures.joinToString("; "))
		}
	}

	/**
	 * A warp bubble (warp 2, pushing +x) standing over the checkerboard and pillars, the floor through it, seen from inside
	 * (forward, back) and beside: the shaders' aim for a grid of points traced back through the CPU module (it must reach
	 * them), and the picture against the CPU module's light per pixel (both following light arriving: the push reversed).
	 */
	@ClientGameTest
	fun ClientGameTestContext.bubbleLens() {
		if (!ClientTestInput.selected("bubbleLens")) return
		settleClient("Bubble lens, start")
		runOnClient { minecraft ->
			minecraft.options.renderDistance().set(6)
			minecraft.options.hideGui = true
			SpacetimeDebugRenderer.hidden = true
			minecraft.options.bobView().set(false)
			minecraft.options.fovEffectScale().set(0.0)
		}
		val speed = net.kernelpanicsoft.relativity.physics.WarpScale.speed(2.0)
		val centre = org.joml.Vector3d(0.5, -58.0, 0.5)
		val axes = listOf(org.joml.Vector3d(6.0, 0.0, 0.0), org.joml.Vector3d(0.0, 4.0, 0.0), org.joml.Vector3d(0.0, 0.0, 5.0))
		val bubble = net.kernelpanicsoft.relativity.spacetime.FieldSource.Ellipsoid(centre, axes, org.joml.Vector3d(1.0, 0.0, 0.0), speed)
		val provider = net.kernelpanicsoft.relativity.spacetime.FieldSource.Provider { level ->
			if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) emptyList() else listOf(bubble)
		}
		withTestWorld {
			while (atLength { it.level == null || it.player == null }) Thread.sleep(500)
			repeat(5) { settleClient("Bubble lens, world") }
			waitTicks(40)
			for (command in listOf(
				"gamemode spectator @a", "difficulty peaceful", "gamerule doMobSpawning false", "kill @e[type=!player]",
				"time set noon", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "weather clear", "relativity bh clear",
			)) command(command)
			server.runOnServer { minecraft ->
				val level = minecraft.overworld()
				for (x in -48..48) for (z in -48..48) {
					val block = if (Math.floorMod(Math.floorDiv(x, 2) + Math.floorDiv(z, 2), 2) == 0) Blocks.WHITE_CONCRETE else Blocks.BLUE_CONCRETE
					level.setBlock(BlockPos(x, -61, z), block.defaultBlockState(), 2)
				}
				for (x in -48..48 step 8) for (z in -48..48 step 8) {
					for (y in -60..-57) level.setBlock(BlockPos(x, y, z + 4), Blocks.RED_CONCRETE.defaultBlockState(), 2)
				}
			}
			command("tick freeze")
			SpacetimeField.addProvider(provider)
			val failures = ArrayList<String>()
			try {
				for ((name, at) in listOf("inside_forward" to "0.5 -59.62 0.5 -90 10", "inside_back" to "0.5 -59.62 0.5 90 10", "beside" to "0.5 -54 -12.5 0 25")) {
					command("tp @a $at")
					runOnClient { it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO }
					repeat(3) { settleClient("Bubble lens, $name") }
					waitTicks(40)
					val composed = computeOnClient { VertexLens.composed }
					val (eye, rotation) = computeOnClient { minecraft ->
						val camera = minecraft.gameRenderer.mainCamera
						camera.position to org.joml.Quaternionf(camera.rotation()).conjugate()
					}
					val module = net.kernelpanicsoft.relativity.physics.lens.field.BubbleModule(
						net.kernelpanicsoft.relativity.physics.lens.field.V3(centre.x - eye.x, centre.y - eye.y, centre.z - eye.z),
						axes.map { net.kernelpanicsoft.relativity.physics.lens.field.V3(it.x, it.y, it.z) },
						net.kernelpanicsoft.relativity.physics.lens.field.V3(-1.0, 0.0, 0.0),
						net.kernelpanicsoft.relativity.physics.lens.field.TubeModule.Interior.Warp(speed),
					)
					val targets = ArrayList<net.minecraft.world.phys.Vec3>()
					for (x in -40..40 step 4) for (z in -40..40 step 4) targets += net.minecraft.world.phys.Vec3(x + 0.3, -60.0, z + 0.7)
					for (x in -5..5) for (dy in listOf(-1.5, 0.0, 1.5)) for (dz in listOf(-2.0, 0.0, 2.0)) targets += net.minecraft.world.phys.Vec3(x + 0.6, -58.0 + dy, 0.5 + dz)
					val views = targets.map { t -> rotation.transform(org.joml.Vector3f((t.x - eye.x).toFloat(), (t.y - eye.y).toFloat(), (t.z - eye.z).toFloat())).let { floatArrayOf(it.x, it.y, it.z) } }
					val gpu = views.chunked(1000).flatMap { chunk -> atLength { LensCheck.run(chunk, VertexLens.lastProjection, tubes = true) } }
					val pixel = computeOnClient { minecraft -> 2.0 / (VertexLens.lastProjection.m11() * minecraft.window.height) }
					val back = org.joml.Quaternionf(rotation).conjugate()
					var placed = 0
					var reaching = 0
					for ((i, t) in targets.withIndex()) {
						val g = gpu[i]
						if (g[3] <= 0f) continue
						placed++
						val d = back.transform(org.joml.Vector3f(g[0], g[1], g[2]))
						val target = net.kernelpanicsoft.relativity.physics.lens.field.V3(t.x - eye.x, t.y - eye.y, t.z - eye.z)
						val miss = module.missAt(net.kernelpanicsoft.relativity.physics.lens.field.V3.ZERO, net.kernelpanicsoft.relativity.physics.lens.field.V3(d.x.toDouble(), d.y.toDouble(), d.z.toDouble()), target)
						// (Within two pixels' angle at the point's distance.)
						if (miss != null && miss.length < 2.0 * pixel * target.length) reaching++
					}
					CreateRelativity.LOGGER.info("Bubble lens, {}{}: {} points, {} placed, {} of those reached by the CPU module's light", name, if (composed) "" else " (NOT composed)", targets.size, placed, reaching)
					if (!composed) failures += "$name: not composed"
					if (reaching < 0.95 * placed) failures += "$name: only $reaching of $placed placed points reached"
					val columns = 120
					val rows = computeOnClient { columns * it.window.height / it.window.width }
					val reference = Array(rows) { arrayOfNulls<LensReference.Kind>(columns) }
					for (first in 0 until rows step 8) {
						val part = atLength { minecraft -> LensReference.traceBubble(minecraft, module, columns, rows, 200.0, 60.0, first until minOf(first + 8, rows)) }
						for (row in first until minOf(first + 8, rows)) reference[row] = part[row]
					}
					val shot = takeScreenshot("bubble_$name")
					val wrong = LensReference.compare(reference, shot)
					CreateRelativity.LOGGER.info("Bubble lens, {}: {}% of pixels in the wrong class; {} fps", name, "%.1f".format(wrong * 100), computeOnClient { it.fps })
					if (wrong > 0.06) failures += "$name: ${"%.1f".format(wrong * 100)}% of pixels in the wrong class"
				}
			} finally {
				SpacetimeField.removeProvider(provider)
				command("tick unfreeze")
			}
			if (failures.isNotEmpty()) throw AssertionError(failures.joinToString("; "))
		}
	}

	/**
	 * F3 + Z: the spacetime grid comes up round a spinning hole, a mass, a warp bubble and a
	 * Krasnikov tube, its flow as each should have it (inward round the hole and turned its spin's way, none round the
	 * mass, along the bubble's push at its speed, along the tube), a screenshot of each; F3 + Z again hides it.
	 */
	@ClientGameTest
	fun ClientGameTestContext.spacetimeGrid() {
		if (!ClientTestInput.selected("spacetimeGrid")) return
		settleClient("Spacetime grid, start")
		runOnClient { minecraft ->
			minecraft.options.hideGui = true
			minecraft.options.bobView().set(false)
		}
		val speed = net.kernelpanicsoft.relativity.physics.WarpScale.speed(1.5)
		val sources = listOf(
			net.kernelpanicsoft.relativity.spacetime.FieldSource.Mass(org.joml.Vector3d(-40.5, -55.0, 0.5), 0.5, softening = 1.5),
			net.kernelpanicsoft.relativity.spacetime.FieldSource.Ellipsoid(org.joml.Vector3d(0.5, -56.0, 40.5),
				listOf(org.joml.Vector3d(6.0, 0.0, 0.0), org.joml.Vector3d(0.0, 4.0, 0.0), org.joml.Vector3d(0.0, 0.0, 5.0)), org.joml.Vector3d(1.0, 0.0, 0.0), speed),
			net.kernelpanicsoft.relativity.spacetime.FieldSource.Krasnikov.through(listOf(org.joml.Vector3d(30.0, -57.5, -40.5), org.joml.Vector3d(70.0, -57.5, -40.5)), 0.05, 3.0),
		)
		val provider = net.kernelpanicsoft.relativity.spacetime.FieldSource.Provider { level ->
			if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) emptyList() else sources
		}
		withTestWorld {
			while (atLength { it.level == null || it.player == null }) Thread.sleep(500)
			repeat(3) { settleClient("Spacetime grid, world") }
			waitTicks(20)
			for (command in listOf("gamemode spectator @a", "time set noon", "gamerule doDaylightCycle false", "relativity bh clear", "tick freeze")) command(command)
			command("relativity bh create 40.5 -55 0.5 2 0.9")
			SpacetimeField.addProvider(provider)
			// (Till the hole reaches the client.)
			waitTicks(20)
			try {
				// (F3 + Z as the game handles it: a real F3 can't be held down here, and the game asks GLFW whether it is.)
				fun press() = runOnClient { minecraft ->
					(minecraft.keyboardHandler as net.kernelpanicsoft.relativity.mixin.client.KeyboardHandlerInvoker).`create_relativity$handleDebugKeys`(org.lwjgl.glfw.GLFW.GLFW_KEY_Z)
				}
				press()
				assertTrue(computeOnClient { net.kernelpanicsoft.relativity.client.SpacetimeGrid.shown }) { "F3 + Z didn't show the grid" }
				// The flow, source by source.
				val c = computeOnClient { minecraft -> net.kernelpanicsoft.relativity.config.RelativityConfig.speedOfLight(minecraft.level!!.dimension()) }
				val flows = computeOnClient { minecraft ->
					val level = minecraft.level!!
					fun flow(x: Double, y: Double, z: Double) = SpacetimeField.flow(level, org.joml.Vector3d(x, y, z), org.joml.Vector3d())
					val pull = SpacetimeField.acceleration(level, org.joml.Vector3d(-36.5, -55.0, 0.5), org.joml.Vector3d(), org.joml.Vector3d())
					mapOf(
						"hole" to flow(46.5, -55.0, 0.5), "mass" to flow(-36.5, -55.0, 0.5), "mass pull" to pull,
						"bubble" to flow(0.5, -56.0, 40.5), "tube" to flow(50.0, -57.5, -40.5),
					)
				}
				CreateRelativity.LOGGER.info("Spacetime grid flows: {}", flows.entries.joinToString { (k, v) -> "$k (%.3f %.3f %.3f)".format(v.x, v.y, v.z) })
				val hole = flows.getValue("hole")
				assertTrue(hole.x < 0.0 && abs(hole.z) > 1e-3) { "Beside the spinning hole (+x of it) space flows ${hole}: expected inward (-x) and turned by its spin" }
				// (The hole's field reaches everywhere: a little of its inflow (toward +x) shows round the others.)
				assertTrue(flows.getValue("mass").length() < 0.05 && flows.getValue("mass pull").x < 0.0) { "Round the mass: flow ${flows["mass"]}, pull ${flows["mass pull"]}; expected next to none, and toward it" }
				val bubble = flows.getValue("bubble")
				assertTrue(abs(bubble.x - speed) < 0.1 * speed && abs(bubble.y) < 0.05 * speed && abs(bubble.z) < 0.05 * speed) { "Inside the bubble space flows $bubble: expected its push, $speed c along +x" }
				val tube = flows.getValue("tube")
				assertTrue(tube.x > 1.0 && abs(tube.y) < 0.1 * tube.x && abs(tube.z) < 0.1 * tube.x) { "Inside the Krasnikov tube space flows $tube: expected along it (+x)" }
				// (The spinning hole alone, then the others without it: together the lens falls back to its tracer, which
				// has no tubes or bubbles.)
				SpacetimeField.removeProvider(provider)
				for ((name, at) in listOf("hole" to "26.5 -48 0.5 -90 25", "mass" to "-54.5 -50 0.5 -90 20", "bubble" to "-14.5 -50 40.5 -90 20", "tube" to "50 -48 -60.5 0 25")) {
					if (name == "mass") {
						command("relativity bh clear")
						SpacetimeField.addProvider(provider)
					}
					command("tp @a $at")
					repeat(2) { settleClient("Spacetime grid, $name") }
					waitTicks(40)
					takeScreenshot("spacetime_grid_$name")
				}
				val ms = (0 until 20).map { atLength { val start = System.nanoTime(); net.kernelpanicsoft.relativity.client.SpacetimeGrid.onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post()); (System.nanoTime() - start) / 1e6 } }.sorted()
				CreateRelativity.LOGGER.info("Spacetime grid: a tick's share of the rebuild in {} ms (median of 20), {} at most", "%.1f".format(ms[10]), "%.1f".format(ms.last()))
				press()
				assertTrue(!computeOnClient { net.kernelpanicsoft.relativity.client.SpacetimeGrid.shown }) { "F3 + Z again didn't hide the grid" }
			} finally {
				SpacetimeField.removeProvider(provider)
				command("tick unfreeze")
				runOnClient { if (net.kernelpanicsoft.relativity.client.SpacetimeGrid.shown) net.kernelpanicsoft.relativity.client.SpacetimeGrid.toggle() }
			}
		}
	}

	/** The post pass's GPU time (ms), the median over 20 ticks. */
	private fun ClientGameTestContext.postPassMillis(): Double {
		runOnClient { BlackHoleLens.timeGpu = true }
		try {
			return (0 until 20).map { waitTick(); computeOnClient { BlackHoleLens.gpuMillis } }.filter { it.isFinite() }.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }
		} finally {
			runOnClient { BlackHoleLens.timeGpu = false }
		}
	}

	/**
	 * The lens round a hole: the ground test's scene round a hole of r_s 2, from beside, above and close, against the
	 * exact light per pixel (LensReference), with the post pass's cost. Logged.
	 */
	@ClientGameTest
	fun ClientGameTestContext.captureLens() {
		if (!ClientTestInput.selected("captureLens")) return
		settleClient("Capture lens, start")
		runOnClient { minecraft ->
			minecraft.options.hideGui = true
			SpacetimeDebugRenderer.hidden = true
			minecraft.options.bobView().set(false)
			minecraft.options.fovEffectScale().set(0.0)
		}
		withTestWorld {
			for (command in listOf("gamemode spectator @a", "time set noon", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "weather clear", "relativity testscene ground", "tick freeze")) command(command)
			try {
				for ((name, at) in listOf("side" to "-14.5 -54 0.5 -90 10", "above" to "-9.5 -46 0.5 -90 45", "close" to "-7.5 -55 0.5 -90 5")) {
					command("tp @a $at")
					runOnClient { it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO }
					repeat(2) { settleClient("Capture lens, $name") }
					waitTicks(40)
					val columns = 120
					val rows = computeOnClient { columns * it.window.height / it.window.width }
					val reference = Array(rows) { arrayOfNulls<LensReference.Kind>(columns) }
					for (first in 0 until rows step 8) {
						val part = atLength { minecraft ->
							val hole = SpacetimeField.blackHoles(minecraft.level!!).single()
							LensReference.trace(minecraft, hole, columns, rows, 96.0, 60.0, first until minOf(first + 8, rows))
						}
						for (row in first until minOf(first + 8, rows)) reference[row] = part[row]
					}
					val shot = takeScreenshot("capture_$name")
					CreateRelativity.LOGGER.info("Capture lens, {}: {}% of pixels in the wrong class; post pass {} ms; {} fps", name,
						"%.1f".format(LensReference.compare(reference, shot) * 100), "%.2f".format(postPassMillis()), computeOnClient { it.fps })
				}
			} finally {
				command("tick unfreeze")
			}
		}
	}

	/**
	 * Tubes and bubbles placed by command (`relativity tube`, `relativity bubble`): the client's field has them (a bent
	 * tube, extended; a bubble round a box), the lens draws the tube (screenshot from inside it), and clear takes them.
	 */
	@ClientGameTest
	fun ClientGameTestContext.testSourceCommands() {
		if (!ClientTestInput.selected("testSourceCommands")) return
		settleClient("Test source commands, start")
		runOnClient { minecraft ->
			minecraft.options.hideGui = true
			SpacetimeDebugRenderer.hidden = true
			minecraft.options.bobView().set(false)
		}
		withTestWorld {
			while (atLength { it.level == null || it.player == null }) Thread.sleep(500)
			repeat(3) { settleClient("Test source commands, world") }
			waitTicks(20)
			for (command in listOf("gamemode spectator @a", "time set noon", "gamerule doDaylightCycle false", "relativity bh clear")) command(command)
			command("relativity tube add -40 -58.5 0.5 8 -58.5 0.5 2.4564560522")
			command("relativity tube extend 40 -58.5 32.5")
			command("relativity bubble add 20 -60 -20 26 -57 -14 2")
			waitTicks(5)
			val seen = computeOnClient { minecraft -> SpacetimeField.fieldSources(minecraft.level!!).map { it.javaClass.simpleName to ((it as? net.kernelpanicsoft.relativity.spacetime.FieldSource.Krasnikov)?.spine?.size ?: 0) } }
			CreateRelativity.LOGGER.info("Test source commands: the client's field has {}", seen)
			assertTrue(seen.count { it.first == "Krasnikov" && it.second == 3 } == 1) { "Expected one Krasnikov tube of 3 points, the client has $seen" }
			assertTrue(seen.count { it.first == "Ellipsoid" } == 1) { "Expected one bubble, the client has $seen" }
			command("tp @a -10.5 -60.12 0.5 -80 10")
			repeat(3) { settleClient("Test source commands, in the tube") }
			waitTicks(40)
			assertTrue(computeOnClient { VertexLens.composed }) { "The placed tube isn't drawn by the field lens" }
			takeScreenshot("test_source_tube")
			command("relativity tube clear")
			command("relativity bubble clear")
			waitTicks(5)
			val left = computeOnClient { minecraft -> SpacetimeField.fieldSources(minecraft.level!!).size }
			assertTrue(left == 0) { "$left sources left after clear" }
		}
	}

	/** [action] on the client thread, waited for at length (a program compiling cold, a long trace). */
	private fun <T> atLength(action: (net.minecraft.client.Minecraft) -> T): T {
		val minecraft = net.minecraft.client.Minecraft.getInstance()
		val result = java.util.concurrent.CompletableFuture<T>()
		minecraft.execute { try { result.complete(action(minecraft)) } catch (e: Throwable) { result.completeExceptionally(e) } }
		return result.get(600, java.util.concurrent.TimeUnit.SECONDS)
	}

	/**
	 * Waits for the client thread directly, at length, and logs (and returns) how long it took: the first frames after a change of
	 * lens compile programs (the terrain's, the tracer's) that can take longer than a harness call allows.
	 */
	private fun settleClient(label: String): Long {
		val start = System.nanoTime()
		val ready = java.util.concurrent.CompletableFuture<Unit>()
		repeat(3) {
			val frame = java.util.concurrent.CompletableFuture<Unit>()
			net.minecraft.client.Minecraft.getInstance().execute { frame.complete(Unit) }
			frame.get(300, java.util.concurrent.TimeUnit.SECONDS)
			Thread.sleep(200)
		}
		ready.complete(Unit)
		val millis = (System.nanoTime() - start) / 1_000_000
		CreateRelativity.LOGGER.info("{}: client settled after {} ms", label, millis)
		return millis
	}

	/**
	 * Two non-spinning holes over the ground test's checkerboard and pillars, composed in the shaders (the field lens,
	 * VertexLens.fieldLens) and with the per-frame tracer it replaces, against the CPU trace through both
	 * ([LensReference.traceField]): accuracy and the GPU's cost, per view. Logged; fails if the field lens is worse
	 * than the tracer by more than a little.
	 */
	@ClientGameTest
	fun ClientGameTestContext.twoHoles() {
		if (!ClientTestInput.selected("twoHoles")) return
		runOnClient { minecraft ->
			minecraft.options.renderDistance().set(6)
			minecraft.options.hideGui = true
			SpacetimeDebugRenderer.hidden = true
			minecraft.options.bobView().set(false)
			minecraft.options.fovEffectScale().set(0.0)
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
				for (x in -32..48) for (z in -32..32) {
					val block = if (Math.floorMod(Math.floorDiv(x, 2) + Math.floorDiv(z, 2), 2) == 0) Blocks.WHITE_CONCRETE else Blocks.BLUE_CONCRETE
					level.setBlock(BlockPos(x, -61, z), block.defaultBlockState(), 2)
				}
				for (x in -32..48 step 8) for (z in -32..32 step 8) {
					for (y in -60..-58) level.setBlock(BlockPos(x, y, z), Blocks.RED_CONCRETE.defaultBlockState(), 2)
				}
			}
			command("relativity bh clear")
			command("relativity bh create 0.5 -54 0.5 2 0")
			command("relativity bh create 16.5 -54 -6.5 1.5 0")
			command("tick freeze")
			val failures = ArrayList<String>()
			for ((name, at) in listOf("side" to "-14.5 -54 0.5 -90 10", "above" to "-9.5 -46 0.5 -90 45", "between" to "8.5 -54 6.5 -150 5")) {
				command("tp @a $at")
				settleClient("Two holes, $name")
				runOnClient { it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO }
				waitTicks(40)
				val columns = 120
				val rows = computeOnClient { columns * it.window.height / it.window.width }
				val reference = Array(rows) { arrayOfNulls<LensReference.Kind>(columns) }
				// (Two rows a call: the trace through both holes is slow, and each call has a time limit.)
				for (first in 0 until rows step 2) {
					val part = computeOnClient { minecraft -> LensReference.traceField(minecraft, columns, rows, 200.0, 60.0, first until minOf(first + 2, rows)) }
					for (row in first until minOf(first + 2, rows)) reference[row] = part[row]
				}
				val wrong = HashMap<Boolean, Double>()
				// The field lens with the post pass (shadow and sky), and alone (vertex lens only).
				for (weak in listOf(false, true)) {
					val field = true
					runOnClient { BlackHoleLens.rawFrame = weak; BlackHoleLens.timeGpu = true }
					settleClient("Two holes, $name")
					waitTicks(20)
					command("tp @a $at")
					runOnClient { it.player?.deltaMovement = net.minecraft.world.phys.Vec3.ZERO }
					waitTicks(20)
					val fps = computeOnClient { it.fps }
					val millis = (0 until 20).map { waitTick(); computeOnClient { BlackHoleLens.gpuMillis } }.filter { it.isFinite() }.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }
					val composed = computeOnClient { VertexLens.composed }
					// Floor points round the holes: how many the field lens finds images for, and the solve's misses.
					if (!weak) {
						val (eye, rotation) = computeOnClient { minecraft ->
							val camera = minecraft.gameRenderer.mainCamera
							camera.position to org.joml.Quaternionf(camera.rotation()).conjugate()
						}
						val views = ArrayList<FloatArray>()
						for (x in -24..40 step 2) for (z in -30..24 step 2) {
							val v = rotation.transform(org.joml.Vector3f((x + 0.5 - eye.x).toFloat(), (-60.0 - eye.y).toFloat(), (z + 0.5 - eye.z).toFloat()))
							if (v.z < -1f) views += floatArrayOf(v.x, v.y, v.z)
						}
						val results = views.chunked(200).flatMap { chunk -> atLength { LensCheck.run(chunk, VertexLens.lastProjection, image = true) } }
						val found = results.count { it[0] > 0f }
						val residuals = results.map { it[1] }.filter { it >= 0f }.sorted()
						CreateRelativity.LOGGER.info("Two holes, {}: {} of {} floor points found; solve misses (px) median {}, 90% {}, max {}", name, found, results.size,
							residuals.getOrNull(residuals.size / 2), residuals.getOrNull(residuals.size * 9 / 10), residuals.lastOrNull())
					}
					val label = if (weak) "field_vertex_only" else "field"
					val shot = takeScreenshot("two_holes_${name}_$label")
					if (!weak) wrong[field] = LensReference.compare(reference, shot)
					CreateRelativity.LOGGER.info("Two holes, {} ({}{}): {}% of pixels in the wrong class; GPU lens {} ms, {} fps",
						name, label, if (field && !composed) ", NOT composed" else "", "%.1f".format(LensReference.compare(reference, shot) * 100), "%.2f".format(millis), fps)
				}
				if (wrong[true]!! > 0.04) failures += "$name: field lens ${"%.1f".format(wrong[true]!! * 100)}% wrong"
			}
			runOnClient { BlackHoleLens.rawFrame = false; BlackHoleLens.timeGpu = false }
			command("tick unfreeze")
			if (failures.isNotEmpty()) throw AssertionError(failures.joinToString("; "))
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
		// The lens, and the vertex lens alone (the post pass passing the frame through).
		val modes = listOf("lens", "vertex_only")
		for (mode in modes) {
			runOnClient {
				BlackHoleLens.timeGpu = true
				BlackHoleLens.rawFrame = mode == "vertex_only"
			}
			if (mode == "vertex_only") waitTicks(20)
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
					doubleArrayOf(BlackHoleLens.gpuMillis)
				}
			}
			val median = DoubleArray(1) { k -> samples.map { it[k] }.filter { it.isFinite() }.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] } }
			val shot = takeScreenshot("ground_${name}_$mode")
			val wrong = LensReference.compare(reference, shot)
			wrongBy[mode] = wrong
			CreateRelativity.LOGGER.info(
				"Lens vs reference, {} ({}): {}% of pixels in the wrong class; GPU lens {} ms",
				name, mode, "%.1f".format(wrong * 100), "%.2f".format(median[0]),
			)
		}
		runOnClient { BlackHoleLens.rawFrame = false }
		// Within 4% (6% in the busy scene, where the reference's classes are rough: shaded block sides).
		val wrong = wrongBy.getValue("lens")
		if (wrong > if (name.endsWith("busy")) 0.06 else 0.04) failures += "$name: ${wrong * 100}% of pixels in the wrong class"
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
	private fun ClientGameTestContext.withTestWorld(seed: String? = null, ponderflat: Boolean = true, block: TestSingleplayerContext.() -> Unit) {
		runOnClient { ClientTestInput.lock(it) }
		waitTicks(20) // the window resizes
		// Ponderflat in its Contrast colours (seed 1: the floor at y -61, as superflat's), or superflat, or the default
		// world type with a given seed.
		val builder = worldBuilder()
		if (seed != null) builder.setUseConsistentSettings(false).adjustSettings { it.seed = seed }
		else if (ponderflat) builder.adjustSettings { state ->
			val ponderflat = net.minecraft.resources.ResourceKey.create(
				net.minecraft.core.registries.Registries.WORLD_PRESET,
				net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CreateRelativity.MOD_ID, "ponderflat"),
			)
			state.worldType = net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.WorldTypeEntry(
				state.settings.worldgenLoadContext().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET).getOrThrow(ponderflat),
			)
			// (Its Contrast preset: white and blue concrete in 2-block squares, the scenes' floor, everywhere.)
			state.updateDimensions { registries, dimensions ->
				val overworld = dimensions.overworld()
				dimensions.replaceOverworldGenerator(registries, net.kernelpanicsoft.relativity.world.PonderflatGenerator(
					overworld.biomeSource, Blocks.WHITE_CONCRETE.defaultBlockState(), Blocks.BLUE_CONCRETE.defaultBlockState(), 2,
				))
			}
		}
		val world = builder.create()
		val save = world.saveDirectory
		try {
			// (After a lens change the world's programs compile cold: the first frames take long, past the harness's waits.)
			while (atLength { it.level == null || it.player == null }) Thread.sleep(500)
			// (Till frames come quickly: the programs compile lazily, a few each frame.)
			for (round in 0 until 40) if (settleClient("Test world") < 3000) break
			world.block()
		} finally {
			// Debug switches back to normal, so a failed test doesn't leave the lens half off for the next.
			runOnClient {
				BlackHoleLens.rawFrame = false
				BlackHoleLens.profileFlags = 0
				BlackHoleLens.timeGpu = false
			}
			world.close()
			runOnClient { ClientTestInput.unlock(it) }
			if (save.parent?.fileName?.toString() == "saves") save.toFile().deleteRecursively()
		}
	}

	/** Waits (up to 30 s) until every chunk section in view is drawn and the lens's grids are in, then a second more. */
	private fun ClientGameTestContext.settle() {
		for (i in 0 until 600) {
			if (computeOnClient { it.levelRenderer.hasRenderedAllSections() }) break
			waitTick()
		}
		waitTicks(20)
	}

	/**
	 * Runs a command as the server (Archie's runCommand fails on its reflective call here), waited for at length: a
	 * command that makes a hole can stall a while as the client compiles the lens's programs cold.
	 */
	private fun TestSingleplayerContext.command(command: String) {
		val server = net.minecraft.client.Minecraft.getInstance().singleplayerServer ?: error("no integrated server")
		val done = java.util.concurrent.CompletableFuture<Unit>()
		server.execute {
			try {
				server.commands.performPrefixedCommand(server.createCommandSourceStack(), command)
				done.complete(Unit)
			} catch (e: Throwable) {
				done.completeExceptionally(e)
			}
		}
		done.get(600, java.util.concurrent.TimeUnit.SECONDS)
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
