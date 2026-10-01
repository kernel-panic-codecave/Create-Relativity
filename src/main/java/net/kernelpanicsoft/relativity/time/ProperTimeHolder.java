package net.kernelpanicsoft.relativity.time;

import org.jetbrains.annotations.Nullable;

/** Mixed into BlockEntity: its local proper-time clock, kept on the block entity so render threads can read it lock-free. */
public interface ProperTimeHolder {
	@Nullable LocalTime create_relativity$getLocalTime();

	void create_relativity$setLocalTime(@Nullable LocalTime time);

	/** Game time the client last checked whether this block entity needs a clock (render workers can't check). */
	long create_relativity$getCheckedAt();

	void create_relativity$setCheckedAt(long time);
}
