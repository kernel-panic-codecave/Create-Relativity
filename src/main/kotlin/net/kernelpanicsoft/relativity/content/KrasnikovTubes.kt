package net.kernelpanicsoft.relativity.content

import com.simibubi.create.Create
import com.simibubi.create.content.trains.entity.Train
import com.simibubi.create.content.trains.graph.TrackEdge
import com.simibubi.create.content.trains.graph.TrackNode
import net.createmod.catnip.data.Couple
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.mixin.create.NavigationAccessor
import net.kernelpanicsoft.relativity.spacetime.FieldSource
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.minecraft.world.level.Level
import org.joml.Vector3d
import org.joml.Vector3dc
import java.util.WeakHashMap

/**
 * Krasnikov tubes: Krasnikov track carries a tube for each maglev train scheduled over it, while it runs its route
 * (station to station): along the Krasnikov track of its route ahead (the edge it's on, then its navigation's path),
 * fast the way it's going. On it, the train runs at up to just under the tube's speed ([topSpeed]). Each train's tube
 * is its own and lasts only while it's under way, so routes may cross, merge and share track freely (opposed tubes at
 * rest together can't reach the past either). Nothing laid, nothing saved. Worked out once a tick per level.
 *
 * Tubes form and fade, never appear: a forming tube's front runs out along the route from the train at the tube's own
 * light speed, c / k (it's extended from inside itself, where signals run that fast; at c, a train faster than light
 * would catch it), its tilt easing in over [RAMP_TICKS] (k from 1, flat, to the config's); once the train's route
 * ends, its last tube eases back to flat as long.
 */
object KrasnikovTubes {
	/** The tube's radius (blocks): round a carriage's body, as a warp tube's. */
	private const val RADIUS = 3.0

	/** How far above the track the spine runs (blocks): the carriages' middles. */
	private const val LIFT = 1.0

	/** A train in a tube runs at up to this share of its light speed along it (just under: its clocks stay defined). */
	private const val SHARE = 0.9

	/** How long a train takes to reach the tube's speed from rest (ticks), at the least. */
	private const val SPOOL_TICKS = 200.0

	/** How long a tube's tilt takes to ease in, or out (ticks). */
	private const val RAMP_TICKS = 20.0

	/** A train's tube: when it began forming, and (once its route ended) its last spines and when it began to fade. */
	private class Tube(val start: Long) {
		var strength = 0.0
		var spines: List<List<Vector3dc>> = emptyList()
		var fadeFrom = -1L
		var fadeStrength = 0.0
	}

	/** Each level's trains' tubes (by level: in singleplayer, a train's client and server copies share an id). */
	private val formingByLevel = WeakHashMap<Level, HashMap<java.util.UUID, Tube>>()

	/** An edge travelled from [from] to [to]. */
	private data class Directed(val from: TrackNode, val to: TrackNode)

	/** Each train under way in its tube (its tube's k there), and the tubes. */
	private class Cached(val tick: Long, val inTube: Map<java.util.UUID, Double>, val tubes: List<FieldSource.Krasnikov>)

	private val cache = WeakHashMap<Level, Cached>()

	fun init() {
		SpacetimeField.addProvider { level -> cached(level).tubes }
	}

	/** The tubes in [level]. */
	fun tubes(level: Level): List<FieldSource.Krasnikov> = cached(level).tubes

	/** The tube's k: light along it runs at 1 / k (the config's speed, times c). */
	fun k(): Double = 1.0 / RelativityConfig.Server.Trains.krasnikovSpeed.coerceAtLeast(1.0)

	@Synchronized
	private fun cached(level: Level): Cached {
		// (A client's copies of trains don't know their routes: its tubes are the server's, sent: KrasnikovSync.)
		if (level.isClientSide) return Cached(level.gameTime, emptyMap(), KrasnikovSync.received(level))
		val tick = level.gameTime
		cache[level]?.takeIf { it.tick == tick }?.let { return it }
		val made = compute(level)
		cache[level] = made
		return made
	}

	private fun trains(level: Level): Collection<Train> {
		val railways = if (level.isClientSide) com.simibubi.create.CreateClient.RAILWAYS else Create.RAILWAYS
		return railways.trains.values.filter { train -> train.carriages.any { it.getDimensionalIfPresent(level.dimension()) != null } }
	}

	/**
	 * [train]'s route ahead as directed edges: the one it's on, then on along the graph to its destination (its
	 * navigation's path names only the choices at junctions; between them, the one way on).
	 */
	private fun route(train: Train): List<Directed> {
		val navigation = train.navigation ?: return emptyList()
		if (navigation.destination == null) return emptyList()
		val graph = train.graph ?: return emptyList()
		val choices = (navigation as NavigationAccessor).`create_relativity$getCurrentPath`() ?: emptyList<Couple<TrackNode>>()
		val point = (if (train.speed >= 0) train.carriages.firstOrNull()?.leadingPoint else train.carriages.lastOrNull()?.trailingPoint)
			?: return emptyList()
		val node1 = point.node1 ?: return emptyList()
		val node2 = point.node2 ?: return emptyList()
		// The edge it's on, toward the first choice's node if that's one of its ends, else the way it's going.
		val first = choices.firstOrNull()?.first
		var here = when {
			first === node1 -> Directed(node2, node1)
			first === node2 -> Directed(node1, node2)
			train.speed >= 0 -> Directed(node1, node2)
			else -> Directed(node2, node1)
		}
		val route = arrayListOf(here)
		var left = navigation.distanceToDestination + 1.0
		var choice = 0
		while (left > 0 && route.size < MAX_EDGES) {
			val onward = graph.getConnectionsFrom(here.to)?.keys?.filter { it !== here.from } ?: break
			val next = when {
				onward.isEmpty() -> break
				choice < choices.size && choices[choice].first === here.to -> choices[choice++].second
				onward.size == 1 -> onward.single()
				else -> break
			}
			val edge = graph.getConnection(Couple.create(here.to, next)) ?: break
			left -= edge.length
			here = Directed(here.to, next)
			route += here
		}
		return route
	}

	/** A route's most edges looked along (they're short: a node each 16 blocks or so on straight track). */
	private const val MAX_EDGES = 512

	private fun edge(train: Train, d: Directed): TrackEdge? = train.graph?.getConnection(Couple.create(d.from, d.to))

	private fun compute(level: Level): Cached {
		val tick = level.gameTime
		val forming = formingByLevel.getOrPut(level) { HashMap() }
		val c = RelativityConfig.speedOfLight(level.dimension())
		val inTube = HashMap<java.util.UUID, Double>()
		val tubes = ArrayList<FieldSource.Krasnikov>()
		val k = k()
		fun tilt(strength: Double) = 1.0 - (1.0 - k) * strength.coerceIn(0.0, 1.0)
		val active = HashSet<java.util.UUID>()
		for (train in trains(level)) {
			if (!MaglevBogey.isMaglev(train)) continue
			val route = route(train)
			if (route.none { edge(train, it)?.trackMaterial === Maglev.KRASNIKOV }) continue
			active += train.id
			val tube = forming[train.id]?.takeIf { it.fadeFrom < 0 } ?: Tube(tick).also { forming[train.id] = it }
			val age = (tick - tube.start).coerceAtLeast(0)
			tube.strength = age / RAMP_TICKS
			// Its front, out from the train at the tube's light speed (ahead of the train, at 90% of it).
			val front = c / k * age / 20.0
			val spines = ArrayList<List<Vector3dc>>()
			var run = ArrayList<Vector3dc>()
			fun close() {
				if (run.size >= 2) spines += run
				run = ArrayList()
			}
			// Round the train itself first: its bogeys' points from its tail to its head (the way it's going), past its
			// tail by its overhang; where they're on Krasnikov track.
			val body = body(train)
			for ((i, point) in body.withIndex()) {
				val krasnikov = point.second
				if (!krasnikov) {
					close()
					continue
				}
				if (run.isEmpty() && i + 1 < body.size) {
					// (The tail's overhang: the last carriage's body past its bogey.)
					val next = body[i + 1].first
					val back = Vector3d(point.first).sub(next)
					if (back.length() > 1e-6) run += Vector3d(point.first).add(back.normalize(OVERHANG))
				}
				if (run.isEmpty() || run.last().distance(point.first) > 0.25) run += point.first
			}
			var travelled = 0.0
			var last: Vector3dc? = body.lastOrNull()?.first
			// (On the edge the head is on, only what's ahead of it: the train's own stretch is the body's.)
			val head = if (train.speed >= 0) train.carriages.firstOrNull()?.leadingPoint else train.carriages.lastOrNull()?.trailingPoint
			outer@ for ((i, d) in route.withIndex()) {
				val krasnikov = edge(train, d)?.trackMaterial === Maglev.KRASNIKOV
				if (krasnikov && i == 0) inTube[train.id] = tilt(tube.strength)
				var along = 0.0
				var previous: Vector3dc? = null
				val start = if (i == 0 && head != null) (if (head.node1 === d.from) head.position else (edge(train, d)?.length ?: 0.0) - head.position) else 0.0
				for (p in polyline(train, d)) {
					previous?.let { along += it.distance(p) }
					previous = p
					if (along <= start) continue
					last?.let { travelled += it.distance(p) }
					last = p
					if (!krasnikov) continue
					if (travelled > front) break@outer
					if (run.isEmpty() || run.last().distance(p) > 0.25) run += p
				}
				if (!krasnikov) close()
			}
			close()
			tube.spines = spines
			for (spine in spines) tubes += FieldSource.Krasnikov(spine, tilt(tube.strength), RADIUS)
		}
		// Routes ended: their tubes fade.
		val gone = ArrayList<java.util.UUID>()
		for ((id, tube) in forming) {
			if (id in active) continue
			if (tube.fadeFrom < 0) {
				tube.fadeFrom = tick
				tube.fadeStrength = tube.strength.coerceIn(0.0, 1.0)
			}
			val strength = tube.fadeStrength - (tick - tube.fadeFrom) / RAMP_TICKS
			if (strength <= 0.0) { gone += id; continue }
			for (spine in tube.spines) tubes += FieldSource.Krasnikov(spine, tilt(strength), RADIUS)
		}
		gone.forEach { forming.remove(it) }
		return Cached(tick, inTube, tubes)
	}

	/** How far (blocks) a carriage's body runs past its end bogey: the tube reaches that far behind the train. */
	private const val OVERHANG = 2.5

	/**
	 * [train]'s bogeys' points from its tail to its head (by the way it's going, as [route] takes its head), lifted to
	 * the carriages' middles, each with whether its track is Krasnikov.
	 */
	private fun body(train: Train): List<Pair<Vector3dc, Boolean>> {
		val graph = train.graph ?: return emptyList()
		val points = ArrayList<com.simibubi.create.content.trains.entity.TravellingPoint>()
		// (Carriages run first to last from the train's front; each's leading point is toward it.)
		for (carriage in train.carriages) {
			points += carriage.leadingPoint
			points += carriage.trailingPoint
		}
		if (train.speed >= 0) points.reverse()
		return points.map { point ->
			val p = point.getPosition(graph)
			Vector3d(p.x, p.y + LIFT, p.z) as Vector3dc to (point.edge?.trackMaterial === Maglev.KRASNIKOV)
		}
	}

	/** [d]'s centre line, lifted to the carriages' middles: a turn's curve sampled a block apart, else straight. */
	private fun polyline(train: Train, d: Directed): List<Vector3dc> {
		val edge = edge(train, d) ?: return emptyList()
		val points = ArrayList<Vector3dc>()
		val turn = edge.turn
		if (edge.isTurn && turn != null) {
			val steps = maxOf(2, turn.length.toInt())
			// (A turn runs from its first node to its second; travelled the other way, reversed.)
			val forward = edge.node1 === d.from
			for (i in 0..steps) {
				val t = i.toDouble() / steps
				val p = turn.getPosition(if (forward) t else 1 - t)
				points += Vector3d(p.x, p.y + LIFT, p.z)
			}
		} else {
			for (node in listOf(d.from, d.to)) {
				val p = node.location.location
				points += Vector3d(p.x, p.y + LIFT, p.z)
			}
		}
		return points
	}

	/** Whether [train] is in its tube: running its route, on Krasnikov track. */
	@JvmStatic
	fun inTube(train: Train, level: Level?): Boolean = level != null && train.id in cached(level).inTube

	/** [train]'s top speed (blocks per tick) in a tube going its way: just under the tube's light speed; null if not in one. */
	@JvmStatic
	fun topSpeed(train: Train): Float? {
		val level = levelOf(train) ?: return null
		if (!MaglevBogey.isMaglev(train)) return null
		val k = cached(level).inTube[train.id] ?: return null
		val c = RelativityConfig.speedOfLight(level.dimension())
		return (SHARE * c / k / 20.0).toFloat()
	}

	/** [train]'s acceleration in a tube: to its top speed there in [SPOOL_TICKS] at the least. */
	@JvmStatic
	fun acceleration(train: Train, normal: Float): Float {
		val top = topSpeed(train) ?: return normal
		return maxOf(normal, (top / SPOOL_TICKS).toFloat())
	}

	/** The level [train] runs in (its first carriage's). */
	private fun levelOf(train: Train): Level? {
		val carriage = train.carriages.firstOrNull() ?: return null
		val entity = carriage.anyAvailableEntity() ?: return null
		return entity.level()
	}
}
