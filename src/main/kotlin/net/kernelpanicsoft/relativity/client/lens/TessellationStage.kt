package net.kernelpanicsoft.relativity.client.lens

import net.kernelpanicsoft.relativity.CreateRelativity
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL40

/**
 * The tessellation stage: a lensed program's triangles split on the GPU as finely as the lens bends them, so there are
 * always enough vertices for the curve of their images, wherever the lens is and however it moves (no meshes rebuilt).
 * Added round the linked program's own vertex and fragment shaders as the image stage ([ImageStage]) is (whatever made
 * them: vanilla, Veil's injections, other mods'): the vertex shader's copy leaves gl_Position unbent (its outputs
 * renamed into the patch); the control stage (shaders/stages/lens.tcsh) splits each edge by how far the lens bends its
 * midpoint's image off straight; the evaluation stage (lens.tesh) interpolates every output, bends each new vertex and
 * adds the image stage's per-vertex part (image_vertex.glsl), its geometry stage after, unchanged. Drawn as patches
 * ([patches]: GlStateManager).
 *
 * Terrain programs only so far ([TARGETS]: always drawn as triangles); on with -Drelativity.tessellation=true.
 */
object TessellationStage {
	@JvmField
	var enabled = System.getProperty("relativity.tessellation", "false").toBoolean()

	/** Whether the stage splits the terrain (vanilla's: not with Sodium, whose chunk programs it doesn't take yet): QuadSubdivision's off. */
	val splitsTerrain: Boolean by lazy { enabled && !net.neoforged.fml.ModList.get().isLoaded("sodium") }

	/** The programs tessellated (by their ShaderInstance names). */
	private val TARGETS = setOf("rendertype_solid", "rendertype_cutout", "rendertype_cutout_mipped", "rendertype_translucent")

	/** Programs given the stage: drawn as patches. */
	private val tessellated = HashSet<Int>()

	/** The program in use (GlStateManager._glUseProgram). */
	@JvmField
	var current = 0

	fun wanted(name: String): Boolean = enabled && name.substringAfterLast('/').substringAfterLast(':') in TARGETS

	/** Whether triangles drawn now must go as patches; sets the patch size if so. */
	@JvmStatic
	fun patches(mode: Int): Boolean {
		if (mode != GL11.GL_TRIANGLES || current !in tessellated) return false
		GL40.glPatchParameteri(GL40.GL_PATCH_VERTICES, 3)
		return true
	}

	fun forget(program: Int) {
		tessellated.remove(program)
	}

	fun added(program: Int) {
		tessellated += program
	}

	private fun intoPatch(name: String) = "relativityTess_$name"
	private fun fromPatch(name: String) = "relativityTessC_$name"

	/**
	 * The vertex shader for the stage: [source] with its outputs ([names]) renamed into the patch (its position left
	 * unbent, its bent one beside it: lens_vertex_tess.glsl, by its shader injection).
	 */
	fun vertexSource(source: String, names: List<String>): String {
		var text = source
		for (name in names) text = text.replace(Regex("""\b$name\b"""), intoPatch(name))
		return text
	}

	/**
	 * Whether the evaluation stage takes new vertices' images from the light table where it has one (else the whole
	 * vertex lens for each). Off unless -Drelativity.tessellationTable=true: the table's images can be off by far more
	 * than a pixel (by a wall, a fold: another image's), placing vertices wrong.
	 */
	/**
	 * Debug (the lens block's [8].x): bit 1 the control stage's midpoints left unlensed (edges split only by the field
	 * types' rules), 2 the per-vertex second images not looked for, 4 the geometry stage's copies not drawn.
	 */
	@JvmField
	var skip = 0

	/** Debug: the most pieces an edge's split into (the lens block's [11].w), 0 the control stage's own. */
	@JvmField
	var most = System.getProperty("relativity.tessellationMost", "0").toFloat()

	@JvmField
	var tableVertices = System.getProperty("relativity.tessellationTable", "false").toBoolean()

	private fun qualifier(flat: Boolean) = if (flat) "flat " else ""

	/** The control stage (lens.tcsh) for varyings [outputs] (type, name as the geometry stage reads it, whether flat). */
	fun controlSource(outputs: List<Triple<String, String, Boolean>>): String? {
		val file = StageFiles.text("lens.tcsh") ?: return null
		// (Each varying's interpolation as the vertex shader declared it: linking needs them to match.)
		return StageFiles.fill(file, mapOf(
			"fields" to net.kernelpanicsoft.relativity.client.lens.field.LensFields.include,
			"varyings" to outputs.joinToString("\n") { (type, name, flat) -> "${qualifier(flat)}in $type ${intoPatch(name)}[];\n${qualifier(flat)}out $type ${fromPatch(name)}[];" },
			"copy" to outputs.joinToString("\n") { (_, name, _) -> "    ${fromPatch(name)}[gl_InvocationID] = ${intoPatch(name)}[gl_InvocationID];" },
		))
	}

	/**
	 * The evaluation stage (lens.tesh) for varyings [outputs] (as [controlSource]'s), [bent] relativityBent's name if
	 * the program has one.
	 */
	fun evaluationSource(outputs: List<Triple<String, String, Boolean>>): String? {
		val file = StageFiles.text("lens.tesh") ?: return null
		fun integer(type: String) = type.startsWith("int") || type.startsWith("uint") || type.startsWith("ivec") || type.startsWith("uvec")
		return StageFiles.fill(file, mapOf(
			"table" to "const bool RELATIVITY_TESS_TABLE = $tableVertices;",
			"varyings" to outputs.joinToString("\n") { (type, name, flat) -> "${qualifier(flat)}in $type ${fromPatch(name)}[];\n${qualifier(flat)}out $type $name;" },
			"interpolate" to outputs.joinToString("\n") { (type, name, flat) ->
				val f = fromPatch(name)
				if (flat || integer(type)) "    $name = $f[0];" else "    $name = relativityTessW.x * $f[0] + relativityTessW.y * $f[1] + relativityTessW.z * $f[2];"
			},
		))
	}

	fun compile(type: Int, source: String, what: String): Int? {
		val shader = GL20.glCreateShader(type)
		GL20.glShaderSource(shader, source)
		GL20.glCompileShader(shader)
		if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) {
			CreateRelativity.LOGGER.error("Tessellation stage {} shader: {}", what, GL20.glGetShaderInfoLog(shader))
			if (dumped++ < 2) CreateRelativity.LOGGER.error("Tessellation stage {} source:\n{}", what, source)
			GL20.glDeleteShader(shader)
			return null
		}
		return shader
	}

	private var dumped = 0
}
