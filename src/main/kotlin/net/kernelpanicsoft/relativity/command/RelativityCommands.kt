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
				.then(tube())
				.then(bubble())
			.then(testScene())
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

	/**
	 * Krasnikov tubes placed by hand ([TestSources]), for testing without trains: `add` a straight one between two points,
	 * `ahead` one from the eye along the way it looks (level), `extend` the last one to another point (a bend), `clear`.
	 * Light runs along each at [speed] times c (the config's tube speed unless given), from its first point toward its last.
	 */
	private fun tube(): LiteralArgumentBuilder<CommandSourceStack> =
		Commands.literal("tube")
			.then(Commands.literal("add")
				// (Exact: whole numbers aren't moved to the block's middle.)
				.then(Commands.argument("from", Vec3Argument.vec3(false))
					.then(Commands.argument("to", Vec3Argument.vec3(false))
						.executes { addTube(it, listOf(Vec3Argument.getVec3(it, "from"), Vec3Argument.getVec3(it, "to"))) }
						.then(Commands.argument("warp", DoubleArgumentType.doubleArg(1.0, net.kernelpanicsoft.relativity.content.KrasnikovTubes.MAX_WARP))
							.executes { addTube(it, listOf(Vec3Argument.getVec3(it, "from"), Vec3Argument.getVec3(it, "to"))) }
							.then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.5, 64.0))
								.executes { addTube(it, listOf(Vec3Argument.getVec3(it, "from"), Vec3Argument.getVec3(it, "to"))) })))))
			.then(Commands.literal("ahead")
				.then(Commands.argument("length", DoubleArgumentType.doubleArg(1.0, 4096.0))
					.executes { addTube(it, ahead(it)) }
					.then(Commands.argument("warp", DoubleArgumentType.doubleArg(1.0, net.kernelpanicsoft.relativity.content.KrasnikovTubes.MAX_WARP))
						.executes { addTube(it, ahead(it)) })))
			.then(Commands.literal("extend")
				.then(Commands.argument("to", Vec3Argument.vec3(false))
					.executes(::extendTube)))
			.then(Commands.literal("clear").executes { ctx ->
				val removed = TestSources.clear(ctx.source.level, tubes = true)
				ctx.source.sendSuccess({ Component.translatable("commands.create_relativity.tube.cleared", removed) }, true)
				removed
			})

	/**
	 * Warp bubbles placed by hand ([TestSources]): `add` one round the box between two corners (blocks, inclusive: as a
	 * ship's bubble round its bounds), at [warp] (the drive's scale), moving the way the one placing it looks; `clear`.
	 */
	private fun bubble(): LiteralArgumentBuilder<CommandSourceStack> =
		Commands.literal("bubble")
			.then(Commands.literal("add")
				.then(Commands.argument("from", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
					.then(Commands.argument("to", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
						.then(Commands.argument("warp", DoubleArgumentType.doubleArg(0.0, 9.99))
							.executes(::addBubble)))))
			.then(Commands.literal("clear").executes { ctx ->
				val removed = TestSources.clear(ctx.source.level, tubes = false)
				ctx.source.sendSuccess({ Component.translatable("commands.create_relativity.bubble.cleared", removed) }, true)
				removed
			})

	/** The `ahead` tube's ends: from the eye, [length] blocks along the way it looks, level. */
	private fun ahead(ctx: CommandContext<CommandSourceStack>): List<net.minecraft.world.phys.Vec3> {
		val eye = ctx.source.entity?.eyePosition ?: ctx.source.position
		val yaw = Math.toRadians(ctx.source.rotation.y.toDouble())
		val way = net.minecraft.world.phys.Vec3(-kotlin.math.sin(yaw), 0.0, kotlin.math.cos(yaw))
		return listOf(eye, eye.add(way.scale(DoubleArgumentType.getDouble(ctx, "length"))))
	}

	private fun optional(ctx: CommandContext<CommandSourceStack>, name: String, default: Double): Double =
		try { DoubleArgumentType.getDouble(ctx, name) } catch (e: IllegalArgumentException) { default }

	private fun addTube(ctx: CommandContext<CommandSourceStack>, ends: List<net.minecraft.world.phys.Vec3>): Int {
		val warp = optional(ctx, "warp", RelativityConfig.Server.Trains.krasnikovWarp)
		val speed = net.kernelpanicsoft.relativity.physics.WarpScale.speed(warp)
		val radius = optional(ctx, "radius", 3.0)
		val (from, to) = ends
		TestSources.add(ctx.source.level, net.kernelpanicsoft.relativity.spacetime.FieldSource.Krasnikov.through(
			ends.map { org.joml.Vector3d(it.x, it.y, it.z) }, 1.0 / speed, radius))
		ctx.source.sendSuccess({
			Component.translatable("commands.create_relativity.tube.added", fmt(from.x), fmt(from.y), fmt(from.z), fmt(to.x), fmt(to.y), fmt(to.z), fmt(warp), fmt(speed), fmt(radius))
		}, true)
		return 1
	}

	private fun extendTube(ctx: CommandContext<CommandSourceStack>): Int {
		val to = Vec3Argument.getVec3(ctx, "to")
		if (!TestSources.extend(ctx.source.level, org.joml.Vector3d(to.x, to.y, to.z))) {
			ctx.source.sendFailure(Component.translatable("commands.create_relativity.tube.none"))
			return 0
		}
		ctx.source.sendSuccess({ Component.translatable("commands.create_relativity.tube.extended", fmt(to.x), fmt(to.y), fmt(to.z)) }, true)
		return 1
	}

	private fun addBubble(ctx: CommandContext<CommandSourceStack>): Int {
		val a = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "from")
		val b = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "to")
		val warp = DoubleArgumentType.getDouble(ctx, "warp")
		val min = net.minecraft.core.BlockPos.min(a, b)
		val max = net.minecraft.core.BlockPos.max(a, b)
		val centre = org.joml.Vector3d((min.x + max.x + 1) / 2.0, (min.y + max.y + 1) / 2.0, (min.z + max.z + 1) / 2.0)
		val halves = listOf(
			org.joml.Vector3d((max.x + 1 - min.x) / 2.0, 0.0, 0.0),
			org.joml.Vector3d(0.0, (max.y + 1 - min.y) / 2.0, 0.0),
			org.joml.Vector3d(0.0, 0.0, (max.z + 1 - min.z) / 2.0),
		)
		val look = ctx.source.entity?.lookAngle ?: net.minecraft.world.phys.Vec3(0.0, 0.0, 1.0)
		val speed = net.kernelpanicsoft.relativity.physics.WarpScale.speed(warp)
		TestSources.add(ctx.source.level, net.kernelpanicsoft.relativity.sable.ShipWarp.around(centre, halves, org.joml.Vector3d(look.x, look.y, look.z), speed))
		ctx.source.sendSuccess({
			Component.translatable("commands.create_relativity.bubble.added", min.toShortString(), max.toShortString(), fmt(warp), fmt(speed),
				"%.2f %.2f %.2f".format(look.x, look.y, look.z))
		}, true)
		return 1
	}

	/**
	 * The lens tests' scenes built where they are in the tests, to look round by hand: `ground` is groundAroundHole's
	 * (a superflat world's ground at y -61: a white and blue checkerboard of 2-block squares 65 blocks across, red pillars
	 * every 8 blocks, a hole of r_s 2 and the given spin at (0.5, -54, 0.5)). The player is put at its side pose.
	 */
	private fun testScene(): LiteralArgumentBuilder<CommandSourceStack> =
		Commands.literal("testscene").requires { it.hasPermission(2) }
			.then(Commands.literal("ground")
				.executes { groundScene(it, 0.0) }
				.then(Commands.argument("spin", DoubleArgumentType.doubleArg(-BlackHole.MAX_SPIN, BlackHole.MAX_SPIN))
					.executes { groundScene(it, DoubleArgumentType.getDouble(it, "spin")) }))

	private fun groundScene(ctx: CommandContext<CommandSourceStack>, spin: Double): Int {
		val level = ctx.source.level
		val white = net.minecraft.world.level.block.Blocks.WHITE_CONCRETE.defaultBlockState()
		val blue = net.minecraft.world.level.block.Blocks.BLUE_CONCRETE.defaultBlockState()
		val red = net.minecraft.world.level.block.Blocks.RED_CONCRETE.defaultBlockState()
		for (x in -32..32) for (z in -32..32) {
			val block = if (Math.floorMod(Math.floorDiv(x, 2) + Math.floorDiv(z, 2), 2) == 0) white else blue
			level.setBlock(net.minecraft.core.BlockPos(x, -61, z), block, 2)
		}
		for (x in -24..24 step 8) for (z in -24..24 step 8) for (y in -60..-58) level.setBlock(net.minecraft.core.BlockPos(x, y, z), red, 2)
		SpacetimeField.removeBlackHoles(level) { true }
		SpacetimeField.addBlackHole(level, BlackHole(x = 0.5, y = -54.0, z = 0.5, schwarzschildRadius = 2.0, spin = spin))
		ctx.source.player?.teleportTo(level, -14.5, -54.0, 0.5, -90f, 10f)
		ctx.source.sendSuccess({ Component.literal("Ground test scene built (spin %s)".format(fmt(spin))) }, true)
		return 1
	}

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
