package net.kernelpanicsoft.relativity.client.lens

import net.kernelpanicsoft.relativity.config.RelativityConfig

/**
 * The lens tables' resolution over the default (the client setting, in percent): the post pass's scene and Kerr tables
 * are sized by it (black_hole_lens.json, through CompositePostPipelineMixin). Lower is faster and softer at the lens's
 * edges; higher is sharper and costs about its square.
 */
object LensResolution {
	@JvmStatic
	fun scale(): Float = (override ?: RelativityConfig.Client.Render.lensResolution).coerceIn(MIN, MAX) / 100f

	/** Debug (GameTests): this percentage instead of the setting's. */
	@JvmField
	var override: Int? = null

	const val MIN = 25
	const val MAX = 300
}
