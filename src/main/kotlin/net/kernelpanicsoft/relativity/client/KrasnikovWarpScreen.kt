package net.kernelpanicsoft.relativity.client

import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsScreen
import net.kernelpanicsoft.relativity.content.KrasnikovNetworks
import net.kernelpanicsoft.relativity.content.KrasnikovWarpControl
import net.kernelpanicsoft.relativity.content.WarpSetting
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Create's value board for a Krasnikov network's warp factor (KrasnikovWarpControl): no block entity behind it (straight
 * track has none), so let go it sends the warp chosen to the server itself.
 */
class KrasnikovWarpScreen(private val track: BlockPos, tenths: Int) : ValueSettingsScreen(
	track,
	WarpSetting.board(Component.translatable("create_relativity.krasnikov_track.warp"), KrasnikovNetworks.MAX_TENTHS),
	ValueSettingsBehaviour.ValueSettings(0, tenths),
	{},
	0,
) {
	override fun saveAndClose(pMouseX: Double, pMouseY: Double) {
		val chosen = getClosestCoordinate(pMouseX.toInt(), pMouseY.toInt())
		PacketDistributor.sendToServer(KrasnikovWarpControl.Set(track, chosen.value()))
		onClose()
	}

	companion object {
		/** Opens the board for the network of the track at [pos], now at [tenths] of warp. */
		fun open(pos: BlockPos, tenths: Int) {
			Minecraft.getInstance().setScreen(KrasnikovWarpScreen(pos, tenths))
		}
	}
}
