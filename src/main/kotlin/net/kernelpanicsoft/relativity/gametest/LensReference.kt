package net.kernelpanicsoft.relativity.gametest

import com.mojang.blaze3d.platform.NativeImage
import net.kernelpanicsoft.relativity.physics.geodesic.HamiltonianRay
import net.kernelpanicsoft.relativity.physics.geodesic.NullRay
import net.kernelpanicsoft.relativity.physics.spacetime.field.ComposedSpacetime
import net.kernelpanicsoft.relativity.physics.spacetime.field.KerrSource
import net.kernelpanicsoft.relativity.physics.spacetime.field.MetricSource
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
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
		val capture = 1.02 * hole.equatorialExtent(hole.horizonRadius) // traced back, light only nears the horizon
		return traceWith(minecraft, Vec3(hole.x, hole.y, hole.z), columns, rows, fogFree, only) { origin, direction ->
			NullRay.trace(hole.spacetime(), origin, direction, maxLength, capture, maxSegment = 0.5, arriving = true)
		}
	}

	/**
	 * As [trace], through every source in the level together ([SpacetimeField]: holes and the rest), on physics-core's
	 * [HamiltonianRay] instead of one hole's metric.
	 */
	fun traceField(minecraft: Minecraft, columns: Int, rows: Int, maxLength: Double, fogFree: Double, only: IntRange = 0 until rows): Array<Array<Kind?>> {
		val eye = minecraft.gameRenderer.mainCamera.position
		val at = Vector3d(eye.x, eye.y, eye.z)
		val level = minecraft.level!!
		// Holes and masses: the lens's own model, the superposed orbit field (OrbitField), followed finely, so what's
		// measured is the lens's error and not the model's (the summed metric the physics uses differs from it).
		val field = SpacetimeField.fieldSources(level)
		if (field.all { it is net.kernelpanicsoft.relativity.spacetime.FieldSource.Mass || it is BlackHole }) {
			val v3 = { x: Double, y: Double, z: Double -> net.kernelpanicsoft.relativity.physics.lens.field.V3(x - eye.x, y - eye.y, z - eye.z) }
			val modules = SpacetimeField.blackHoles(level).map { net.kernelpanicsoft.relativity.physics.lens.field.HoleModule(v3(it.x, it.y, it.z), it.schwarzschildRadius) } +
				field.filterIsInstance<net.kernelpanicsoft.relativity.spacetime.FieldSource.Mass>().map {
					net.kernelpanicsoft.relativity.physics.lens.field.MassModule(v3(it.center.x(), it.center.y(), it.center.z()), it.mass, it.softening)
				}
			val model = net.kernelpanicsoft.relativity.physics.lens.field.OrbitField(modules)
			return traceWith(minecraft, eye, columns, rows, fogFree, only) { _, direction ->
				val game = GameFrame.toGame(direction, 0, Vector3d())
				val traced = model.trace(net.kernelpanicsoft.relativity.physics.lens.field.V3.ZERO, net.kernelpanicsoft.relativity.physics.lens.field.V3(game.x, game.y, game.z),
					reach = maxLength, record = true, recordEvery = 0.25)
				// (Captured: what it passes before falling in still shows; past that, the shadow.)
				NullRay.Path(traced.points.map { p -> DoubleArray(3).also { GameFrame.toPhysics(Vector3d(p.x, p.y, p.z), it) } },
					if (traced.escape == null) NullRay.End.CAPTURED else NullRay.End.LENGTH)
			}
		}

		val sources = SpacetimeField.blackHoles(level).map { hole ->
			val p = DoubleArray(3)
			GameFrame.toPhysics(Vector3d(hole.x, hole.y, hole.z).sub(at), p)
			KerrSource(p[0], p[1], p[2], hole.mass, hole.spin * hole.mass) as MetricSource
		} + SpacetimeField.fieldSources(level).map { it.toPhysics(at) }
		val ray = HamiltonianRay(ComposedSpacetime(sources), rtol = 1e-8, atol = 1e-10)
		return traceWith(minecraft, eye, columns, rows, fogFree, only) { origin, direction ->
			ray.trace(origin, direction, maxLength, maxSegment = 0.5, arriving = true)
		}
	}

	/**
	 * As [traceField], but with light following [module] (a tube lens module, in game coordinates relative to the eye) as
	 * it does: straight between its walls, on out to [maxLength].
	 */
	fun traceTube(minecraft: Minecraft, module: net.kernelpanicsoft.relativity.physics.lens.field.TubeModule, columns: Int, rows: Int, maxLength: Double, fogFree: Double, only: IntRange = 0 until rows): Array<Array<Kind?>> =
		tracePaths(minecraft, columns, rows, fogFree, only) { d -> module.path(net.kernelpanicsoft.relativity.physics.lens.field.V3.ZERO, d, maxLength) }

	/** As [traceTube], through a bubble lens module ([module], in game coordinates relative to the eye). */
	fun traceBubble(minecraft: Minecraft, module: net.kernelpanicsoft.relativity.physics.lens.field.BubbleModule, columns: Int, rows: Int, maxLength: Double, fogFree: Double, only: IntRange = 0 until rows): Array<Array<Kind?>> =
		tracePaths(minecraft, columns, rows, fogFree, only) { d -> module.path(net.kernelpanicsoft.relativity.physics.lens.field.V3.ZERO, d, maxLength) }

	/** Light along the polyline [pathOf] gives for each pixel's direction (game axes, from the eye; null: captured). */
	private fun tracePaths(minecraft: Minecraft, columns: Int, rows: Int, fogFree: Double, only: IntRange, pathOf: (net.kernelpanicsoft.relativity.physics.lens.field.V3) -> List<net.kernelpanicsoft.relativity.physics.lens.field.V3>?): Array<Array<Kind?>> {
		val eye = minecraft.gameRenderer.mainCamera.position
		return traceWith(minecraft, eye, columns, rows, fogFree, only) { _, direction ->
			val game = GameFrame.toGame(direction, 0, Vector3d())
			val path = pathOf(net.kernelpanicsoft.relativity.physics.lens.field.V3(game.x, game.y, game.z))
			if (path == null) NullRay.Path(listOf(DoubleArray(3)), NullRay.End.CAPTURED)
			else NullRay.Path(path.map { p -> DoubleArray(3).also { GameFrame.toPhysics(Vector3d(p.x, p.y, p.z), it) } }, NullRay.End.LENGTH)
		}
	}

	/**
	 * How far (radians, as seen from [eye]) the light seen looking along [direction] (game axes) passes from [target]:
	 * its path traced back through [sources] (physics axes, relative to [eye]) on [ray], its closest approach to
	 * [target] over [target]'s distance. NaN if the path is captured before getting there.
	 */
	fun missAngle(ray: HamiltonianRay, eye: Vec3, target: Vec3, direction: Vec3): Double {
		val goal = DoubleArray(3)
		GameFrame.toPhysics(Vector3d(target.x - eye.x, target.y - eye.y, target.z - eye.z), goal)
		val dir = DoubleArray(3)
		GameFrame.toPhysics(Vector3d(direction.x, direction.y, direction.z), dir)
		val distance = target.distanceTo(eye)
		val path = ray.trace(DoubleArray(3), dir, distance * 1.5 + 8.0, maxSegment = minOf(0.25, 0.05 * distance), arriving = true)
		var best = Double.MAX_VALUE
		for (k in 1 until path.points.size) {
			val p0 = path.points[k - 1]
			val p1 = path.points[k]
			val sx = p1[0] - p0[0]; val sy = p1[1] - p0[1]; val sz = p1[2] - p0[2]
			val len2 = sx * sx + sy * sy + sz * sz
			val t = if (len2 > 0) (((goal[0] - p0[0]) * sx + (goal[1] - p0[1]) * sy + (goal[2] - p0[2]) * sz) / len2).coerceIn(0.0, 1.0) else 0.0
			val dx = p0[0] + t * sx - goal[0]; val dy = p0[1] + t * sy - goal[1]; val dz = p0[2] + t * sz - goal[2]
			best = minOf(best, dx * dx + dy * dy + dz * dz)
		}
		if (best == Double.MAX_VALUE) return Double.NaN
		return kotlin.math.sqrt(best) / distance
	}

	/**
	 * The true image of [target] nearest view direction [start] (game axes, from [eye]): Gauss-Newton on the direction,
	 * the residual being how far the light seen along it passes from [target] (across its path, on the two axes square to
	 * [start]); null if it doesn't get within [tolerance] of [target]'s distance (no image near [start]).
	 */
	fun nearestImage(ray: HamiltonianRay, eye: Vec3, target: Vec3, start: Vec3, tolerance: Double = 2e-4): Vec3? {
		val goal = DoubleArray(3)
		GameFrame.toPhysics(Vector3d(target.x - eye.x, target.y - eye.y, target.z - eye.z), goal)
		val distance = target.distanceTo(eye)
		val s0 = start.normalize()
		val u = (if (kotlin.math.abs(s0.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)).cross(s0).normalize()
		val w = s0.cross(u)
		val uP = DoubleArray(3).also { GameFrame.toPhysics(Vector3d(u.x, u.y, u.z), it) }
		val wP = DoubleArray(3).also { GameFrame.toPhysics(Vector3d(w.x, w.y, w.z), it) }
		fun dir(a: Double, b: Double) = s0.add(u.scale(a)).add(w.scale(b)).normalize()
		// The miss vector at the path's closest approach to the target, on u and w, over the target's distance.
		fun residual(a: Double, b: Double): DoubleArray? {
			val d = dir(a, b)
			val direction = DoubleArray(3).also { GameFrame.toPhysics(Vector3d(d.x, d.y, d.z), it) }
			val path = ray.trace(DoubleArray(3), direction, distance * 1.5 + 8.0, maxSegment = minOf(0.25, 0.05 * distance), arriving = true)
			var best = Double.MAX_VALUE
			var miss = DoubleArray(3)
			for (k in 1 until path.points.size) {
				val p0 = path.points[k - 1]
				val p1 = path.points[k]
				val sx = p1[0] - p0[0]; val sy = p1[1] - p0[1]; val sz = p1[2] - p0[2]
				val len2 = sx * sx + sy * sy + sz * sz
				val t = if (len2 > 0) (((goal[0] - p0[0]) * sx + (goal[1] - p0[1]) * sy + (goal[2] - p0[2]) * sz) / len2).coerceIn(0.0, 1.0) else 0.0
				val m = doubleArrayOf(p0[0] + t * sx - goal[0], p0[1] + t * sy - goal[1], p0[2] + t * sz - goal[2])
				val d2 = m[0] * m[0] + m[1] * m[1] + m[2] * m[2]
				if (d2 < best) { best = d2; miss = m }
			}
			if (best == Double.MAX_VALUE) return null
			return doubleArrayOf((miss[0] * uP[0] + miss[1] * uP[1] + miss[2] * uP[2]) / distance, (miss[0] * wP[0] + miss[1] * wP[1] + miss[2] * wP[2]) / distance, kotlin.math.sqrt(best) / distance)
		}
		var a = 0.0
		var b = 0.0
		var r = residual(a, b) ?: return null
		val h = 1e-5
		repeat(12) {
			if (r[2] < tolerance) return dir(a, b)
			val ra = residual(a + h, b) ?: return null
			val rb = residual(a, b + h) ?: return null
			val j00 = (ra[0] - r[0]) / h; val j10 = (ra[1] - r[1]) / h
			val j01 = (rb[0] - r[0]) / h; val j11 = (rb[1] - r[1]) / h
			val det = j00 * j11 - j01 * j10
			if (kotlin.math.abs(det) < 1e-18) return null
			var da = -(j11 * r[0] - j01 * r[1]) / det
			var db = -(-j10 * r[0] + j00 * r[1]) / det
			val step = kotlin.math.sqrt(da * da + db * db)
			if (step > 0.05) { da *= 0.05 / step; db *= 0.05 / step } // stay near the start: the image nearest it
			// Halve a step that makes it worse.
			var scale = 1.0
			var next: DoubleArray? = null
			for (k in 0 until 6) {
				val candidate = residual(a + da * scale, b + db * scale)
				if (candidate != null && candidate[2] < r[2]) { next = candidate; break }
				scale *= 0.5
			}
			if (next == null) return null
			a += da * scale
			b += db * scale
			r = next
		}
		return if (r[2] < tolerance) dir(a, b) else null
	}

	/** Every source in the level (holes and the rest) on physics axes relative to [eye]. */
	fun sourcesAround(minecraft: Minecraft, eye: Vec3): List<MetricSource> {
		val level = minecraft.level!!
		val at = Vector3d(eye.x, eye.y, eye.z)
		return SpacetimeField.blackHoles(level).map { hole ->
			val p = DoubleArray(3)
			GameFrame.toPhysics(Vector3d(hole.x, hole.y, hole.z).sub(at), p)
			KerrSource(p[0], p[1], p[2], hole.mass, hole.spin * hole.mass) as MetricSource
		} + SpacetimeField.fieldSources(level).map { it.toPhysics(at) }
	}

	/**
	 * The view direction (game axes, unit) from [eye] whose light, traced back through [sources] (physics axes, relative
	 * to [eye]), passes through [target]: Nelder-Mead on the miss from the straight line of sight; null if it can't get
	 * within 0.05 blocks.
	 */
	fun imageDirection(sources: List<MetricSource>, eye: Vec3, target: Vec3): Vec3? {
		val ray = HamiltonianRay(ComposedSpacetime(sources), rtol = 1e-9, atol = 1e-11)
		val straight = target.subtract(eye).normalize()
		val u = (if (kotlin.math.abs(straight.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)).cross(straight).normalize()
		val w = straight.cross(u)
		val reach = target.distanceTo(eye) * 1.5 + 20.0
		val goal = DoubleArray(3)
		GameFrame.toPhysics(Vector3d(target.x - eye.x, target.y - eye.y, target.z - eye.z), goal)
		fun dir(a: Double, b: Double) = straight.add(u.scale(a)).add(w.scale(b)).normalize()
		fun miss(a: Double, b: Double): Double {
			val d = dir(a, b)
			val direction = DoubleArray(3)
			GameFrame.toPhysics(Vector3d(d.x, d.y, d.z), direction)
			val path = ray.trace(DoubleArray(3), direction, reach, maxSegment = 0.25, arriving = true)
			var best = Double.MAX_VALUE
			for (k in 1 until path.points.size) {
				val p0 = path.points[k - 1]
				val p1 = path.points[k]
				val sx = p1[0] - p0[0]; val sy = p1[1] - p0[1]; val sz = p1[2] - p0[2]
				val len2 = sx * sx + sy * sy + sz * sz
				val t = if (len2 > 0) (((goal[0] - p0[0]) * sx + (goal[1] - p0[1]) * sy + (goal[2] - p0[2]) * sz) / len2).coerceIn(0.0, 1.0) else 0.0
				val dx = p0[0] + t * sx - goal[0]; val dy = p0[1] + t * sy - goal[1]; val dz = p0[2] + t * sz - goal[2]
				best = minOf(best, dx * dx + dy * dy + dz * dz)
			}
			return kotlin.math.sqrt(best)
		}
		// Nelder-Mead in (a, b).
		val simplex = arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.03, 0.0), doubleArrayOf(0.0, 0.03))
		val values = DoubleArray(3) { miss(simplex[it][0], simplex[it][1]) }
		repeat(120) {
			val order = (0..2).sortedBy { values[it] }
			val best = order[0]; val mid = order[1]; val worst = order[2]
			val cx = (simplex[best][0] + simplex[mid][0]) / 2; val cy = (simplex[best][1] + simplex[mid][1]) / 2
			fun at(k: Double) = doubleArrayOf(cx + k * (simplex[worst][0] - cx), cy + k * (simplex[worst][1] - cy))
			val r = at(-1.0); val fr = miss(r[0], r[1])
			if (fr < values[best]) {
				val e = at(-2.0); val fe = miss(e[0], e[1])
				if (fe < fr) { simplex[worst] = e; values[worst] = fe } else { simplex[worst] = r; values[worst] = fr }
			} else if (fr < values[mid]) {
				simplex[worst] = r; values[worst] = fr
			} else {
				val c = at(0.5); val fc = miss(c[0], c[1])
				if (fc < values[worst]) { simplex[worst] = c; values[worst] = fc } else {
					for (k in listOf(mid, worst)) {
						simplex[k] = doubleArrayOf((simplex[k][0] + simplex[best][0]) / 2, (simplex[k][1] + simplex[best][1]) / 2)
						values[k] = miss(simplex[k][0], simplex[k][1])
					}
				}
			}
		}
		val best = (0..2).minBy { values[it] }
		if (values[best] > 0.05) return null
		return dir(simplex[best][0], simplex[best][1])
	}

	/** Each grid pixel's path from [pathOf] (physics axes, relative to [center]), clipped against the level's blocks. */
	private fun traceWith(
		minecraft: Minecraft, center: Vec3, columns: Int, rows: Int, fogFree: Double, only: IntRange,
		pathOf: (DoubleArray, DoubleArray) -> NullRay.Path,
	): Array<Array<Kind?>> {
		val camera = minecraft.gameRenderer.mainCamera
		val eye = camera.position
		val look = Vector3d(camera.lookVector)
		val up = Vector3d(camera.upVector)
		val right = Vector3d(camera.leftVector).negate()
		val tanHalf = tan(Math.toRadians(minecraft.options.fov().get().toDouble()) / 2.0)
		val aspect = minecraft.window.width.toDouble() / minecraft.window.height
		val level = minecraft.level!!
		val origin = DoubleArray(3)
		GameFrame.toPhysics(Vector3d(eye.x - center.x, eye.y - center.y, eye.z - center.z), origin)
		return Array(rows) { row ->
			Array(columns) inner@{ column ->
				if (row !in only) return@inner null
				val x = ((column + 0.5) / columns * 2.0 - 1.0) * tanHalf * aspect
				val y = (1.0 - (row + 0.5) / rows * 2.0) * tanHalf
				val view = Vector3d(look).add(Vector3d(right).mul(x)).add(Vector3d(up).mul(y)).normalize()
				val direction = DoubleArray(3)
				GameFrame.toPhysics(view, direction)
				val path = pathOf(origin, direction)
				var kind: Kind? = if (path.end == NullRay.End.CAPTURED) Kind.BLACK else Kind.SKY
				var traveled = 0.0
				var previous: Vec3? = null
				for (point in path.points) {
					val game = GameFrame.toGame(point, 0, Vector3d())
					val at = Vec3(game.x + center.x, game.y + center.y, game.z + center.z)
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

	/**
	 * The view as the reference sees it, for a look: per grid pixel (ARGB) the hit block's map colour shaded by its face
	 * (fluids passed through), light blue for paths that escape, black for captured ones, grey past [maxLength].
	 */
	fun picture(minecraft: Minecraft, hole: BlackHole, columns: Int, rows: Int, maxLength: Double, only: IntRange): IntArray {
		val camera = minecraft.gameRenderer.mainCamera
		val eye = camera.position
		val look = Vector3d(camera.lookVector)
		val up = Vector3d(camera.upVector)
		val right = Vector3d(camera.leftVector).negate()
		val tanHalf = tan(Math.toRadians(minecraft.options.fov().get().toDouble()) / 2.0)
		val aspect = minecraft.window.width.toDouble() / minecraft.window.height
		val level = minecraft.level!!
		val capture = 1.02 * hole.equatorialExtent(hole.horizonRadius)
		val origin = DoubleArray(3)
		GameFrame.toPhysics(Vector3d(eye.x - hole.x, eye.y - hole.y, eye.z - hole.z), origin)
		val out = IntArray(columns * rows)
		for (row in only) for (column in 0 until columns) {
			val x = ((column + 0.5) / columns * 2.0 - 1.0) * tanHalf * aspect
			val y = (1.0 - (row + 0.5) / rows * 2.0) * tanHalf
			val view = Vector3d(look).add(Vector3d(right).mul(x)).add(Vector3d(up).mul(y)).normalize()
			val direction = DoubleArray(3)
			GameFrame.toPhysics(view, direction)
			val path = NullRay.trace(hole.spacetime(), origin, direction, maxLength, capture, maxSegment = 0.5, arriving = true)
			var color = if (path.end == NullRay.End.CAPTURED) 0xFF000000.toInt() else 0xFF99BBFF.toInt()
			var previous: Vec3? = null
			for (point in path.points) {
				val game = GameFrame.toGame(point, 0, Vector3d())
				val at = Vec3(game.x + hole.x, game.y + hole.y, game.z + hole.z)
				val from = previous
				previous = at
				if (from == null) continue
				val hit = level.clip(ClipContext(from, at, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()))
				if (hit.type == HitResult.Type.BLOCK) {
					val blockHit = hit as BlockHitResult
					val state = level.getBlockState(blockHit.blockPos)
					val shade = when (blockHit.direction) {
						net.minecraft.core.Direction.UP -> 1.0
						net.minecraft.core.Direction.DOWN -> 0.5
						net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH -> 0.8
						else -> 0.6
					}
					val map = state.getMapColor(level, blockHit.blockPos).col
					color = (0xFF shl 24) or
						(((map shr 16 and 0xFF) * shade).toInt() shl 16) or (((map shr 8 and 0xFF) * shade).toInt() shl 8) or ((map and 0xFF) * shade).toInt()
					break
				}
			}
			out[row * columns + column] = color
		}
		return out
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
