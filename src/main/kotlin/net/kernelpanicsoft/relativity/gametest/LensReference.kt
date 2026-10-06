package net.kernelpanicsoft.relativity.gametest

import com.mojang.blaze3d.platform.NativeImage
import net.kernelpanicsoft.relativity.physics.geodesic.NullRay
import net.kernelpanicsoft.relativity.spacetime.BlackHole
import net.kernelpanicsoft.relativity.spacetime.GameFrame
import net.minecraft.client.Minecraft
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import org.joml.Vector3d
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * What the camera should see around one black hole, traced on the CPU: each pixel's null geodesic (physics-core
 * [NullRay]) clipped against the level's blocks. The lens shader's screenshot is compared with it by coarse colour
 * class, which lighting, texture and fog barely move.
 */
internal object LensReference {
	enum class Kind(val color: Int) {
		WHITE(0xFFDDDDDD.toInt()), BLUE(0xFFAA3333.toInt()), RED(0xFF2222CC.toInt()), GREEN(0xFF33AA33.toInt()),
		SKY(0xFFFFCC88.toInt()), BLACK(0xFF000000.toInt()), UNKNOWN(0xFFFF00FF.toInt()),
	}

	/**
	 * Reference classes on a grid of [columns] × [rows] pixel centres, or null where too far to be fog-free; only the
	 * rows in [only] (the rest are left null), so a caller can spread the work over several calls.
	 */
	fun trace(
		minecraft: Minecraft, hole: BlackHole, columns: Int, rows: Int, maxLength: Double, fogFree: Double,
		only: IntRange = 0 until rows,
	): Array<Array<Kind?>> {
		val camera = minecraft.gameRenderer.mainCamera
		val eye = camera.position
		val look = Vector3d(camera.lookVector)
		val up = Vector3d(camera.upVector)
		val right = Vector3d(camera.leftVector).negate()
		val tanHalf = tan(Math.toRadians(minecraft.options.fov().get().toDouble()) / 2.0)
		val aspect = minecraft.window.width.toDouble() / minecraft.window.height
		val level = minecraft.level!!
		val capture = 1.02 * hole.equatorialExtent(hole.horizonRadius) // traced back, light only nears the horizon
		val origin = DoubleArray(3)
		GameFrame.toPhysics(Vector3d(eye.x - hole.x, eye.y - hole.y, eye.z - hole.z), origin)
		return Array(rows) { row ->
			Array(columns) inner@{ column ->
				if (row !in only) return@inner null
				val x = ((column + 0.5) / columns * 2.0 - 1.0) * tanHalf * aspect
				val y = (1.0 - (row + 0.5) / rows * 2.0) * tanHalf
				val view = Vector3d(look).add(Vector3d(right).mul(x)).add(Vector3d(up).mul(y)).normalize()
				val direction = DoubleArray(3)
				GameFrame.toPhysics(view, direction)
				val path = NullRay.trace(hole.spacetime(), origin, direction, maxLength, capture, maxSegment = 0.5, arriving = true)
				var kind: Kind? = if (path.end == NullRay.End.CAPTURED) Kind.BLACK else Kind.SKY
				var traveled = 0.0
				var previous: Vec3? = null
				for (point in path.points) {
					val game = GameFrame.toGame(point, 0, Vector3d())
					val at = Vec3(game.x + hole.x, game.y + hole.y, game.z + hole.z)
					val from = previous
					previous = at
					if (from == null) continue
					val hit = level.clip(ClipContext(from, at, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()))
					if (hit.type == HitResult.Type.BLOCK) {
						val distance = traveled + from.distanceTo(hit.location)
						kind = if (distance > fogFree) null else classify(level.getBlockState((hit as BlockHitResult).blockPos).block, hit)
						break
					}
					traveled += from.distanceTo(at)
				}
				if (kind == Kind.SKY && path.end != NullRay.End.CAPTURED && traveled < fogFree) kind = null // ended early
				// Out of length still heading down: the game draws terrain a little past the trace's reach, so unknown.
				if (kind == Kind.SKY && path.points.size >= 2) {
					val last = GameFrame.toGame(path.points.last(), 0, Vector3d())
					val before = GameFrame.toGame(path.points[path.points.size - 2], 0, Vector3d())
					if (last.y - before.y <= 0.0) kind = null
				}
				kind
			}
		}
	}

	private fun classify(block: Block, hit: BlockHitResult): Kind? = when {
		block == Blocks.WHITE_CONCRETE -> Kind.WHITE
		block == Blocks.BLUE_CONCRETE -> Kind.BLUE
		block == Blocks.RED_CONCRETE -> Kind.RED
		block == Blocks.GRASS_BLOCK && hit.direction == net.minecraft.core.Direction.UP -> Kind.GREEN
		else -> null
	}

	/** Colour class of a rendered pixel (ABGR, as NativeImage stores it). */
	fun classify(abgr: Int): Kind {
		val r = abgr and 0xFF
		val g = abgr shr 8 and 0xFF
		val b = abgr shr 16 and 0xFF
		val high = max(r, max(g, b))
		val low = min(r, min(g, b))
		return when {
			high < 25 -> Kind.BLACK
			r > 1.6 * g && r > 1.6 * b -> Kind.RED
			g > 1.1 * r && g > b -> Kind.GREEN
			b > 1.4 * r && b > 1.2 * g && r < 80 -> Kind.BLUE
			b > 1.2 * r && r >= 80 -> Kind.SKY
			high - low < 40 && high > 110 -> Kind.WHITE
			else -> Kind.UNKNOWN
		}
	}

	/**
	 * Fraction of comparable grid pixels whose rendered class differs from the reference (comparable: known, and the
	 * same class across its 3×3 neighbourhood, so edges don't count). Also writes the reference and the mismatches
	 * next to the screenshot.
	 */
	fun compare(reference: Array<Array<Kind?>>, screenshot: Path): Double {
		val rows = reference.size
		val columns = reference[0].size
		NativeImage.read(Files.newInputStream(screenshot)).use { image ->
			NativeImage(columns * 2, rows, false).use { out ->
				var compared = 0
				var wrong = 0
				for (row in 0 until rows) for (column in 0 until columns) {
					val expected = reference[row][column]
					val px = ((column + 0.5) / columns * image.width).toInt()
					val py = ((row + 0.5) / rows * image.height).toInt()
					val seen = classify(image.getPixelRGBA(px, py))
					out.setPixelRGBA(column, row, expected?.color ?: 0xFF444444.toInt())
					var uniform = expected != null
					for (dy in -1..1) for (dx in -1..1) {
						val neighbour = reference.getOrNull(row + dy)?.getOrNull(column + dx)
						if (neighbour != expected) uniform = false
					}
					var mark = 0xFF444444.toInt()
					if (uniform) {
						compared += 1
						if (seen != expected) {
							wrong += 1
							mark = 0xFF0000FF.toInt()
						} else {
							mark = 0xFF00AA00.toInt()
						}
					}
					out.setPixelRGBA(columns + column, row, mark)
				}
				out.writeToFile(screenshot.resolveSibling(screenshot.fileName.toString().removeSuffix(".png") + "_reference.png"))
				return if (compared == 0) 1.0 else wrong.toDouble() / compared
			}
		}
	}
}
