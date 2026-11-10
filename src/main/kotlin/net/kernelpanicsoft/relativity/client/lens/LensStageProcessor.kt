package net.kernelpanicsoft.relativity.client.lens

import foundry.veil.api.client.render.shader.processor.ShaderPreProcessor
import foundry.veil.platform.VeilEventPlatform
import io.github.ocelot.glslprocessor.api.node.GlslTree
import net.kernelpanicsoft.relativity.CreateRelativity

/**
 * The lens's part in Veil's shader processing (added to its chain with VeilAddShaderPreProcessorsEvent, so it runs
 * wherever Veil processes shaders, with every other mod's processors): on the lens's own stage sources only
 * (StageFiles' ids, create_relativity:shaders/stages/...), the lens core or its per-vertex part included, by Veil's own
 * include (resolved and de-duplicated as Veil does). Everything else passes through untouched.
 */
object LensStageProcessor : ShaderPreProcessor {
	/**
	 * What each stage needs included (pinwheel/shaders/include/<name>.glsl), in order: each goes in at the top, so the
	 * last comes first. (An include's own #include isn't followed here: what it needs is listed after it.)
	 */
	private val includes = mapOf(
		"shaders/stages/lens.tcsh" to listOf("vertex_lens_core"),
		"shaders/stages/lens.tesh" to listOf("lens_stage_vertex", "vertex_lens_core"),
		"shaders/stages/vertex.vsh" to listOf("lens_stage_vertex", "vertex_lens_core"),
		"shaders/stages/vertex_tess.vsh" to listOf("vertex_lens_core"),
	)

	override fun modify(ctx: ShaderPreProcessor.Context, tree: GlslTree) {
		val name = ctx.name() ?: return
		if (name.namespace != CreateRelativity.MOD_ID || !ctx.isSourceFile) return
		for (include in includes[name.path] ?: return) ctx.include(tree, CreateRelativity.id(include), ShaderPreProcessor.IncludeOverloadStrategy.SOURCE)
	}

	/** Adds it to Veil's chain (at mod construction). */
	fun register() {
		VeilEventPlatform.INSTANCE.onVeilAddShaderProcessors { _, registry -> registry.addPreprocessor(this, false) }
	}
}
