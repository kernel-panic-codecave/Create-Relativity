package net.kernelpanicsoft.relativity.client.lens

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexFormat
import net.kernelpanicsoft.relativity.CreateRelativity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation
import org.lwjgl.opengl.GL20
import java.util.IdentityHashMap

/**
 * The lens's own copies of the world's shaders. Every render type drawn as triangles (Veil's
 * RenderTypeShardRegistry: one shard on each, after its own shader state) draws, while the lens is on, with a twin of
 * its shader: a second ShaderInstance from the same files (the same vertex and fragment shaders, as Veil and other mods'
 * injections made them, shared), linked with the lens's stages added round them ([ImageStage]: the lens in a geometry
 * stage, and a tessellation stage ([TessellationStage]) where the type draws plain triangles). The originals are never
 * touched: the GUI, debug lines and anything outside a render type draw with them as ever.
 */
object LensTwins {
	/** A twin being built: whether it's tessellated, and its vertex format (ImageStage reads it as its program's linked). */
	class Building(val tessellated: Boolean, val format: VertexFormat)

	private val building = ThreadLocal<Building?>()

	@JvmStatic
	fun building(): Building? = building.get()

	private class Key(val original: ShaderInstance, val tessellated: Boolean) {
		override fun equals(other: Any?) = other is Key && other.original === original && other.tessellated == tessellated
		override fun hashCode() = System.identityHashCode(original) * 2 + if (tessellated) 1 else 0
	}

	/** Twins by their original (null where one couldn't be made: not tried again until the shaders reload). */
	private val twins = HashMap<Key, ShaderInstance?>()

	/** [original]'s twin, built the first time it's asked for; null if it can't have one. */
	fun twin(original: ShaderInstance, tessellated: Boolean): ShaderInstance? {
		// (Only vanilla's own ShaderInstance, read from its JSON: Veil's program wrappers and shader packs' are left be.)
		if (original.javaClass != ShaderInstance::class.java) return null
		// (Not getOrPut: it takes a null, a twin that couldn't be made, as none yet, and would try again every draw.)
		val key = Key(original, tessellated)
		if (twins.containsKey(key)) return twins[key]
		return twins.getOrPut(key) {
			building.set(Building(tessellated, original.vertexFormat))
			try {
				ShaderInstance(Minecraft.getInstance().resourceManager, ResourceLocation.parse(original.name), original.vertexFormat)
			} catch (e: Exception) {
				CreateRelativity.LOGGER.error("Lens: couldn't make {}'s twin; it draws unlensed", original.name, e)
				null
			} finally {
				building.remove()
			}
		}
	}

	/**
	 * Every twin dropped (the shaders reloaded: their originals are gone). Their programs deleted, not their
	 * ShaderInstances closed: that would delete the vertex and fragment shaders they share with the originals.
	 */
	fun clear() {
		for (twin in twins.values) if (twin != null) GL20.glDeleteProgram(twin.id)
		twins.clear()
	}

	/** The shader a shard swapped out, put back after its render type. */
	private var swapped: ShaderInstance? = null

	/**
	 * Registers the shards (at mod construction, LensTwinShards): on every render type with a shader drawn as plain
	 * triangles the tessellated twin's (when tessellation's on), on those drawn as strips or fans the plain twin's.
	 */
	fun register() {
		LensTwinShards.register(shard(TessellationStage.enabled), shard(false))
	}

	/** Swaps the render type's shader (its own shader state, just set) for its twin while the lens is on; puts it back after. */
	private fun shard(tessellated: Boolean): RenderStateShard = object : RenderStateShard(
		if (tessellated) "create_relativity:lens_twin_tessellated" else "create_relativity:lens_twin",
		Runnable {
			if (!VertexLens.drawingLevel || !VertexLens.activeThisFrame) return@Runnable
			val original = RenderSystem.getShader() ?: return@Runnable
			val twin = twin(original, tessellated) ?: return@Runnable
			swapped = original
			RenderSystem.setShader { twin }
		},
		Runnable {
			swapped?.let { original -> RenderSystem.setShader { original } }
			swapped = null
		},
	) {}
}
