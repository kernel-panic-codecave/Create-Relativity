package net.kernelpanicsoft.relativity.content

import com.simibubi.create.Create
import com.simibubi.create.content.trains.entity.Train
import com.simibubi.create.content.trains.signal.SignalBoundary
import com.simibubi.create.content.trains.signal.SignalEdgeGroup
import java.util.UUID

/**
 * Warp trains' routes, reserved whole: a maglev train sent over Krasnikov track (KrasnikovTubes.route) sets off only
 * once every signal block from its head to its destination is free (other trains', and other warp trains' routes:
 * through chain signals and plain ones alike), and then holds them all, so its tube forms the whole way and it never
 * stops at a signal; till then it waits at its station. Its own blocks don't stop it (SignalEdgeGroupMixin); others'
 * signals read them occupied, so no train crosses its path. The blocks behind it fall free as it passes (only those
 * ahead are held), and all of them once it arrives or its route ends.
 *
 * Create clears every block's reservation at the start of each tick and trains reserve again as they run; so held
 * blocks are put back before the trains run ([apply], GlobalRailwayManagerMixin), and who holds what is settled
 * after they have, on that tick's occupancy ([update]).
 */
object WarpReservations {
	/** Each held block (group id): its train, and the signal it's entered by on that train's route (null: the train's in it). */
	private val held = HashMap<UUID, Pair<UUID, SignalBoundary?>>()

	/** Trains whose whole route is held. */
	private val cleared = HashSet<UUID>()

	/** Debug (GameTests): blocks taken as occupied, as by a train that isn't there. */
	@JvmField
	val blockedForTest = HashSet<UUID>()

	/** Whether [train]'s whole route is held. */
	@JvmStatic
	fun isCleared(train: Train): Boolean = train.id in cleared

	/** [train]'s route's blocks (debug: GameTests). */
	fun routeBlocks(train: Train): List<UUID> = blocks(train)?.map { it.first } ?: emptyList()

	/** Whether [train] runs under a route reservation: a maglev train with a destination, over Krasnikov track. */
	@JvmStatic
	fun isWarp(train: Train): Boolean {
		if (!MaglevBogey.isMaglev(train) || train.navigation?.destination == null) return false
		return KrasnikovTubes.route(train).any { KrasnikovTubes.edge(train, it)?.trackMaterial === Maglev.KRASNIKOV }
	}

	/** Whether [train] must wait where it is: a warp train still at its station, its route not held yet. */
	@JvmStatic
	fun mustWait(train: Train): Boolean = train.id !in cleared && train.getCurrentStation() != null && isWarp(train)

	/** Whether block [group] is held for [train]. */
	@JvmStatic
	fun holds(group: SignalEdgeGroup, train: Train): Boolean = held[group.id]?.first == train.id

	/** Start of each tick (after Create clears reservations, before trains run): the held blocks reserved again. */
	@JvmStatic
	fun apply() {
		val groups = Create.RAILWAYS.signalEdgeGroups
		for ((id, owner) in held) {
			val group = groups[id] ?: continue
			// (A block the train is in needs no signal: it's occupied by it; any boundary marks the others held.)
			group.reserved = owner.second ?: group.reserved
		}
	}

	/** End of each tick: each warp train's route held (if all of it is free) or kept, the rest let go. */
	@JvmStatic
	fun update() {
		val trains = Create.RAILWAYS.trains
		val groups = Create.RAILWAYS.signalEdgeGroups
		// Routes no longer under way let go.
		cleared.retainAll { id -> trains[id]?.let { isWarp(it) } == true }
		held.values.removeIf { it.first !in cleared }
		for (train in trains.values) {
			if (!isWarp(train)) continue
			val blocks = blocks(train) ?: continue
			if (train.id !in cleared) {
				// All of it free: nobody else in it, nor holding it.
				val free = blocks.all { (id, _) ->
					val group = groups[id] ?: return@all false
					val other = held[id]?.first
					id !in blockedForTest && (other == null || other == train.id) && (group.trains.isEmpty() || group.trains.singleOrNull() === train)
				}
				if (!free) continue
				cleared += train.id
			}
			// Held: the blocks still ahead (those passed fall free).
			held.values.removeIf { it.first == train.id }
			for ((id, signal) in blocks) held[id] = train.id to signal
		}
	}

	/**
	 * The signal blocks on [train]'s route from its head to its destination, each with the signal it's entered by (the
	 * first, the one it's in: none); null if the route can't be read.
	 */
	private fun blocks(train: Train): List<Pair<UUID, SignalBoundary?>>? {
		val graph = train.graph ?: return null
		val head = KrasnikovTubes.head(train) ?: return null
		val out = ArrayList<Pair<UUID, SignalBoundary?>>()
		fun add(id: UUID?, signal: SignalBoundary?) {
			if (id == null || out.any { it.first == id }) return
			out += id to signal
		}
		for ((i, d) in KrasnikovTubes.route(train).withIndex()) {
			val edge = KrasnikovTubes.edge(train, d) ?: return null
			val data = edge.edgeData
			// (Positions along the edge from its first node, as its points' are.)
			val start = if (i == 0) KrasnikovTubes.paramOn(edge, head) * edge.length else 0.0
			add(data.getGroupAtPosition(graph, start), null)
			for (point in data.points) {
				if (point !is SignalBoundary || point.getLocationOn(edge) < start) continue
				add(point.getGroup(d.to), point)
			}
		}
		return out
	}
}
