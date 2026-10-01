package net.kernelpanicsoft.relativity.time

import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.api.visual.TickableVisual
import dev.engine_room.flywheel.lib.task.functional.ConsumerWithContext
import net.kernelpanicsoft.relativity.mixin.client.flywheel.AbstractBlockEntityVisualAccessor

/** Runs Flywheel block-entity visuals in their block's local time. Called on Flywheel's worker threads. */
object FlywheelTime {
	@JvmStatic
	fun frame(visual: Any, context: DynamicVisual.Context, action: ConsumerWithContext<Any, DynamicVisual.Context>) {
		val blockEntity = (visual as? AbstractBlockEntityVisualAccessor)?.`create_relativity$getBlockEntity`()
		val frame = blockEntity?.let { RenderTime.frameFor(it, context.partialTick()) }
		if (frame == null) {
			action.accept(visual, context)
			return
		}
		RenderTime.within(frame) { action.accept(visual, LocalContext(context, frame.partialTick)) }
	}

	@JvmStatic
	fun tick(visual: Any, context: TickableVisual.Context, action: ConsumerWithContext<Any, TickableVisual.Context>) {
		val blockEntity = (visual as? AbstractBlockEntityVisualAccessor)?.`create_relativity$getBlockEntity`()
		val level = blockEntity?.level
		val time = level?.let { LocalClocks.localTimeOf(blockEntity, it) }
		if (time == null) {
			action.accept(visual, context)
			return
		}
		if (!time.visualTickDue(level.gameTime)) return
		val frame = RenderTime.frameFor(blockEntity, 0f)
		if (frame == null) action.accept(visual, context) else RenderTime.within(frame) { action.accept(visual, context) }
	}

	private class LocalContext(private val context: DynamicVisual.Context, private val partialTick: Float) : DynamicVisual.Context {
		override fun camera() = context.camera()
		override fun frustum() = context.frustum()
		override fun partialTick() = partialTick
		override fun limiter() = context.limiter()
	}
}
