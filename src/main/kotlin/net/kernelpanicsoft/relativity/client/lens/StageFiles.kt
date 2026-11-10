package net.kernelpanicsoft.relativity.client.lens

import foundry.veil.api.client.render.VeilRenderSystem
import foundry.veil.impl.client.render.shader.processor.VanillaShaderProcessor
import net.kernelpanicsoft.relativity.CreateRelativity
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import org.lwjgl.opengl.GL

/**
 * The lens's stage files (assets/create_relativity/shaders/stages: image.gsh, lens.tcsh, lens.tesh), each a whole
 * shader but for what depends on the program it's added to (lines `//@<name>`, filled in per program: [fill]); and
 * every stage source's processing ([process]): Veil's, as it processes vanilla shaders.
 */
object StageFiles {
	/** Stage file [name]'s text (read each time: a resource reload relinks the programs, picking up edits). */
	fun text(name: String): String? =
		Minecraft.getInstance().resourceManager.getResource(id(name)).orElse(null)?.open()?.use { String(it.readAllBytes(), Charsets.UTF_8) }

	/** The id a stage's source is processed under (shader injections target it): create_relativity:shaders/stages/<file>. */
	fun id(file: String): ResourceLocation = CreateRelativity.id("shaders/stages/$file")

	/** [text] with each line `//@<name>` replaced by [parts]' entry for it (a missing one by nothing). */
	fun fill(text: String, parts: Map<String, String>): String =
		text.lines().joinToString("\n") { line ->
			val marker = line.trim()
			if (marker.startsWith("//@")) parts[marker.removePrefix("//@")] ?: "" else line
		}

	/**
	 * [source] through Veil's processing for vanilla shaders (VanillaShaderProcessor: its shader injections, dynamic
	 * buffers, and every processor mods add) as stage [file] of [type] for shader [shader] (its vertex format [format]);
 * null if it fails (logged).
	 * (Veil's own chain, reached through its internal class: what every vanilla shader goes through.) Under the stage's own
 * id as the shader's name too: a copy of a shader already processed (its vertex shader's) mustn't have the processors
 * that act on that shader's name run on it again; the lens's injections, on the stage's id, still are.
	 */
	fun process(source: String, file: String, type: Int, shader: String, format: com.mojang.blaze3d.vertex.VertexFormat): String? {
		val renderer = VeilRenderSystem.renderer()
		return try {
			VanillaShaderProcessor.setup(Minecraft.getInstance().resourceManager)
			VanillaShaderProcessor.modify(HashMap(), id(file).toString(), id(file), format, renderer.dynamicBufferManger.activeBuffers, type, source, GL.getCapabilities())
		} catch (e: Exception) {
			CreateRelativity.LOGGER.error("Lens: Veil couldn't process {} for {}", file, shader, e)
			null
		} finally {
			VanillaShaderProcessor.free()
		}
	}
}
