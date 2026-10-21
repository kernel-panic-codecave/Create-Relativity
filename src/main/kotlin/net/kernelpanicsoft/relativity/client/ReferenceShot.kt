package net.kernelpanicsoft.relativity.client

import com.mojang.blaze3d.platform.NativeImage
import net.kernelpanicsoft.relativity.physics.geodesic.HamiltonianRay
import net.kernelpanicsoft.relativity.physics.geodesic.NullRay
import net.kernelpanicsoft.relativity.physics.spacetime.field.ComposedSpacetime
import net.kernelpanicsoft.relativity.physics.spacetime.field.KerrSource
import net.kernelpanicsoft.relativity.physics.spacetime.field.MetricSource
import net.kernelpanicsoft.relativity.spacetime.GameFrame
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import net.neoforged.neoforge.common.NeoForge
import org.joml.Vector3d
import java.io.File
import kotlin.math.tan

/**
 * `/relativityref`: the current view traced exactly, to hold the lens against. A screenshot is taken, then light from
 * the camera's pose at that moment is followed back through every source in the level (physics-core's
 * [HamiltonianRay], all holes and field sources composed) a few rows a tick, and the picture it sees saved beside the
 * screenshot (`<time>_reference.png`): blocks in their map colours shaded by face, light blue where light escapes,
 * black where it's captured, grey where it ran out of length.
 */
object ReferenceShot {
	private const val COLUMNS = 320
	private const val MAX_LENGTH = 400.0
	/** Rows traced per tick (about 30 ms each through two holes). */
	private const val ROWS_PER_TICK = 2

	private class Job(
		val eye: Vec3, val look: Vector3d, val up: Vector3d, val right: Vector3d, val tanHalf: Double, val aspect: Double,
		val rows: Int, val ray: HamiltonianRay, val file: File,
	) {
		val pixels = IntArray(COLUMNS * rows)
		var next = 0
	}

	private var job: Job? = null

	fun init() {
		NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent::class.java) { event ->
			event.dispatcher.register(net.minecraft.commands.Commands.literal("relativityref").executes { start(); 1 })
		}
		NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { tick() }
	}

	private fun start() {
		val minecraft = Minecraft.getInstance()
		val level = minecraft.level ?: return
		val camera = minecraft.gameRenderer.mainCamera
		val eye = camera.position
		val at = Vector3d(eye.x, eye.y, eye.z)
		val sources = SpacetimeField.blackHoles(level).map { hole ->
			val p = DoubleArray(3)
			GameFrame.toPhysics(Vector3d(hole.x, hole.y, hole.z).sub(at), p)
			KerrSource(p[0], p[1], p[2], hole.mass, hole.spin * hole.mass) as MetricSource
		} + SpacetimeField.fieldSources(level).map { it.toPhysics(at) }
		val aspect = minecraft.window.width.toDouble() / minecraft.window.height
		val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss"))
		Screenshot.grab(minecraft.gameDirectory, "${stamp}.png", minecraft.mainRenderTarget) { }
		val file = File(File(minecraft.gameDirectory, "screenshots"), "${stamp}_reference.png")
		job = Job(
			eye, Vector3d(camera.lookVector), Vector3d(camera.upVector), Vector3d(camera.leftVector).negate(),
			tan(Math.toRadians(minecraft.options.fov().get().toDouble()) / 2.0), aspect, (COLUMNS / aspect).toInt(),
			HamiltonianRay(ComposedSpacetime(sources), rtol = 1e-8, atol = 1e-10), file,
		)
		minecraft.player?.displayClientMessage(Component.literal("Tracing the view exactly through ${sources.size} source(s)..."), false)
	}

	private fun tick() {
		val job = job ?: return
		val minecraft = Minecraft.getInstance()
		val level = minecraft.level ?: run { this.job = null; return }
		val origin = doubleArrayOf(0.0, 0.0, 0.0)
		repeat(ROWS_PER_TICK) {
			if (job.next >= job.rows) return@repeat
			val row = job.next++
			for (column in 0 until COLUMNS) {
				val x = ((column + 0.5) / COLUMNS * 2.0 - 1.0) * job.tanHalf * job.aspect
				val y = (1.0 - (row + 0.5) / job.rows * 2.0) * job.tanHalf
				val view = Vector3d(job.look).add(Vector3d(job.right).mul(x)).add(Vector3d(job.up).mul(y)).normalize()
				val direction = DoubleArray(3)
				GameFrame.toPhysics(view, direction)
				val path = job.ray.trace(origin, direction, MAX_LENGTH, maxSegment = 0.5, arriving = true)
				var color = when (path.end) {
					NullRay.End.CAPTURED -> 0xFF000000.toInt()
					NullRay.End.LENGTH -> 0xFF808080.toInt()
					else -> 0xFF99BBFF.toInt()
				}
				var previous: Vec3? = null
				for (point in path.points) {
					val game = GameFrame.toGame(point, 0, Vector3d())
					val at = Vec3(game.x + job.eye.x, game.y + job.eye.y, game.z + job.eye.z)
					val from = previous
					previous = at
					if (from == null) continue
					val hit = level.clip(ClipContext(from, at, ClipContext.Block.VISUAL, ClipContext.Fluid.ANY, CollisionContext.empty()))
					if (hit.type == HitResult.Type.BLOCK) {
						val blockHit = hit as BlockHitResult
						val state = level.getBlockState(blockHit.blockPos)
						val shade = when (blockHit.direction) {
							Direction.UP -> 1.0
							Direction.DOWN -> 0.5
							Direction.NORTH, Direction.SOUTH -> 0.8
							else -> 0.6
						}
						val map = (if (!state.fluidState.isEmpty) state.fluidState.createLegacyBlock() else state).getMapColor(level, blockHit.blockPos).col
						color = (0xFF shl 24) or (((map shr 16 and 0xFF) * shade).toInt() shl 16) or
							(((map shr 8 and 0xFF) * shade).toInt() shl 8) or ((map and 0xFF) * shade).toInt()
						break
					}
				}
				job.pixels[row * COLUMNS + column] = color
			}
		}
		if (job.next < job.rows) return
		NativeImage(COLUMNS, job.rows, false).use { image ->
			for (row in 0 until job.rows) for (column in 0 until COLUMNS) {
				val argb = job.pixels[row * COLUMNS + column]
				image.setPixelRGBA(column, row, (argb and 0xFF00FF00.toInt()) or ((argb shr 16) and 0xFF) or ((argb and 0xFF) shl 16))
			}
			job.file.parentFile.mkdirs()
			image.writeToFile(job.file)
		}
		minecraft.player?.displayClientMessage(Component.literal("Reference saved: ${job.file.name}"), false)
		this.job = null
	}
}
