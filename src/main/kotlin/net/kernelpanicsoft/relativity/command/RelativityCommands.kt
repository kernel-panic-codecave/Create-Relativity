package net.kernelpanicsoft.relativity.command

import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.kernelpanicsoft.relativity.config.RelativityConfig
import net.kernelpanicsoft.relativity.spacetime.BlackHole
import net.kernelpanicsoft.relativity.spacetime.SpacetimeField
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.event.RegisterCommandsEvent

object RelativityCommands {
	fun register(event: RegisterCommandsEvent) {
		event.dispatcher.register(
			Commands.literal("relativity").requires { it.hasPermission(2) }
				.then(blackHole())
				.then(Commands.literal("clock")
					.then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
						.executes(::clock)))
		)
	}

	/** Debug: the local clock rate at a block, the nearest hole's distance, and how many ticks have been skipped. */
	private fun clock(ctx: CommandContext<CommandSourceStack>): Int {
		val level = ctx.source.level
		val pos = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(ctx, "pos")
		val rate = net.kernelpanicsoft.relativity.time.LocalClocks.rateAt(level, pos)
		val hole = SpacetimeField.blackHoles(level).minByOrNull { pos.center.distanceToSqr(it.x, it.y, it.z) }
		val where = hole?.let {
			val r = pos.center.distanceTo(net.minecraft.world.phys.Vec3(it.x, it.y, it.z))
			"%.1f blocks from a hole of r_s %.2f (%.2f r_s)".format(r, it.schwarzschildRadius, r / it.schwarzschildRadius)
		} ?: "no holes"
		val enabled = net.kernelpanicsoft.relativity.time.LocalClocks.enabled
		val skipped = net.kernelpanicsoft.relativity.time.LocalClocks.skippedBlockEntityTicks
		ctx.source.sendSuccess({
			Component.literal("dτ/dt = %.4f at %s; %s. Dilation %s, block-entity ticks skipped so far: %d"
				.format(rate, pos.toShortString(), where, if (enabled) "on" else "off", skipped))
		}, false)
		return 1
	}

	private fun blackHole(): LiteralArgumentBuilder<CommandSourceStack> =
		Commands.literal("bh")
			.then(Commands.literal("create")
				.then(Commands.argument("pos", Vec3Argument.vec3())
					.then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.05, 256.0))
						.executes { create(it, 0.0) }
						.then(Commands.argument("spin", DoubleArgumentType.doubleArg(-BlackHole.MAX_SPIN, BlackHole.MAX_SPIN))
							.executes { create(it, DoubleArgumentType.getDouble(it, "spin")) }))))
			.then(Commands.literal("list").executes(::list))
			.then(Commands.literal("feed")
				.then(Commands.argument("rate", DoubleArgumentType.doubleArg(0.0, 1e6))
					.executes(::feed)))
			.then(Commands.literal("remove").executes(::removeNearest))
			.then(Commands.literal("clear").executes { ctx ->
				val removed = SpacetimeField.removeBlackHoles(ctx.source.level) { true }
				ctx.source.sendSuccess({ Component.translatable("commands.create_relativity.bh.cleared", removed) }, true)
				removed
			})

	private fun create(ctx: CommandContext<CommandSourceStack>, spin: Double): Int {
		val pos = Vec3Argument.getVec3(ctx, "pos")
		val radius = DoubleArgumentType.getDouble(ctx, "radius")
		val level = ctx.source.level
		SpacetimeField.addBlackHole(level, BlackHole(x = pos.x, y = pos.y, z = pos.z, schwarzschildRadius = radius, spin = spin))
		val mass = RelativityConfig.units(level.dimension()).massForRadius(radius)
		ctx.source.sendSuccess({
			Component.translatable("commands.create_relativity.bh.created", fmt(pos.x), fmt(pos.y), fmt(pos.z), fmt(radius), fmt(spin), "%.3g".format(mass))
		}, true)
		return 1
	}

	private fun list(ctx: CommandContext<CommandSourceStack>): Int {
		val holes = SpacetimeField.blackHoles(ctx.source.level)
		ctx.source.sendSuccess({ Component.translatable("commands.create_relativity.bh.list", holes.size) }, false)
		for (hole in holes) {
			ctx.source.sendSuccess({
				Component.literal("  %s  r_s=%s  χ=%s  horizon=%s  ISCO=%s  Ṁ=%s/s (λ=%s)  feed=%s/s".format(
					"${fmt(hole.x)} ${fmt(hole.y)} ${fmt(hole.z)}", fmt(hole.schwarzschildRadius), fmt(hole.spin),
					fmt(hole.horizonRadius), fmt(hole.isco(prograde = true)), fmt(hole.accretionRate),
					fmt(hole.accretionRate / RelativityConfig.eddingtonRate(hole.schwarzschildRadius)), fmt(hole.feedRate)))
			}, false)
		}
		return holes.size
	}

	/** Steady feed of the nearest hole through its disk, for watching it grow and glow; capped at Eddington. */
	private fun feed(ctx: CommandContext<CommandSourceStack>): Int {
		val rate = DoubleArgumentType.getDouble(ctx, "rate")
		val from = ctx.source.position
		val nearest = SpacetimeField.blackHoles(ctx.source.level).minByOrNull { it.center.distanceToSqr(from) }
		if (nearest == null) {
			ctx.source.sendFailure(Component.translatable("commands.create_relativity.bh.none"))
			return 0
		}
		SpacetimeField.updateBlackHole(ctx.source.level, nearest.id) { it.copy(feedRate = rate) }
		val eddington = RelativityConfig.eddingtonRate(nearest.schwarzschildRadius)
		ctx.source.sendSuccess({ Component.translatable("commands.create_relativity.bh.feed", fmt(rate), fmt(eddington)) }, true)
		return 1
	}

	private fun removeNearest(ctx: CommandContext<CommandSourceStack>): Int {
		val from = ctx.source.position
		val nearest = SpacetimeField.blackHoles(ctx.source.level).minByOrNull { it.center.distanceToSqr(from) }
		if (nearest == null) {
			ctx.source.sendFailure(Component.translatable("commands.create_relativity.bh.none"))
			return 0
		}
		SpacetimeField.removeBlackHoles(ctx.source.level) { it.id == nearest.id }
		ctx.source.sendSuccess({ Component.translatable("commands.create_relativity.bh.removed", fmt(nearest.x), fmt(nearest.y), fmt(nearest.z)) }, true)
		return 1
	}

	private fun fmt(value: Double) = "%.2f".format(value)
}
