package net.kernelpanicsoft.relativity.client.lens

import net.kernelpanicsoft.relativity.CreateRelativity
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL32
import org.lwjgl.opengl.GL40

/**
 * The image stage: world geometry drawn at its other images too, in the one pass. The light table keeps up to
 * [LightTable.SLOTS] signatures (which side of each hole's axis the light ends up), slot 0 the primary images'; each
 * other slot's image of a point is found as the vertex lens finds the primary (the slot's seed map, then Newton across
 * the table: vertex_lens_core.glsl's relativityTableImageIn). A lensed world program (its vertex shader carries the vertex
 * lens: vertex_lens.glsl) gets, as it's linked, its vertex shader swapped for a copy with its outputs renamed that also
 * finds each vertex's image in every other slot, and a geometry shader that passes the triangle through (the primary, as
 * the vertex lens bent it) and draws it again in each slot all three corners have an image in: at the image's view
 * direction, the corner's true distance, wound to face the camera (a mirrored image comes out the other way round: the
 * pixel check is told, to tell a face's front from its back).
 * Built from the linked shader's own source (whatever Veil made of it), so any lensed program takes it; programs whose
 * outputs it can't read are left as they are.
 */
object ImageStage {
	/** Whether lensed programs get the stage when next linked (a resource reload relinks them); on unless -Drelativity.imageStage=false. */
	@JvmField
	var enabled = System.getProperty("relativity.imageStage", "true").toBoolean()

	/**
	 * How near a hole (in its r_s; the block's [28].w) vertices' other images are looked for: farther out they're thin
	 * slivers hugging the photon ring, left to the post pass.
	 */
	@JvmField
	var reach = 20f

	/** Each program's shaders made here (to detach and delete when it's linked again). */
	private val made = HashMap<Int, IntArray>()

	private val outRegex = Regex("""^((?:layout\s*\([^)]*\)\s*)?(?:(?:flat|smooth|noperspective|centroid|invariant)\s+)*)out\s+(\w+)\s+(\w+)\s*;""", RegexOption.MULTILINE)

	private class Output(val qualifiers: String, val type: String, val name: String)

	/**
	 * Program [program]'s shaders attached, before it's linked: if it's a lens twin being built ([LensTwins]), its own
	 * vertex and fragment shaders swapped for the lens's copies, with the stages added between. Each stage's source
	 * goes through Veil's processing for vanilla shaders (VanillaShaderProcessor: every shader injection and processor
	 * Veil runs, other mods' too) under its own id, create_relativity:shaders/stages/<file>: the lens's pieces come in
	 * by shader injections on those ids (the core, the per-vertex part, the pixel check).
	 */
	@JvmStatic
	fun attach(program: Int, name: String) {
		made.remove(program)?.forEach { shader ->
			if (attached(program).contains(shader)) GL20.glDetachShader(program, shader)
			GL20.glDeleteShader(shader)
		}
		TessellationStage.forget(program)
		val building = LensTwins.building() ?: return
		val tessellate = building.tessellated
		val format = building.format
		val present = attached(program)
		val vertex = present.firstOrNull { GL20.glGetShaderi(it, GL20.GL_SHADER_TYPE) == GL20.GL_VERTEX_SHADER } ?: return
		val vertexSource = GL20.glGetShaderSource(vertex, GL20.glGetShaderi(vertex, GL20.GL_SHADER_SOURCE_LENGTH))
		val outputs = outRegex.findAll(vertexSource).map { Output(it.groupValues[1].trim(), it.groupValues[2], it.groupValues[3]) }.toList()
		val text = renamedSource(vertexSource, outputs) ?: return
		val shaders = ArrayList<Int>()
		fun fail(): Unit = shaders.forEach { GL20.glDeleteShader(it) }
		val varyings = outputs.map { Triple(it.type, renamed(it.name), it.qualifiers.contains("flat")) }
		// (Its own main, then the lens's tail: vertex_tail.glsl, or vertex_tess_tail.glsl tessellated.)
		val tail = StageFiles.text(if (tessellate) "vertex_tess_tail.glsl" else "vertex_tail.glsl") ?: return
		val main = "$text\n$tail\nvoid main() {\n    relativityStageMain();\n    relativityStageTail();\n}\n"
		if (tessellate) {
			// The vertex shader passes its position on unbent, and its bent one beside it; the evaluation stage bends the
			// new vertices and adds the per-vertex part.
			val source = StageFiles.process(TessellationStage.vertexSource(main, varyings.map { it.second }), "vertex_tess.vsh", GL20.GL_VERTEX_SHADER, name, format) ?: return
			shaders += compile(GL20.GL_VERTEX_SHADER, source, "vertex (tessellated)") ?: return
			val control = StageFiles.process(TessellationStage.controlSource(varyings) ?: return fail(), "lens.tcsh", GL40.GL_TESS_CONTROL_SHADER, name, format) ?: return fail()
			shaders += compile(GL40.GL_TESS_CONTROL_SHADER, control, "control") ?: return fail()
			val evaluation = StageFiles.process(TessellationStage.evaluationSource(varyings) ?: return fail(), "lens.tesh", GL40.GL_TESS_EVALUATION_SHADER, name, format) ?: return fail()
			shaders += compile(GL40.GL_TESS_EVALUATION_SHADER, evaluation, "evaluation") ?: return fail()
		} else {
			// The vertex shader bends its position (the lens's tail) and adds the per-vertex part.
			val source = StageFiles.process(main, "vertex.vsh", GL20.GL_VERTEX_SHADER, name, format) ?: return
			shaders += compile(GL20.GL_VERTEX_SHADER, source, "vertex") ?: return
		}
		val geometry = StageFiles.process(geometrySource(outputs) ?: return fail(), "image.gsh", GL32.GL_GEOMETRY_SHADER, name, format) ?: return fail()
		shaders += compile(GL32.GL_GEOMETRY_SHADER, geometry, "geometry") ?: return fail()
		// (Its own fragment shader, as it is: the triangles are judged whole above, no check per pixel.)
		GL20.glDetachShader(program, vertex)
		for (shader in shaders) GL20.glAttachShader(program, shader)
		made[program] = shaders.toIntArray()
		if (tessellate) TessellationStage.added(program)
		CreateRelativity.LOGGER.debug("Lens twin of {}: {} stages", name, shaders.size)
	}

	private fun attached(program: Int): IntArray {
		val count = GL20.glGetProgrami(program, GL20.GL_ATTACHED_SHADERS)
		if (count == 0) return IntArray(0)
		val shaders = IntArray(count)
		GL20.glGetAttachedShaders(program, intArrayOf(0), shaders)
		return shaders
	}

	private fun renamed(name: String) = "relativityStage_$name"

	/** The vertex shader with its outputs renamed (as the stages after read them) and its main renamed relativityStageMain. */
	private fun renamedSource(source: String, outputs: List<Output>): String? {
		var text = source
		for (output in outputs) text = text.replace(Regex("""\b${output.name}\b"""), renamed(output.name))
		val main = Regex("""\bvoid\s+main\s*\(\s*\)""")
		if (main.findAll(text).count() != 1) return null
		return text.replace(main, "void relativityStageMain()")
	}

	/**
	 * The geometry shader: the triangle passed through; then, for each image of its first corner (any slot) with the
	 * others' nearest images (any slot) close by, drawn there too (counter-clockwise on screen, so culling keeps it; relativityChecked 2, or 3 where that turned it
	 * round: the pixel check checks it by the table alone and turns its normal back).
	 */
	private fun geometrySource(outputs: List<Output>): String? {
		val file = StageFiles.text("image.gsh") ?: return null
		return StageFiles.fill(file, mapOf(
			"varyings" to outputs.joinToString("\n") { o -> "${o.qualifiers} in ${o.type} ${renamed(o.name)}[];\n${o.qualifiers} out ${o.type} ${o.name};" },
			"copy" to outputs.joinToString("\n") { o -> "    ${o.name} = ${renamed(o.name)}[corner];" },
			// (Its other images drawn, or the triangle alone: -Drelativity.imageStage=false.)
			"images" to "const bool RELATIVITY_STAGE_IMAGES = $enabled;",
		))
	}

	private fun compile(type: Int, source: String, what: String): Int? {
		val shader = GL20.glCreateShader(type)
		GL20.glShaderSource(shader, source)
		GL20.glCompileShader(shader)
		if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) {
			CreateRelativity.LOGGER.error("Image stage {} shader: {}", what, GL20.glGetShaderInfoLog(shader))
			if (dumped++ < 2) CreateRelativity.LOGGER.error("Image stage {} source:\n{}", what, source)
			GL20.glDeleteShader(shader)
			return null
		}
		return shader
	}

	private var dumped = 0
}
