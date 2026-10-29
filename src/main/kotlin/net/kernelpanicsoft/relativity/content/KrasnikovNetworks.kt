package net.kernelpanicsoft.relativity.content

import com.simibubi.create.content.trains.graph.TrackGraph
import com.simibubi.create.content.trains.graph.TrackGraphHelper
import com.simibubi.create.content.trains.graph.TrackNode
import com.simibubi.create.content.trains.graph.TrackNodeLocation
import com.simibubi.create.content.trains.track.ITrackBlock
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.SavedData

/**
 * Krasnikov networks: Krasnikov track joined end to end (its nodes and the Krasnikov edges between them) is one network,
 * and shares one setting, its warp factor (set with a wrench on any of its track: KrasnikovWarpControl), so it's set in one place (as its
 * fuel will be shared, fed in at one place). Kept by the networks' nodes' places, which outlast track being laid and
 * broken (Create's graphs and their nodes' ids don't): setting it sets it at every node of the network; track laid on
 * takes its network's; networks joined take the setting most of their nodes have (the higher on a tie).
 */
object KrasnikovNetworks {
	/** Every Krasnikov node's network's warp factor (tenths), by the node's place. */
	class Data : SavedData() {
		val warp = HashMap<String, Int>()

		override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
			val list = CompoundTag()
			for ((key, tenths) in warp) list.putInt(key, tenths)
			tag.put("Warp", list)
			return tag
		}

		companion object {
			fun load(tag: CompoundTag, registries: HolderLookup.Provider): Data {
				val data = Data()
				val list = tag.getCompound("Warp")
				for (key in list.allKeys) data.warp[key] = list.getInt(key)
				return data
			}
		}
	}

	private val FACTORY = SavedData.Factory(::Data, Data::load, null)

	private fun data(server: MinecraftServer): Data = server.overworld().dataStorage.computeIfAbsent(FACTORY, "create_relativity_krasnikov_networks")

	private fun key(location: TrackNodeLocation): String =
		"${location.dimension.location()}|${location.x}|${location.y}|${location.z}|${location.yOffsetPixels}"

	/** The highest warp a network may be set to, in tenths (warp 10 is instant: KrasnikovTubes.MAX_WARP). */
	val MAX_TENTHS: Int get() = (KrasnikovTubes.MAX_WARP * 10).toInt()

	/** A new network's warp factor, in tenths: the config's. */
	private fun defaultTenths(): Int = (RelativityConfig.Server.Trains.krasnikovWarp.coerceIn(1.0, KrasnikovTubes.MAX_WARP) * 10).toInt()

	/** [node]'s network: the nodes Krasnikov edges join it to (just it, if none do). */
	fun network(graph: TrackGraph, node: TrackNode): List<TrackNode> {
		val seen = LinkedHashSet<TrackNode>()
		val queue = ArrayDeque(listOf(node))
		seen += node
		while (queue.isNotEmpty()) {
			val here = queue.removeFirst()
			for ((other, edge) in graph.getConnectionsFrom(here) ?: continue) {
				if (edge.trackMaterial !== Maglev.KRASNIKOV || !seen.add(other)) continue
				queue.addLast(other)
			}
		}
		return seen.toList()
	}

	/**
	 * [node]'s network's warp factor (tenths), settled on: every node of it given the one most of them have (the higher
	 * on a tie; a new network the config's).
	 */
	fun tenths(server: MinecraftServer, graph: TrackGraph, node: TrackNode): Int {
		val data = data(server)
		val nodes = network(graph, node)
		val keys = nodes.map { key(it.location) }
		val counts = keys.mapNotNull { data.warp[it] }.groupingBy { it }.eachCount()
		val tenths = counts.entries.maxWithOrNull(compareBy({ it.value }, { it.key }))?.key ?: defaultTenths()
		var changed = false
		for (k in keys) if (data.warp.put(k, tenths) != tenths) changed = true
		if (changed) data.setDirty()
		return tenths
	}

	/** Sets [node]'s network's warp factor to [tenths]. */
	fun set(server: MinecraftServer, graph: TrackGraph, node: TrackNode, tenths: Int) {
		val data = data(server)
		for (n in network(graph, node)) data.warp[key(n.location)] = tenths
		data.setDirty()
	}

	/** The graph and a node of the network the Krasnikov track at [pos] belongs to, or null. */
	fun nodeAt(level: Level, pos: BlockPos): Pair<TrackGraph, TrackNode>? {
		val state = level.getBlockState(pos)
		val track = state.block as? ITrackBlock ?: return null
		for (axis in track.getTrackAxes(level, pos, state)) {
			for (direction in Direction.AxisDirection.entries) {
				val at = TrackGraphHelper.getGraphLocationAt(level, pos, direction, axis) ?: continue
				val node = at.graph.locateNode(at.edge.first) ?: at.graph.locateNode(at.edge.second) ?: continue
				return at.graph to node
			}
		}
		return null
	}

	/** The warp factor (tenths) of the network of the Krasnikov track at [pos], or null if it's in none yet. */
	fun tenthsAt(level: Level, pos: BlockPos): Int? {
		val server = level.server ?: return null
		val (graph, node) = nodeAt(level, pos) ?: return null
		return tenths(server, graph, node)
	}

	/** Sets the warp factor of the network of the Krasnikov track at [pos] to [tenths]. */
	fun setAt(level: Level, pos: BlockPos, tenths: Int) {
		val server = level.server ?: return
		val (graph, node) = nodeAt(level, pos) ?: return
		set(server, graph, node, tenths)
	}

	/** The warp factor of the network an edge from [node] on runs in (its first node's), as a multiple of c: for the tubes. */
	fun speed(graph: TrackGraph, node: TrackNode): Double {
		val server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer()
		val tenths = if (server != null) tenths(server, graph, node) else defaultTenths()
		return net.kernelpanicsoft.relativity.physics.WarpScale.speed((tenths / 10.0).coerceIn(1.0, KrasnikovTubes.MAX_WARP))
	}
}
