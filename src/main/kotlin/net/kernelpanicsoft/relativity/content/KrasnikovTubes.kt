package net.kernelpanicsoft.relativity.content

import com.simibubi.create.Create
import com.simibubi.create.content.trains.entity.Train
import com.simibubi.create.content.trains.graph.TrackEdge
import com.simibubi.create.content.trains.graph.TrackNode
import net.createmod.catnip.data.Couple
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.mixin.create.NavigationAccessor
import com.simibubi.create.content.trains.entity.TravellingPoint
import net.kernelpanicsoft.relativity.spacetime.Cubic
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
	const val RADIUS = 3.0

	/** How far above the track the spine runs (blocks): the carriages' middles. */
	const val LIFT = 1.0

	/** A train in a tube runs at up to this share of its light speed along it (just under: its clocks stay defined). */
	private const val SHARE = 0.9

	/** How long a train takes to reach the tube's speed from rest (ticks), at the least. */
	private const val SPOOL_TICKS = 200.0

	/** How long a tube's tilt takes to ease in, or out (ticks). */
	private const val RAMP_TICKS = 20.0

	/** A train's tube: when it began forming, and (once its route ended) its last spines and when it began to fade. */
	private class Tube(val start: Long) {
		var strength = 0.0
		var spines: List<List<Cubic>> = emptyList()
		var fadeFrom = -1L
		var fadeStrength = 0.0
	}

	/** Each level's trains' tubes (by level: in singleplayer, a train's client and server copies share an id). */
	private val formingByLevel = WeakHashMap<Level, HashMap<java.util.UUID, Tube>>()

	/** An edge travelled from [from] to [to]. */
	data class Directed(val from: TrackNode, val to: TrackNode)

	/**
	 * Each train under way in its tube (its tube's k there), the tubes, and each tube's train where it runs round it
	 * (null for the rest of its route, past a gap, and for fading tubes).
	 */
	private class Cached(val tick: Long, val inTube: Map<java.util.UUID, Double>, val tubes: List<FieldSource.Krasnikov>, val owners: List<java.util.UUID?>)

	private val cache = WeakHashMap<Level, Cached>()

	fun init() {
		SpacetimeField.addProvider { level -> cached(level).tubes }
	}

	/** The tubes in [level]. */
	fun tubes(level: Level): List<FieldSource.Krasnikov> = cached(level).tubes

	/** The tubes in [level], each with its train where it runs round it (KrasnikovSync sends both). */
	fun owned(level: Level): List<Pair<java.util.UUID?, FieldSource.Krasnikov>> = cached(level).let { it.owners.zip(it.tubes) }

	/** The tube's k: light along it runs at 1 / k (the config's speed, times c). */
	fun k(): Double = 1.0 / RelativityConfig.Server.Trains.krasnikovSpeed.coerceAtLeast(1.0)

	@Synchronized
	private fun cached(level: Level): Cached {
		val tick = level.gameTime
		cache[level]?.takeIf { it.tick == tick }?.let { return it }
		// (A client's copies of trains don't know their routes: its tubes are the server's, sent (KrasnikovSync), each
		// round its own train where it is here: the server's is a tick or more ahead, and drawn between ticks.)
		if (level.isClientSide) {
			val received = KrasnikovSync.received(level)
			val made = Cached(tick, emptyMap(), received.map { (owner, tube) -> owner?.let { aroundClientTrain(level, it, tube) } ?: tube }, received.map { it.first })
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

	fun edge(train: Train, d: Directed): TrackEdge? = train.graph?.getConnection(Couple.create(d.from, d.to))

	private fun compute(level: Level): Cached {
		val tick = level.gameTime
		val forming = formingByLevel.getOrPut(level) { HashMap() }
		val c = RelativityConfig.speedOfLight(level.dimension())
		val inTube = HashMap<java.util.UUID, Double>()
		val tubes = ArrayList<FieldSource.Krasnikov>()
		val owners = ArrayList<java.util.UUID?>()
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
			val spines = ArrayList<List<Cubic>>()
			var owned = -1
			var run = ArrayList<Cubic>()
			fun close() {
				if (run.isNotEmpty()) spines += run
				run = ArrayList()
			}
			// Round the train itself first: along the track from past its tail (by its overhang) to its head (the way
			// it's going), where it's Krasnikov track.
			val body = bodyRuns(train, OVERHANG)
			for (part in body.runs) {
				close()
				run.addAll(part)
			}
			if (body.headOnKrasnikov) owned = spines.size else close()
			// Then on along its route, from its head, out to the tube's front.
			val head = head(train)
			var travelled = 0.0
			outer@ for ((i, d) in route.withIndex()) {
				val edge = edge(train, d) ?: break
				val krasnikov = edge.trackMaterial === Maglev.KRASNIKOV
				if (krasnikov && i == 0) inTube[train.id] = tilt(tube.strength)
				val forward = edge.node1 === d.from
				val start = if (i == 0 && head != null) paramOn(edge, head) else if (forward) 0.0 else 1.0
				var piece = curve(edge).part(start, if (forward) 1.0 else 0.0)
				val length = piece.length()
				if (travelled + length > front) piece = piece.part(0.0, ((front - travelled) / length).coerceIn(0.0, 1.0))
				travelled += length
				if (!krasnikov) {
					close()
				} else if (piece.length() > 1e-3) {
					run += piece
				}
				if (travelled > front) break@outer
			}
			close()
			tube.spines = spines
			for ((index, spine) in spines.withIndex()) {
				tubes += FieldSource.Krasnikov(spine, tilt(tube.strength), RADIUS)
				owners += if (index == owned) train.id else null
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
			for (spine in tube.spines) {
				tubes += FieldSource.Krasnikov(spine, tilt(strength), RADIUS)
				owners += null
			}
		}
		gone.forEach { forming.remove(it) }
		return Cached(tick, inTube, tubes, owners)
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

	/** The point of [train] leading the way it's going (as [route] takes it). */
	fun head(train: Train): TravellingPoint? =
		if (train.speed >= 0) train.carriages.firstOrNull()?.leadingPoint else train.carriages.lastOrNull()?.trailingPoint

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
		if (train.speed >= 0) points.reverse()
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
					val graph = train.graph
					fun at(p: TravellingPoint): Vector3dc = p.getPosition(graph).let { Vector3d(it.x, it.y + LIFT, it.z) }
					add(Cubic.line(at(a), at(b)), krasnikov)
				}
			}
		}
		val headOnKrasnikov = run.isNotEmpty() && points.lastOrNull()?.edge?.trackMaterial === Maglev.KRASNIKOV
		if (run.isNotEmpty()) runs += run
		// (The tail's overhang, and more: straight on back past the tail, along the track's direction there.)
		val first = runs.firstOrNull()?.firstOrNull()
		if (first != null) {
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
	private fun aroundClientTrain(level: Level, id: java.util.UUID, tube: FieldSource.Krasnikov): FieldSource.Krasnikov {
		val train = com.simibubi.create.CreateClient.RAILWAYS.trains[id] ?: return tube
		val body = bodyRuns(train, OVERHANG + kotlin.math.abs(train.speed))
		val own = body.runs.lastOrNull()?.takeIf { body.headOnKrasnikov } ?: return tube
		val head = own.last().d
		var bestPiece = -1
		var bestT = 0.0
		var best = Double.MAX_VALUE
		for ((i, piece) in tube.curve.withIndex()) for (step in 0..20) {
			val t = step / 20.0
			val d = piece.at(t).distance(head)
			if (d < best) { best = d; bestPiece = i; bestT = t }
		}
		if (bestPiece < 0) return tube
		val curve = ArrayList<Cubic>(own)
		if (bestT < 1.0) curve += tube.curve[bestPiece].part(bestT, 1.0)
		curve.addAll(tube.curve.drop(bestPiece + 1))
		return FieldSource.Krasnikov(curve, tube.k, tube.radius)
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
