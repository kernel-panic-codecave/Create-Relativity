package net.kernelpanicsoft.relativity.content

import com.simibubi.create.Create
import com.simibubi.create.content.trains.entity.Train
import com.simibubi.create.content.trains.graph.TrackEdge
import com.simibubi.create.content.trains.graph.TrackGraph
import com.simibubi.create.content.trains.graph.TrackNode
import net.createmod.catnip.data.Couple
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.mixin.create.NavigationAccessor
import com.simibubi.create.content.trains.entity.TravellingPoint
import net.kernelpanicsoft.relativity.spacetime.Cubic
import net.kernelpanicsoft.relativity.spacetime.FieldSource
import net.kernelpanicsoft.relativity.spacetime.along
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
	const val RADIUS = 3.0

	/** How far above the track the spine runs (blocks): the carriages' middles. */
	const val LIFT = 1.0

	/** A train in a tube runs at up to this share of its light speed along it (just under: its clocks stay defined). */
	private const val SHARE = 0.9

	/** How long a train takes to reach the tube's speed from rest (ticks), at the least. */
	private const val SPOOL_TICKS = 200.0

	/** How long a tube's tilt takes to ease in, or out (ticks). */
	private const val RAMP_TICKS = 20.0

	/**
	 * A train's tube: when it began forming, how far before its destination it eases back to flat ([taper], blocks),
	 * and (once its route ended) its last spines (each its curve and the tilts at its pieces' ends) and when it began to
	 * fade.
	 */
	private class Tube(val start: Long, var taper: Double) {
		/** Whether [taper] is set yet: on the first tick the train moves (standing at its station, its navigation's
		 * distance to go is its path's cost, not the track's length). */
		var taperSet = false
		/** Its k (its network's warp factor's: KrasnikovNetworks), as of the last tick. */
		var k = 1.0
		var strength = 0.0
		/** Behind the train: the track its tail has covered (each piece, and whether it's Krasnikov track), back to the last signal it passed. */
		val trail = ArrayList<Pair<Cubic, Boolean>>()
		/** Where its tail was last tick: its edge's nodes, and its parameter along it from the first. */
		var tail: Triple<TrackNode, TrackNode, Double>? = null
		/** Which way it was going then (its speed's sign): the trail is begun again if it turns back. */
		var forward = true
		var spines: List<Pair<List<Cubic>, List<Double>>> = emptyList()
		var fadeFrom = -1L
		var fadeStrength = 0.0
	}

	/** Each level's trains' tubes (by level: in singleplayer, a train's client and server copies share an id). */
	private val formingByLevel = WeakHashMap<Level, HashMap<java.util.UUID, Tube>>()

	/** An edge travelled from [from] to [to]. */
	data class Directed(val from: TrackNode, val to: TrackNode)

	/** The train a tube runs round, and its pieces round the train's body ([from] until [to]: the client fits its own there). */
	data class Owner(val train: java.util.UUID, val from: Int, val to: Int)

	/**
	 * Each train under way in its tube (its tube's k there), the tubes, and each tube's train where it runs round it
	 * (null for the rest of its route, past a gap, and for fading tubes).
	 */
	private class Cached(val tick: Long, val inTube: Map<java.util.UUID, Double>, val tubes: List<FieldSource.Krasnikov>, val owners: List<Owner?>)

	private val cache = WeakHashMap<Level, Cached>()

	fun init() {
		SpacetimeField.addProvider { level -> cached(level).tubes }
	}

	/** The tubes in [level]. */
	fun tubes(level: Level): List<FieldSource.Krasnikov> = cached(level).tubes

	/** The tubes in [level], each with its train where it runs round it (KrasnikovSync sends both). */
	fun owned(level: Level): List<Pair<Owner?, FieldSource.Krasnikov>> = cached(level).let { it.owners.zip(it.tubes) }

	/** [train]'s tube's k: its route's first Krasnikov network's warp factor's (KrasnikovNetworks), else [k]. */
	private fun kOf(train: Train, route: List<Directed>): Double {
		val graph = train.graph ?: return k()
		val first = route.firstOrNull { edge(train, it)?.trackMaterial === Maglev.KRASNIKOV } ?: return k()
		return 1.0 / KrasnikovNetworks.speed(graph, first.from)
	}

	/** A new network's tube's k: light along it runs at 1 / k times c (the config's warp factor, on the warp scale; under 10). */
	fun k(): Double = 1.0 / net.kernelpanicsoft.relativity.physics.WarpScale.speed(RelativityConfig.Server.Trains.krasnikovWarp.coerceIn(1.0, MAX_WARP))

	/** The highest warp a tube may run at: warp 10 is instant, and an instant tube would let light reach its own past. */
	const val MAX_WARP = 9.99

	@Synchronized
	private fun cached(level: Level): Cached {
		val tick = level.gameTime
		cache[level]?.takeIf { it.tick == tick }?.let { return it }
		// (A client's copies of trains don't know their routes: its tubes are the server's, sent (KrasnikovSync), each
		// round its own train where it is here: the server's is a tick or more ahead, and drawn between ticks.)
		if (level.isClientSide) {
			val received = KrasnikovSync.received(level)
			val made = Cached(tick, emptyMap(), received.map { (owner, tube) -> owner?.let { aroundClientTrain(it, tube) } ?: tube }, received.map { it.first })
			cache[level] = made
			return made
		}
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
	fun route(train: Train): List<Directed> {
		val navigation = train.navigation ?: return emptyList()
		if (navigation.destination == null) return emptyList()
		val graph = train.graph ?: return emptyList()
		val choices = (navigation as NavigationAccessor).`create_relativity$getCurrentPath`() ?: emptyList<Couple<TrackNode>>()
		val point = (if (forward(train)) train.carriages.firstOrNull()?.leadingPoint else train.carriages.lastOrNull()?.trailingPoint)
			?: return emptyList()
		val node1 = point.node1 ?: return emptyList()
		val node2 = point.node2 ?: return emptyList()
		// The edge it's on, toward the first choice's node if that's one of its ends, else the way it's going.
		val first = choices.firstOrNull()?.first
		var here = when {
			first === node1 -> Directed(node2, node1)
			first === node2 -> Directed(node1, node2)
			forward(train) -> Directed(node1, node2)
			else -> Directed(node2, node1)
		}
		val route = arrayListOf(here)
		var left = navigation.distanceToDestination + 1.0
		var choice = 0
		while (left > 0 && route.size < MAX_EDGES) {
			// (The ways on that carry the track on smoothly: Create names a choice only where there's more than one.)
			val onward = onward(graph, here)
			val next = when {
				choice < choices.size && choices[choice].first === here.to -> choices[choice++].second
				onward.isEmpty() -> break
				onward.size == 1 -> onward.single().to
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

	fun edge(train: Train, d: Directed): TrackEdge? = train.graph?.getConnection(Couple.create(d.from, d.to))

	private fun compute(level: Level): Cached {
		val tick = level.gameTime
		val forming = formingByLevel.getOrPut(level) { HashMap() }
		val c = RelativityConfig.speedOfLight(level.dimension())
		val inTube = HashMap<java.util.UUID, Double>()
		val tubes = ArrayList<FieldSource.Krasnikov>()
		val owners = ArrayList<Owner?>()
		fun tilt(k: Double, strength: Double) = 1.0 - (1.0 - k) * strength.coerceIn(0.0, 1.0)
		val active = HashSet<java.util.UUID>()
		for (train in trains(level)) {
			if (!MaglevBogey.isMaglev(train)) continue
			val route = route(train)
			if (route.none { edge(train, it)?.trackMaterial === Maglev.KRASNIKOV }) continue
			active += train.id
			// (At its Krasnikov network's warp factor: the first on its route's.)
			val k = kOf(train, route)
			val left = train.navigation?.distanceToDestination ?: 0.0
			val tube = forming[train.id]?.takeIf { it.fadeFrom < 0 } ?: Tube(tick, taper(level, left, k)).also { forming[train.id] = it }
			tube.k = k
			if (!tube.taperSet) {
				tube.taper = taper(level, left, k)
				tube.taperSet = train.speed != 0.0
			}
			val age = (tick - tube.start).coerceAtLeast(0)
			tube.strength = age / RAMP_TICKS
			// Its front, out from the train at the tube's light speed (ahead of the train, at 90% of it).
			val front = c / k * age / 20.0
			// Its tilt where it's [ahead] blocks on from the train's head: full, easing back to flat over the last [taper]
			// before the destination (where the train brakes: its top speed follows), all eased in as the tube forms.
			fun tiltAt(ahead: Double) = tilt(k, tube.strength * ((left - ahead) / tube.taper).coerceIn(0.0, 1.0))
			val spines = ArrayList<Pair<List<Cubic>, List<Double>>>()
			var owned = -1
			var run = ArrayList<Cubic>()
			var tilts = ArrayList<Double>()
			fun close() {
				if (run.isNotEmpty()) spines += run to tilts
				run = ArrayList()
				tilts = ArrayList()
			}
			// Each piece added at how far on from the head its end is.
			fun add(piece: Cubic, end: Double) {
				if (run.isEmpty()) tilts += tiltAt(end - piece.length())
				run += piece
				tilts += tiltAt(end)
			}
			// Behind it, the track its tail has covered since it set off (from past its tail then, by its overhang), back
			// to the last signal it passed: the blocks behind fall free for other trains as it passes on.
			extendTrail(train, tube)
			// Then round the train itself, from its tail to its head (the way it's going), where it's Krasnikov track.
			// (Past its tail by its overhang till the trail's begun.)
			val body = bodyRuns(train, if (tube.tail == null) OVERHANG else 0.0)
			var at = -tube.trail.sumOf { it.first.length() } - body.runs.sumOf { part -> part.sumOf { it.length() } }
			for ((piece, krasnikov) in tube.trail) {
				at += piece.length()
				if (krasnikov) add(piece, at) else close()
			}
			var bodyFrom = 0
			for (part in body.runs) {
				// (On from the trail where it meets the tail.)
				if (run.isEmpty() || run.last().d.distance(part.first().a) > 0.5) close()
				bodyFrom = run.size
				for (piece in part) {
					at += piece.length()
					add(piece, at)
				}
			}
			val bodyTo = run.size
			if (body.headOnKrasnikov) owned = spines.size else close()
			// Then on along its route, from its head, out to the tube's front.
			val head = head(train)
			var travelled = 0.0
			outer@ for ((i, d) in route.withIndex()) {
				val edge = edge(train, d) ?: break
				val krasnikov = edge.trackMaterial === Maglev.KRASNIKOV
				if (krasnikov && i == 0) inTube[train.id] = tiltAt(0.0)
				val forward = edge.node1 === d.from
				val start = if (i == 0 && head != null) paramOn(edge, head) else if (forward) 0.0 else 1.0
				var piece = curve(edge).part(start, if (forward) 1.0 else 0.0)
				val length = piece.length()
				if (travelled + length > front) piece = piece.part(0.0, ((front - travelled) / length).coerceIn(0.0, 1.0))
				val end = travelled + piece.length()
				travelled += length
				if (!krasnikov) {
					close()
				} else if (piece.length() > 1e-3) {
					add(piece, end)
				}
				if (travelled > front) break@outer
			}
			close()
			tube.spines = spines
			for ((index, spine) in spines.withIndex()) {
				tubes += FieldSource.Krasnikov(spine.first, tilt(k, tube.strength), RADIUS, spine.second)
				owners += if (index == owned) Owner(train.id, bodyFrom, bodyTo) else null
			}
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
			// (Its tilts eased back toward flat together.)
			val share = if (tube.fadeStrength > 0) strength / tube.fadeStrength else 0.0
			for ((curve, tilts) in tube.spines) {
				tubes += FieldSource.Krasnikov(curve, tilt(tube.k, strength), RADIUS, tilts.map { 1.0 - (1.0 - it) * share })
				owners += null
			}
		}
		gone.forEach { forming.remove(it) }
		return Cached(tick, inTube, tubes, owners)
	}

	/**
	 * How far before its destination a tube eases back to flat (blocks): where a train braking from the tube's speed
	 * would ([SPOOL_TICKS] to stop as to start), half the route at most (it brakes from wherever it got to).
	 */
	private fun taper(level: Level, route: Double, k: Double): Double {
		val top = SHARE * RelativityConfig.speedOfLight(level.dimension()) / k / 20.0
		return minOf(top * SPOOL_TICKS / 2.0, route / 2.0).coerceAtLeast(1.0)
	}

	/** How far (blocks) a carriage's body runs past its end bogey: the tube reaches that far behind the train. */
	private const val OVERHANG = 2.5

	/**
	 * [edge]'s centre line from its first node to its second, lifted to the carriages' middles: where a train's points
	 * ride (the track's line, up its normal: TravellingPoint.getPosition), [LIFT] higher. One cubic, exact on straights
	 * and turns (Create's turns are cubic Béziers in their parameter), close on banked ones (the normal turns too).
	 */
	fun curve(edge: TrackEdge): Cubic {
		fun at(t: Double): Vector3dc {
			val p = edge.getPosition(null, t).add(edge.getNormal(null, t))
			return Vector3d(p.x, p.y + LIFT, p.z)
		}
		return Cubic.through(at(0.0), at(1.0 / 3.0), at(2.0 / 3.0), at(1.0))
	}

	/** Where [point] is along [edge] (its parameter there, from its first node), [edge] being its edge either way round. */
	fun paramOn(edge: TrackEdge, point: TravellingPoint): Double {
		val pointEdge = point.edge ?: return 0.0
		val t = point.position / pointEdge.length.coerceAtLeast(1e-9)
		return if (pointEdge.node1 === edge.node1) t else 1.0 - t
	}

	/**
	 * Which way [train] goes: its speed's sign while it moves; standing, the way its navigation will send it (its
	 * destination ahead of it or behind).
	 */
	fun forward(train: Train): Boolean =
		if (train.speed != 0.0) train.speed > 0 else !(train.navigation?.destination != null && train.navigation.destinationBehindTrain)

	/** The point of [train] at its back, the way it's going (its head's opposite). */
	fun tail(train: Train): TravellingPoint? =
		if (forward(train)) train.carriages.lastOrNull()?.trailingPoint else train.carriages.firstOrNull()?.leadingPoint

	/** A stretch of [edge] (from its first node) from parameter [t0] to [t1]. */
	class Span(val edge: TrackEdge, val t0: Double, val t1: Double) {
		fun piece(): Cubic = curve(edge).part(t0, t1)
	}

	/** Track going on smoothly (as a train can: its curve's direction carried on, as Create's own navigation takes it). */
	private const val SMOOTH = 0.875

	/** The ways on from [d]'s end that carry its curve on smoothly (by the curves' own directions where they meet). */
	private fun onward(graph: TrackGraph, d: Directed): List<Directed> {
		val edge = graph.getConnection(Couple.create(d.from, d.to)) ?: return emptyList()
		val out = curve(edge).derivative(1.0).normalize()
		return graph.getConnectionsFrom(d.to)?.entries?.mapNotNull { (node, next) ->
			if (node === d.from) return@mapNotNull null
			val into = curve(next).derivative(0.0).normalize()
			if (out.dot(into) >= SMOOTH) Directed(d.to, node) else null
		} ?: emptyList()
	}

	/**
	 * The track from [a] to [b] (each a point on an edge) along the graph, going on smoothly, as spans of its edges'
	 * curves: the shortest way, through edges [reach] blocks long at most between; null if there's none.
	 */
	private fun stretch(graph: TrackGraph, a: TravellingPoint, b: TravellingPoint, reach: Double): List<Span>? {
		val ae = a.edge ?: return null
		val be = b.edge ?: return null
		return stretch(
			graph, a.node1 ?: return null, a.node2 ?: return null, a.position / ae.length.coerceAtLeast(1e-9),
			b.node1 ?: return null, b.node2 ?: return null, b.position / be.length.coerceAtLeast(1e-9), reach,
		)
	}

	/** As [stretch], from parameter [at] along edge [an1]–[an2] (from [an1]) to [bt] along [bn1]–[bn2] (from [bn1]). */
	private fun stretch(graph: TrackGraph, an1: TrackNode, an2: TrackNode, at: Double, bn1: TrackNode, bn2: TrackNode, bt: Double, reach: Double): List<Span>? {
		fun same(d: Directed) = (d.from === bn1 && d.to === bn2) || (d.from === bn2 && d.to === bn1)
		fun paramFrom(d: Directed, n1: TrackNode, t: Double) = if (d.from === n1) t else 1.0 - t
		if (same(Directed(an1, an2))) {
			val edge = graph.getConnection(Couple.create(an1, an2)) ?: return null
			return listOf(Span(edge, at, if (bn1 === an1) bt else 1.0 - bt))
		}
		var best: List<Span>? = null
		var bestLength = Double.MAX_VALUE
		for (start in listOf(Directed(an1, an2), Directed(an2, an1))) {
			// (Breadth first, by the edges between: few, a train moves a tick's way at most.)
			val queue = ArrayDeque(listOf(listOf(start) to 0.0))
			var expanded = 0
			while (queue.isNotEmpty() && expanded++ < 256) {
				val (path, between) = queue.removeFirst()
				val last = path.last()
				if (path.size > 1 && same(last)) {
					val spans = path.mapIndexed { i, d ->
						val edge = graph.getConnection(Couple.create(d.from, d.to)) ?: return null
						Span(edge, if (i == 0) paramFrom(d, an1, at) else 0.0, if (i == path.size - 1) paramFrom(d, bn1, bt) else 1.0)
					}
					val length = spans.sumOf { kotlin.math.abs(it.t1 - it.t0) * it.edge.length }
					if (length < bestLength) {
						bestLength = length
						best = spans
					}
					continue
				}
				val length = if (path.size > 1) between + (graph.getConnection(Couple.create(last.from, last.to))?.length ?: 0.0) else 0.0
				if (length > reach) continue
				for (next in onward(graph, last)) queue.addLast(path + next to length)
			}
		}
		return best
	}

	/**
	 * [tube]'s trail on to where [train]'s tail is now: the track it covered since last tick, all of it behind the last
	 * signal it passed dropped; begun, when it sets off, by its carriages' overhang behind its tail.
	 */
	private fun extendTrail(train: Train, tube: Tube) {
		val graph = train.graph ?: return
		// (Standing, which end is its tail isn't known yet: begun once it moves; begun again if it turns back.)
		if (train.speed == 0.0) return
		if (tube.tail != null && forward(train) != tube.forward) {
			tube.tail = null
			tube.trail.clear()
		}
		tube.forward = forward(train)
		val point = tail(train) ?: return
		val n1 = point.node1 ?: return
		val n2 = point.node2 ?: return
		val t = point.position / (point.edge ?: return).length.coerceAtLeast(1e-9)
		val last = tube.tail
		tube.tail = Triple(n1, n2, t)
		if (last == null) {
			val overhang = bodyRuns(train, OVERHANG).runs.firstOrNull()?.firstOrNull()
			if (overhang != null && point.edge?.trackMaterial === Maglev.KRASNIKOV) tube.trail += overhang to true
			return
		}
		val spans = stretch(graph, last.first, last.second, last.third, n1, n2, t, 2.0 * kotlin.math.abs(train.speed) + 16.0)
		if (spans == null) {
			// (Lost its way back: begun again from here.)
			tube.trail.clear()
			return
		}
		for (span in spans) {
			// A signal passed on it: everything behind it dropped.
			val passed = span.edge.edgeData.points.filterIsInstance<com.simibubi.create.content.trains.signal.SignalBoundary>()
				.map { it.getLocationOn(span.edge) / span.edge.length.coerceAtLeast(1e-9) }
				.filter { s -> if (span.t1 >= span.t0) s > span.t0 && s <= span.t1 else s < span.t0 && s >= span.t1 }
				.maxByOrNull { s -> kotlin.math.abs(s - span.t0) }
			if (passed != null) tube.trail.clear()
			val from = passed ?: span.t0
			if (kotlin.math.abs(span.t1 - from) * span.edge.length > 1e-3) {
				tube.trail += curve(span.edge).part(from, span.t1) to (span.edge.trackMaterial === Maglev.KRASNIKOV)
			}
		}
	}

	/** The point of [train] leading the way it's going (as [route] takes it). */
	fun head(train: Train): TravellingPoint? =
		if (forward(train)) train.carriages.firstOrNull()?.leadingPoint else train.carriages.lastOrNull()?.trailingPoint

	/** A train's stretch of track as runs of curve on Krasnikov track (tail to head), and whether the last ends at its head. */
	private class Body(val runs: List<List<Cubic>>, val headOnKrasnikov: Boolean)

	/**
	 * [train]'s own stretch of track, its bogeys' points from tail to head joined along the track between them (the
	 * edge they share, or across the node two edges meet at), as runs on Krasnikov track; the first reaching [tail]
	 * blocks back past the tail.
	 */
	private fun bodyRuns(train: Train, tail: Double): Body {
		val points = ArrayList<TravellingPoint>()
		// (Carriages run first to last from the train's front; each's leading point is toward it.)
		for (carriage in train.carriages) {
			points += carriage.leadingPoint
			points += carriage.trailingPoint
		}
		if (forward(train)) points.reverse()
		val runs = ArrayList<List<Cubic>>()
		var run = ArrayList<Cubic>()
		fun add(piece: Cubic, krasnikov: Boolean) {
			if (!krasnikov) {
				if (run.isNotEmpty()) runs += run
				run = ArrayList()
				return
			}
			if (piece.a.distance(piece.d) > 1e-4 || piece.flatness() > 1e-4) run += piece
		}
		for ((a, b) in points.zipWithNext()) {
			val ea = a.edge ?: continue
			val eb = b.edge ?: continue
			val krasnikov = ea.trackMaterial === Maglev.KRASNIKOV && eb.trackMaterial === Maglev.KRASNIKOV
			val sameEdge = (ea.node1 === eb.node1 && ea.node2 === eb.node2) || (ea.node1 === eb.node2 && ea.node2 === eb.node1)
			val shared = listOf(ea.node1, ea.node2).firstOrNull { it === eb.node1 || it === eb.node2 }
			when {
				sameEdge -> add(curve(ea).part(paramOn(ea, a), paramOn(ea, b)), krasnikov)
				shared != null -> {
					add(curve(ea).part(paramOn(ea, a), if (ea.node1 === shared) 0.0 else 1.0), ea.trackMaterial === Maglev.KRASNIKOV)
					add(curve(eb).part(if (eb.node1 === shared) 0.0 else 1.0, paramOn(eb, b)), eb.trackMaterial === Maglev.KRASNIKOV)
				}
				else -> {
					// (Edges apart: along the track between them, its curves; straight across if it can't be found.)
					val spans = train.graph?.let { stretch(it, a, b, 64.0) }
					if (spans != null) {
						for (span in spans) add(span.piece(), span.edge.trackMaterial === Maglev.KRASNIKOV)
					} else {
						fun at(p: TravellingPoint): Vector3dc = p.getPosition(train.graph).let { Vector3d(it.x, it.y + LIFT, it.z) }
						add(Cubic.line(at(a), at(b)), krasnikov)
					}
				}
			}
		}
		val headOnKrasnikov = run.isNotEmpty() && points.lastOrNull()?.edge?.trackMaterial === Maglev.KRASNIKOV
		if (run.isNotEmpty()) runs += run
		// (The tail's overhang, and more: straight on back past the tail, along the track's direction there.)
		val first = runs.firstOrNull()?.firstOrNull()
		if (first != null && tail > 0.0) {
			val back = first.derivative(0.0)
			if (back.length() > 1e-9) {
				back.normalize(-tail)
				runs[0] = listOf(Cubic.line(Vector3d(first.a).add(back), first.a)) + runs[0]
			}
		}
		return Body(runs, headOnKrasnikov)
	}

	/**
	 * The server's [tube] round train [id], round the client's copy of it instead: its stretch here (its tail reaching
	 * on by the train's travel in a tick, as it's drawn between ticks), then the tube's curve on from the point nearest
	 * its head.
	 */
	private fun aroundClientTrain(owner: Owner, tube: FieldSource.Krasnikov): FieldSource.Krasnikov {
		val train = com.simibubi.create.CreateClient.RAILWAYS.trains[owner.train] ?: return tube
		val body = bodyRuns(train, 0.0)
		val own = body.runs.lastOrNull()?.takeIf { body.headOnKrasnikov } ?: return tube
		val pieces = tube.curve
		if (owner.from >= owner.to || owner.to > pieces.size) return tube
		// Where the client's train's tail and head are on the server's tube: near the server's (a tick apart at most),
		// looked for only there (a route may cross itself).
		val margin = 2.0 * kotlin.math.abs(train.speed) + 10.0
		var lo = owner.from
		var length = 0.0
		while (lo > 0 && length < margin) length += pieces[--lo].length()
		var hi = owner.to - 1
		length = 0.0
		while (hi < pieces.size - 1 && length < margin) length += pieces[++hi].length()
		fun nearest(p: Vector3dc): Pair<Int, Double>? {
			var best: Pair<Int, Double>? = null
			var bestD = margin
			for (i in lo..hi) for (step in 0..20) {
				val t = step / 20.0
				val d = pieces[i].at(t).distance(p)
				if (d < bestD) { bestD = d; best = i to t }
			}
			return best
		}
		val (tp, tt) = nearest(own.first().a) ?: return tube
		val (hp, ht) = nearest(own.last().d) ?: return tube
		if (tp + tt >= hp + ht) return tube
		// The server's tube up to the client's tail, the client's train, then the server's tube on from its head (round the
		// train at the tilt the server's has where its head is).
		val curve = ArrayList<Cubic>()
		val tilts = ArrayList<Double>()
		fun put(piece: Cubic, startTilt: Double, endTilt: Double) {
			if (tilts.isEmpty()) tilts += startTilt
			curve += piece
			tilts += endTilt
		}
		for (i in 0 until tp) put(pieces[i], tube.tilts[i], tube.tilts[i + 1])
		if (tt > 1e-6) put(pieces[tp].part(0.0, tt), tube.tilts[tp], along(tube.tilts, tp + tt))
		val joined = along(tube.tilts, hp + ht)
		for (piece in own) put(piece, along(tube.tilts, tp + tt), joined)
		if (ht < 1.0 - 1e-6) put(pieces[hp].part(ht, 1.0), joined, tube.tilts[hp + 1])
		for (i in hp + 1 until pieces.size) put(pieces[i], tube.tilts[i], tube.tilts[i + 1])
		return FieldSource.Krasnikov(curve, tube.k, tube.radius, tilts)
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
