package net.kernelpanicsoft.relativity.entity

import net.kernelpanicsoft.relativity.spacetime.BlackHole
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level
import kotlin.math.abs

/**
 * A black hole as an entity: position and motion come from the entity system (so holes can move, sink, get kicked
 * and orbit each other), tracking sends it to the players that need it, and it saves with its chunk.
 *
 * The server keeps full-precision values (growth and Hawking evaporation change them by tiny amounts each tick);
 * clients get floats, resent when they've drifted noticeably or every half second.
 */
class BlackHoleEntity(type: EntityType<out BlackHoleEntity>, level: Level) : Entity(type, level) {
	var schwarzschildRadius = 1.0
		set(value) { field = value; changed() }
	var spin = 0.0
		set(value) { field = value; changed() }
	var accretionRate = 0.0
		set(value) { field = value; changed() }
	var feedRate = 0.0
		set(value) { field = value; changed() }

	init {
		noPhysics = true
		isNoGravity = true
	}

	/** The hole's state as the rest of the mod sees it: full precision on the server, synced floats on clients. */
	fun snapshot(): BlackHole =
		if (level().isClientSide) {
			BlackHole(
				id = stringUUID, x = x, y = y, z = z,
				schwarzschildRadius = entityData.get(RADIUS).toDouble(),
				spin = entityData.get(SPIN).toDouble().coerceIn(-BlackHole.MAX_SPIN, BlackHole.MAX_SPIN),
				accretionRate = entityData.get(ACCRETION).toDouble(),
				feedRate = entityData.get(FEED).toDouble(),
			)
		} else {
			BlackHole(stringUUID, x, y, z, schwarzschildRadius, spin, accretionRate, feedRate)
		}

	fun apply(hole: BlackHole) {
		if (hole.x != x || hole.y != y || hole.z != z) setPos(hole.x, hole.y, hole.z)
		schwarzschildRadius = hole.schwarzschildRadius
		spin = hole.spin
		accretionRate = hole.accretionRate
		feedRate = hole.feedRate
	}

	private fun changed() {
		if (level().isClientSide) return
		syncIfDrifted(RADIUS, schwarzschildRadius)
		syncIfDrifted(SPIN, spin)
		syncIfDrifted(ACCRETION, accretionRate)
		syncIfDrifted(FEED, feedRate)
	}

	private fun syncIfDrifted(key: EntityDataAccessor<Float>, value: Double) {
		val synced = entityData.get(key)
		if (abs(value - synced) > 1e-4 * maxOf(1.0, abs(value)) || tickCount % 10 == 0) entityData.set(key, value.toFloat())
	}

	override fun tick() {
		super.tick()
		val motion = deltaMovement
		if (motion.lengthSqr() > 0.0) setPos(x + motion.x, y + motion.y, z + motion.z)
	}

	override fun defineSynchedData(builder: SynchedEntityData.Builder) {
		builder.define(RADIUS, 1f)
		builder.define(SPIN, 0f)
		builder.define(ACCRETION, 0f)
		builder.define(FEED, 0f)
	}

	override fun readAdditionalSaveData(tag: CompoundTag) {
		schwarzschildRadius = tag.getDouble("SchwarzschildRadius").takeIf { it > 0.0 } ?: 1.0
		spin = tag.getDouble("Spin")
		accretionRate = tag.getDouble("AccretionRate")
		feedRate = tag.getDouble("FeedRate")
	}

	override fun addAdditionalSaveData(tag: CompoundTag) {
		tag.putDouble("SchwarzschildRadius", schwarzschildRadius)
		tag.putDouble("Spin", spin)
		tag.putDouble("AccretionRate", accretionRate)
		tag.putDouble("FeedRate", feedRate)
	}

	// Nothing hits, pushes or damages a black hole; it's removed by being evaporated or /kill.
	override fun isPickable() = false
	override fun isPushable() = false
	override fun canBeCollidedWith() = false
	override fun hurt(source: DamageSource, amount: Float) = false
	override fun shouldRenderAtSqrDistance(distance: Double) = true

	companion object {
		private val RADIUS: EntityDataAccessor<Float> = SynchedEntityData.defineId(BlackHoleEntity::class.java, EntityDataSerializers.FLOAT)
		private val SPIN: EntityDataAccessor<Float> = SynchedEntityData.defineId(BlackHoleEntity::class.java, EntityDataSerializers.FLOAT)
		private val ACCRETION: EntityDataAccessor<Float> = SynchedEntityData.defineId(BlackHoleEntity::class.java, EntityDataSerializers.FLOAT)
		private val FEED: EntityDataAccessor<Float> = SynchedEntityData.defineId(BlackHoleEntity::class.java, EntityDataSerializers.FLOAT)
	}
}
