package net.kernelpanicsoft.relativity.client.lens

import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nyon.klf.MOD_BUS
import foundry.veil.api.client.registry.PostPipelineStageRegistry
import foundry.veil.api.client.render.VeilRenderSystem
import foundry.veil.api.client.render.framebuffer.AdvancedFbo
import foundry.veil.api.client.render.framebuffer.FramebufferManager
import foundry.veil.api.client.render.post.PostPipeline
import foundry.veil.api.client.render.shader.program.ShaderProgram
import net.kernelpanicsoft.relativity.CreateRelativity
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import org.lwjgl.opengl.GL11
import java.util.Optional

/**
 * A post stage that runs an expensive shader only where a cheap one says it's needed (`create_relativity:sparse_march`
 * in a pipeline): [mark] draws every texel of [out], and writes depth 0 where it's done and 1 where it isn't; then
 * [march] draws only where the depth is 1. The GPU drops the rest before shading, so the texels that need the work run
 * packed together, instead of each holding up the neighbours it's shaded alongside. [out] needs a depth buffer.
 */
class SparseMarchStage(
	private val mark: ResourceLocation,
	private val march: ResourceLocation,
	private val input: ResourceLocation?,
	private val out: ResourceLocation,
) : PostPipeline {
	override fun getType(): PostPipelineStageRegistry.PipelineType<out PostPipeline> = RelativityPostStages.SPARSE_MARCH.get()

	override fun apply(context: PostPipeline.Context) {
		val shaders = VeilRenderSystem.renderer().shaderManager
		val markShader = shaders.getShader(mark) ?: return
		val marchShader = shaders.getShader(march) ?: return
		val inFbo = input?.let { context.getFramebuffer(it) }
		val outFbo = context.getFramebufferOrDraw(out)
		val depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
		val depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC)
		val depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
		// Straight GL, set just before each draw (after Veil's setup) and put back as found: RenderSystem's cache of this
		// state needn't match GL's here.
		draw(markShader, context, inFbo, outFbo, clear = true) {
			GL11.glEnable(GL11.GL_DEPTH_TEST)
			GL11.glDepthMask(true)
			GL11.glDepthFunc(GL11.GL_ALWAYS)
		}
		// The screen quad is at depth 0.5: in front of 1 (to do), behind 0 (done).
		draw(marchShader, context, inFbo, outFbo, clear = false) {
			GL11.glEnable(GL11.GL_DEPTH_TEST)
			GL11.glDepthMask(false)
			GL11.glDepthFunc(GL11.GL_LESS)
		}
		GL11.glDepthFunc(depthFunc)
		GL11.glDepthMask(depthMask)
		if (!depthTest) GL11.glDisable(GL11.GL_DEPTH_TEST)
	}

	/** As Veil's blit stage draws a shader over its output. */
	private fun draw(shader: ShaderProgram, context: PostPipeline.Context, inFbo: AdvancedFbo?, outFbo: AdvancedFbo, clear: Boolean, depth: () -> Unit) {
		shader.bind()
		context.applySamplers(shader)
		if (inFbo != null) shader.setFramebufferSamplers(inFbo)
		outFbo.bind(true)
		if (clear) {
			GL11.glDepthMask(true)
			outFbo.clear()
		}
		shader.getUniform("OutSize")?.setVector(outFbo.width.toFloat(), outFbo.height.toFloat())
		shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLE_STRIP)
		shader.bindSamplers(context, 0)
		depth()
		VeilRenderSystem.drawScreenQuad()
		context.clearSamplers(shader)
	}

	companion object {
		val CODEC: MapCodec<SparseMarchStage> = RecordCodecBuilder.mapCodec { instance ->
			instance.group(
				ResourceLocation.CODEC.fieldOf("mark").forGetter { it.mark },
				ResourceLocation.CODEC.fieldOf("march").forGetter { it.march },
				// Framebuffer names as Veil's own stages read them (a bare name is the pipeline's, not minecraft's).
				FramebufferManager.FRAMEBUFFER_CODEC.optionalFieldOf("in").forGetter { Optional.ofNullable(it.input) },
				FramebufferManager.FRAMEBUFFER_CODEC.fieldOf("out").forGetter { it.out },
			).apply(instance) { mark, march, input, out -> SparseMarchStage(mark, march, input.orElse(null), out) }
		}
	}
}

/**
 * Our Veil post pipeline stage types (client only: Veil's post pipelines are).
 *
 * A plain NeoForge [DeferredRegister], which waits for the registry event: Veil makes this registry only then, so
 * Architectury's (and Archie's RegistryHelper over it), which looks the registry up when registering, can't find it;
 * Veil's own RegistrationProvider rejects KotlinLangForge's mod container.
 */
object RelativityPostStages {
	private val REGISTER = DeferredRegister.create(PostPipelineStageRegistry.REGISTRY_KEY, CreateRelativity.MOD_ID)

	val SPARSE_MARCH: DeferredHolder<PostPipelineStageRegistry.PipelineType<*>, PostPipelineStageRegistry.PipelineType<SparseMarchStage>> =
		REGISTER.register("sparse_march") { -> PostPipelineStageRegistry.PipelineType(SparseMarchStage.CODEC) }

	fun init() {
		REGISTER.register(MOD_BUS)
	}
}
