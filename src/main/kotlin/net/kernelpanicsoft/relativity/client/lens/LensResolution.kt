package net.kernelpanicsoft.relativity.client.lens

import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.config.RelativityConfig
import org.lwjgl.opengl.GL11

/**
 * The lens tables' resolution over the default (the client setting, in percent): the post pass's scene and Kerr tables
 * are sized by it (black_hole_lens.json, through CompositePostPipelineMixin). Lower is faster and softer at the lens's
 * edges; higher is sharper and costs about its square. 0 (the default) picks one for the GPU ([detected]).
 */
object LensResolution {
	const val MIN = 25
	const val MAX = 300

	/** Debug (GameTests): this percentage instead of the setting's. */
	@JvmField
	var override: Int? = null

	/** The percentage in use. */
	fun percent(): Int {
		val chosen = override ?: RelativityConfig.Client.Render.lensResolution
		return (if (chosen <= 0) detected else chosen).coerceIn(MIN, MAX)
	}

	@JvmStatic
	fun scale(): Float = percent() / 100f

	/** The resolution for this GPU, from its name (read on the render thread, once). */
	val detected: Int by lazy {
		val renderer = GL11.glGetString(GL11.GL_RENDERER).orEmpty()
		val vendor = GL11.glGetString(GL11.GL_VENDOR).orEmpty()
		forGpu(renderer, vendor).also { CreateRelativity.LOGGER.info("Lens resolution for {} ({}): {}%", renderer, vendor, it) }
	}

	/**
	 * A resolution for a GPU by its GL renderer and vendor strings: software renderers lowest; integrated GPUs (Intel's
	 * but Arc, AMD's APUs, which name no model) below the default; discrete cards above it; anything else the default.
	 */
	fun forGpu(renderer: String, vendor: String): Int {
		val r = renderer.lowercase()
		val v = vendor.lowercase()
		return when {
			listOf("llvmpipe", "softpipe", "swiftshader", "software").any { it in r } -> 25
			// (Arc cards name a model, A770, B580; Arc integrated graphics don't.)
			"intel" in r || "intel" in v -> if (Regex("""\barc\S*\s+[ab]\d{3}""").containsMatchIn(r)) 150 else 75
			("amd" in r || "radeon" in r || "amd" in v || v.startsWith("ati ")) && Regex("""\b(rx|r9|r7|pro|vii|fury|w\d)\b""").containsMatchIn(r).not() -> 75
			"nvidia" in r || "geforce" in r || "quadro" in r || "rtx" in r || "radeon" in r -> 150
			"apple m" in r -> 100
			else -> 100
		}
	}
}
