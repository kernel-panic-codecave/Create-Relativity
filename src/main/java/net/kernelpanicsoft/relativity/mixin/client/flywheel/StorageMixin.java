package net.kernelpanicsoft.relativity.mixin.client.flywheel;

import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.TickableVisual;
import dev.engine_room.flywheel.lib.task.functional.ConsumerWithContext;
import net.kernelpanicsoft.relativity.time.FlywheelTime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(targets = "dev.engine_room.flywheel.impl.visualization.storage.Storage", remap = false)
public abstract class StorageMixin {
	/** Each block-entity visual's beginFrame runs in its block's local time (Flywheel runs these on worker threads). */
	@ModifyArg(
		method = "framePlan",
		at = @At(value = "INVOKE", target = "Ldev/engine_room/flywheel/lib/task/ForEachPlan;of(Ldev/engine_room/flywheel/lib/task/functional/SupplierWithContext$Ignored;Ldev/engine_room/flywheel/lib/task/functional/ConsumerWithContext;)Ldev/engine_room/flywheel/lib/task/ForEachPlan;"),
		index = 1
	)
	private ConsumerWithContext<Object, DynamicVisual.Context> create_relativity$localFrame(ConsumerWithContext<Object, DynamicVisual.Context> action) {
		return (visual, context) -> FlywheelTime.frame(visual, context, action);
	}

	/** Each block-entity visual's tick runs on its block's local clock. */
	@ModifyArg(
		method = "tickPlan",
		at = @At(value = "INVOKE", target = "Ldev/engine_room/flywheel/lib/task/ForEachPlan;of(Ldev/engine_room/flywheel/lib/task/functional/SupplierWithContext$Ignored;Ldev/engine_room/flywheel/lib/task/functional/ConsumerWithContext;)Ldev/engine_room/flywheel/lib/task/ForEachPlan;"),
		index = 1
	)
	private ConsumerWithContext<Object, TickableVisual.Context> create_relativity$localTick(ConsumerWithContext<Object, TickableVisual.Context> action) {
		return (visual, context) -> FlywheelTime.tick(visual, context, action);
	}
}
