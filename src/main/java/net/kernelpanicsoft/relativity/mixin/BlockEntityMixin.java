package net.kernelpanicsoft.relativity.mixin;

import net.kernelpanicsoft.relativity.time.LocalTime;
import net.kernelpanicsoft.relativity.time.ProperTimeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin implements ProperTimeHolder {
	@Unique private volatile @Nullable LocalTime create_relativity$localTime;
	@Unique private volatile long create_relativity$checkedAt = Long.MIN_VALUE;

	@Override
	public @Nullable LocalTime create_relativity$getLocalTime() {
		return create_relativity$localTime;
	}

	@Override
	public void create_relativity$setLocalTime(@Nullable LocalTime time) {
		create_relativity$localTime = time;
	}

	@Override
	public long create_relativity$getCheckedAt() {
		return create_relativity$checkedAt;
	}

	@Override
	public void create_relativity$setCheckedAt(long time) {
		create_relativity$checkedAt = time;
	}
}
