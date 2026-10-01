package net.kernelpanicsoft.relativity.client.lens

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.server.packs.resources.ResourceManager
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil

/** The Schwarzschild lens table as a two-channel float texture (φ∞, captured), linearly filtered. */
class LensLookupTexture(private val width: Int, private val height: Int, private val data: FloatArray) : AbstractTexture() {
	/** Nothing to load from resources; [upload] fills it. */
	override fun load(resourceManager: ResourceManager) = Unit

	fun upload() {
		RenderSystem.assertOnRenderThreadOrInit()
		bind()
		val buffer = MemoryUtil.memAllocFloat(data.size)
		try {
			buffer.put(data).flip()
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG32F, width, height, 0, GL30.GL_RG, GL11.GL_FLOAT, buffer)
		} finally {
			MemoryUtil.memFree(buffer)
		}
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
	}
}
