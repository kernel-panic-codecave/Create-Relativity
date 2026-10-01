package net.kernelpanicsoft.relativity.command

import com.mojang.brigadier.arguments.DoubleArgumentType
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.physics.Schwarzschild
import net.kernelpanicsoft.relativity.sable.SpikeAttractor
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.event.RegisterCommandsEvent

object RelativityCommands {
	fun register(event: RegisterCommandsEvent) {
		event.dispatcher.register(
			Commands.literal("relativity").requires { it.hasPermission(2) }
				.then(Commands.literal("spike")
					.then(Commands.literal("attractor")
						.then(Commands.argument("pos", Vec3Argument.vec3())
							.then(Commands.argument("rs", DoubleArgumentType.doubleArg(0.01, 1000.0))
								.executes { ctx ->
									val pos = Vec3Argument.getVec3(ctx, "pos")
									val rs = DoubleArgumentType.getDouble(ctx, "rs")
									val units = RelativityConfig.units(ctx.source.level.dimension())
									val hole = Schwarzschild(rs, units)
									SpikeAttractor.active = SpikeAttractor.Attractor(ctx.source.level.dimension(), pos, hole)
									ctx.source.sendSuccess({
										Component.literal("Spike attractor at %.1f %.1f %.1f, r_s=%.2f, M=%.0f".format(
											pos.x, pos.y, pos.z, rs, units.massForRadius(rs)))
									}, true)
									1
								})))
					.then(Commands.literal("clear").executes { ctx ->
						SpikeAttractor.active = null
						ctx.source.sendSuccess({ Component.literal("Spike attractor cleared") }, true)
						1
					}))
		)
	}
}
