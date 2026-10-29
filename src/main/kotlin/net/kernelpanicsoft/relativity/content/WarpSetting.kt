package net.kernelpanicsoft.relativity.content

import com.google.common.collect.ImmutableList
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.BlockHitResult

/**
 * A warp factor in a value box, kept in tenths (9.5 is 95) and shown as a warp factor with its one decimal: in the box,
 * and on the board dragged across to set it (Create's own board shows the bare number), a mark at each whole warp.
 */
class WarpSetting(label: Component, be: SmartBlockEntity, slot: ValueBoxTransform) : ScrollValueBehaviour(label, be, slot) {
	init {
		withFormatter(::format)
	}

	override fun createBoard(player: Player, hitResult: BlockHitResult): ValueSettingsBoard = board(label, max)

	companion object {
		/** The board for a warp factor up to [max] tenths, titled [label]: one row, a mark at each whole warp. */
		fun board(label: Component, max: Int): ValueSettingsBoard =
			ValueSettingsBoard(label, max, 10, ImmutableList.of<Component>(Component.translatable("create_relativity.warp_factor")), ValueSettingsFormatter { Component.literal(format(it.value())) })

		/** [tenths] of a warp factor as one, to one decimal. */
		fun format(tenths: Int): String = "%.1f".format(tenths / 10.0)
	}
}
