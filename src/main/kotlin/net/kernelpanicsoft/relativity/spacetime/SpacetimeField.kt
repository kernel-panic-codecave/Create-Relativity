package net.kernelpanicsoft.relativity.spacetime

import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.entity.BlackHoleEntity
import net.kernelpanicsoft.relativity.entity.RelativityEntities
import net.kernelpanicsoft.relativity.physics.geodesic.CoordinateAcceleration
import net.kernelpanicsoft.relativity.physics.geodesic.Geodesic
import net.kernelpanicsoft.relativity.physics.spacetime.field.ComposedSpacetime
import net.kernelpanicsoft.relativity.physics.spacetime.field.FieldSample
import net.kernelpanicsoft.relativity.physics.spacetime.field.KerrSource
import net.kernelpanicsoft.relativity.physics.spacetime.field.MetricSource
import net.kernelpanicsoft.relativity.physics.spacetime.field.WarpEllipsoid
import net.kernelpanicsoft.relativity.physics.spacetime.field.WarpTube
import net.kernelpanicsoft.relativity.physics.weakfield.PostNewtonian
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import org.joml.Vector3d
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent
import org.joml.Vector3dc
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sqrt

/**
 * The one place the rest of the mod asks what spacetime is doing at a point. Black holes are entities
 * ([BlackHoleEntity]); this keeps an index of them per level, on both sides, and hands out snapshots.
 */
object SpacetimeField {
	/** Live black-hole entities per level (both sides), kept by entity join/leave events. */
	// Both maps are shared by the client and server threads in singleplayer (each level's entry by only one of them).
	private val index: MutableMap<Level, MutableSet<BlackHoleEntity>> = Collections.synchronizedMap(WeakHashMap())

	/** Snapshots for the current tick, rebuilt when a tick passes or a hole is added, removed or changed. */
	private val cache: MutableMap<Level, Pair<Long, List<BlackHole>>> = Collections.synchronizedMap(WeakHashMap())
	@Volatile private var generation = 0L

	/**
	 * Inside this fraction of `r_s` the field isn't evaluated: the point is well inside the horizon, where
	 * anything is already captured, and the connection diverges toward the singularity.
	 */
	private const val SINGULARITY_CUTOFF = 0.1

	/** Pulls weaker than this (blocks/s², ~1/3000 of vanilla gravity) are ignored, so distant holes cost nothing. */
	private const val MIN_ACCELERATION = 0.01

	/** Beyond this many r_s the cheap 1PN acceleration replaces the exact geodesic one (error ~ (r_s/r)²). */
	private const val WEAK_FIELD_RADII = 50.0

	/** Things this deep inside the horizon (fraction of r_s, spheroidal radius) have reached the singularity. */
	const val SINGULARITY_RADIUS = 0.15

	fun onEntityJoin(event: EntityJoinLevelEvent) {
		val hole = event.entity as? BlackHoleEntity ?: return
		index.getOrPut(event.level) { LinkedHashSet() }.add(hole)
		generation++
	}

	fun onEntityLeave(event: EntityLeaveLevelEvent) {
		val hole = event.entity as? BlackHoleEntity ?: return
		index[event.level]?.remove(hole)
		generation++
	}

	fun blackHoles(level: Level): List<BlackHole> {
		val stamp = level.gameTime * 1_000_003L + generation
		cache[level]?.let { (at, holes) -> if (at == stamp) return holes }
		val holes = index[level].orEmpty().filter { !it.isRemoved }.map { it.snapshot() }
		cache[level] = stamp to holes
		return holes
	}

	private fun entity(level: Level, id: String): BlackHoleEntity? =
		index[level].orEmpty().firstOrNull { it.stringUUID == id && !it.isRemoved }

	fun addBlackHole(level: ServerLevel, hole: BlackHole) {
		val entity = RelativityEntities.BLACK_HOLE.get().create(level) ?: return
		entity.setPos(hole.x, hole.y, hole.z)
		entity.apply(hole, setSpin = true)
		level.addFreshEntity(entity)
	}

	/** Applies [change] to the hole with [id], if it still exists. */
	fun updateBlackHole(level: ServerLevel, id: String, change: (BlackHole) -> BlackHole) {
		val entity = entity(level, id) ?: return
		entity.apply(change(entity.snapshot()))
		generation++
	}

	fun removeBlackHoles(level: ServerLevel, predicate: (BlackHole) -> Boolean): Int {
		val doomed = index[level].orEmpty().filter { !it.isRemoved && predicate(it.snapshot()) }
		doomed.forEach { it.discard() }
		return doomed.size
	}

	private val providers: MutableList<FieldSource.Provider> = CopyOnWriteArrayList()

	/** Adds a supplier of non-hole sources (gravity generators, warp drives, tests); [removeProvider] takes it away. */
	fun addProvider(provider: FieldSource.Provider) {
		providers += provider
	}

	fun removeProvider(provider: FieldSource.Provider) {
		providers -= provider
	}

	/** Whether nothing curves spacetime in [level]: no holes, no other sources. */
	fun isFlat(level: Level): Boolean = blackHoles(level).isEmpty() && fieldSources(level).isEmpty()

	/** Every source in [level]: its holes first, then the rest ([fieldSources]). */
	fun sources(level: Level): List<FieldSource> = blackHoles(level) + fieldSources(level)

	/** Every non-hole source in [level]. */
	fun fieldSources(level: Level): List<FieldSource> =
		if (providers.isEmpty()) emptyList() else providers.flatMap { it.sources(level) }

	/**
	 * The curvature sources that matter at [position]: each hole as a [KerrSource] centred relative to [position]
	 * (physics axes, so the point itself is the origin and precision doesn't depend on how far out in the world it is),
	 * leaving out holes whose pull there is below [minPull] (blocks/s²) and holes whose singularity it's at; then every
	 * other source ([fieldSources]) as strong as that.
	 */
	private fun sourcesAt(level: Level, position: Vector3dc, minPull: Double): Sources {
		val holes = blackHoles(level)
		val others = fieldSources(level)
		if (holes.isEmpty() && others.isEmpty()) return Sources.NONE
		val c = RelativityConfig.speedOfLight(level.dimension())
		val rel = Vector3d()
		val p = DoubleArray(3)
		val out = Sources()
		for (hole in holes) {
			Vector3d(hole.x, hole.y, hole.z).sub(position, rel)
			val r = rel.length()
			if (r < SINGULARITY_CUTOFF * hole.schwarzschildRadius) continue
			if (c * c * hole.mass / (r * r) < minPull) continue
			GameFrame.toPhysics(rel, p)
			out.holes += hole to KerrSource(p[0], p[1], p[2], hole.mass, hole.spin * hole.mass)
		}
		// The section's lattice for the gridded sources it holds (FieldGrid), once built; the rest analytic.
		val section = FieldGrid.sectionAt(level, others, position)
		if (section != null) {
			val shift = DoubleArray(3)
			GameFrame.toPhysics(Vector3d(position).sub(section.origin), shift)
			out.others += FieldGrid.Shifted(section.field, shift[0], shift[1], shift[2])
		}
		for (source in others) {
			if (section != null && source in section.sources) continue
			if (c * c * source.strengthAt(position) < minPull || source.strengthAt(position) == 0.0) continue
			out.others += source.toPhysics(position)
		}
		return out
	}

	private class Sources {
		val holes: MutableList<Pair<BlackHole, KerrSource>> = ArrayList()
		val others: MutableList<MetricSource> = ArrayList()
		fun isEmpty() = holes.isEmpty() && others.isEmpty()
		fun all() = holes.map { it.second } + others

		companion object {
			val NONE = Sources()
		}
	}

	/**
	 * Coordinate acceleration (blocks/s²) of a free body at [position] (blocks) moving at [velocity] (blocks/s): the
	 * geodesic acceleration in the spacetime of every hole together ([ComposedSpacetime]: exact near one hole, the
	 * weak-field sum between several). Far from all of them, the cheap 1PN sum. Writes into [dest] and returns it.
	 */
	fun acceleration(level: Level, position: Vector3dc, velocity: Vector3dc, dest: Vector3d): Vector3d {
		dest.zero()
		val sources = sourcesAt(level, position, MIN_ACCELERATION)
		if (sources.isEmpty()) return dest
		val c = RelativityConfig.speedOfLight(level.dimension())
		val v = DoubleArray(3)
		val a = DoubleArray(3)
		val add = Vector3d()
		GameFrame.toPhysics(Vector3d(velocity).div(c), v)
		val weak = sources.others.isEmpty() && sources.holes.all { (hole, source) ->
			sqrt(source.cx * source.cx + source.cy * source.cy + source.cz * source.cz) > WEAK_FIELD_RADII * hole.schwarzschildRadius
		}
		if (weak) {
			for ((hole, source) in sources.holes) {
				PostNewtonian.acceleration(hole.mass, doubleArrayOf(-source.cx, -source.cy, -source.cz), v, a)
				dest.add(GameFrame.toGame(a, 0, add).mul(c * c))
			}
			return dest
		}
		CoordinateAcceleration(ComposedSpacetime(sources.all())).compute(DoubleArray(4), v, a)
		return dest.add(GameFrame.toGame(a, 0, add).mul(c * c))
	}

	/**
	 * The flow of space at [position] (the river picture): how fast the field's own observers (Eulerian: those it
	 * carries, as a warp bubble its riders) move there, in units of c, game axes. Inward round a hole (twisted round a
	 * spinning one: frame dragging), along the push in a warp bubble or tube, along a Krasnikov tube; none round a mass.
	 */
	fun flow(level: Level, position: Vector3dc, dest: Vector3d): Vector3d {
		dest.zero()
		val sources = sourcesAt(level, position, 0.0)
		if (sources.isEmpty()) return dest
		val sample = FieldSample()
		ComposedSpacetime(sources.all()).inverse(DoubleArray(4), sample)
		val gtt = sample.value[0]
		if (gtt >= 0.0) return dest
		// dx/dt of the observers along g^{μt}: g^{ti} / g^{tt}.
		val v = doubleArrayOf(sample.value[1] / gtt, sample.value[2] / gtt, sample.value[3] / gtt)
		return GameFrame.toGame(v, 0, dest)
	}

	/**
	 * How fast a clock at [position] moving at [velocity] (blocks/s) runs against world time: 1 far from any hole,
	 * less near one or at speed, 0 at a horizon. In the spacetime of every hole together; with none, it's just
	 * special-relativistic time dilation.
	 */
	fun properTimeRate(level: Level, position: Vector3dc, velocity: Vector3dc): Double {
		val c = RelativityConfig.speedOfLight(level.dimension())
		val v = DoubleArray(3)
		GameFrame.toPhysics(Vector3d(velocity).div(c), v)
		// At a singularity (left out of the sources) no clock runs, as anywhere inside a horizon.
		if (blackHoles(level).any { position.distance(it.x, it.y, it.z) < SINGULARITY_CUTOFF * it.schwarzschildRadius }) return 0.0
		val sources = sourcesAt(level, position, 0.0)
		if (sources.isEmpty()) {
			val beta2 = minOf(v[0] * v[0] + v[1] * v[1] + v[2] * v[2], 1.0)
			return sqrt(1.0 - beta2)
		}
		val spacetime = ComposedSpacetime(sources.all())
		val rate = Geodesic.properTimeRate(spacetime, DoubleArray(4), v)
		if (rate > 0.0 || sources.others.none { it is WarpEllipsoid || it is WarpTube }) return rate
		// Not timelike: at rest (or too slow) against a warp push faster than light, which nothing can be (a bubble
		// standing still, as `relativity bubble add` places, flows past what's in it). It rides the push instead, as a
		// ship's riders do, on the clock of the push's own observer: the lapse, 1 / sqrt(−g^tt) (1 for a warp push).
		// (Not behind a horizon: there no clock runs.)
		if (spacetime.captures(DoubleArray(4))) return 0.0
		val sample = FieldSample()
		spacetime.inverse(DoubleArray(4), sample)
		return if (sample.value[0] < 0.0) 1.0 / sqrt(-sample.value[0]) else 0.0
	}

	/**
	 * Outward radiation-pressure acceleration (blocks/s²) at [position] from every feeding hole: the hole's pull
	 * scaled by its Eddington ratio λ = Ṁ/Ṁ_Edd and the matter's [coupling]. At λ·coupling = 1 it cancels gravity.
	 */
	fun radiationAcceleration(level: Level, position: Vector3dc, coupling: Double, dest: Vector3d): Vector3d {
		dest.zero()
		val c = RelativityConfig.speedOfLight(level.dimension())
		val rel = Vector3d()
		for (hole in blackHoles(level)) {
			if (hole.accretionRate <= 0.0) continue
			val ratio = hole.accretionRate / RelativityConfig.eddingtonRate(hole.schwarzschildRadius)
			position.sub(hole.x, hole.y, hole.z, rel)
			val r2 = rel.lengthSquared()
			if (r2 < 1e-6) continue
			val push = ratio * coupling * c * c * hole.mass / r2
			if (push < MIN_ACCELERATION) continue
			dest.add(rel.normalize(push))
		}
		return dest
	}

	/** The hole whose horizon [position] is inside, if any. */
	fun insideHorizon(level: Level, position: Vector3dc): BlackHole? =
		blackHoles(level).firstOrNull { spheroidalRadius(it, position) < it.horizonRadius }

	/**
	 * The hole whose horizon the straight path [from] → [to] entered, if any: a swept test, so something moving
	 * several blocks a tick can't skip over a small horizon between ticks. Treats the horizon as a sphere of its
	 * polar (smallest) Kerr–Schild extent, r₊.
	 */
	fun crossedHorizon(level: Level, from: Vector3dc, to: Vector3dc): BlackHole? =
		blackHoles(level).firstOrNull { closestApproach(it, from, to) < it.horizonRadius }

	private fun closestApproach(hole: BlackHole, from: Vector3dc, to: Vector3dc): Double {
		val seg = Vector3d(to).sub(from)
		val rel = Vector3d(hole.x, hole.y, hole.z).sub(from)
		val len2 = seg.lengthSquared()
		val t = if (len2 > 0.0) (rel.dot(seg) / len2).coerceIn(0.0, 1.0) else 0.0
		return Vector3d(from).fma(t, seg).distance(hole.x, hole.y, hole.z)
	}

	/** The hole whose singularity [position] has reached, if any. */
	fun atSingularity(level: Level, position: Vector3dc): BlackHole? =
		blackHoles(level).firstOrNull { spheroidalRadius(it, position) < SINGULARITY_RADIUS * it.schwarzschildRadius }

	private fun spheroidalRadius(hole: BlackHole, position: Vector3dc): Double {
		val p = DoubleArray(3)
		GameFrame.toPhysics(Vector3d(position).sub(hole.x, hole.y, hole.z), p)
		return hole.spacetime().radius(p[0], p[1], p[2])
	}
}
