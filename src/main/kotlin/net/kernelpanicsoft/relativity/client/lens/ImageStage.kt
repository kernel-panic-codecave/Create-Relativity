package net.kernelpanicsoft.relativity.client.lens

import net.kernelpanicsoft.relativity.CreateRelativity
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL32

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

	/**
	 * How many other images a triangle is drawn at, at most: the geometry stage's declared output, which (on Intel at
	 * least) costs every triangle through it, drawn again or not.
	 */
	private const val EMITTED = 1


	/** The slots other than the primary's: their images, two to a vec4. */
	private const val OTHERS = 3

	/** Each program's shaders made here (to detach and delete when it's linked again). */
	private val made = HashMap<Int, IntArray>()

	private val outRegex = Regex("""^((?:layout\s*\([^)]*\)\s*)?(?:(?:flat|smooth|noperspective|centroid|invariant)\s+)*)out\s+(\w+)\s+(\w+)\s*;""", RegexOption.MULTILINE)

	private class Output(val qualifiers: String, val type: String, val name: String)

	/** Program [program]'s shaders attached, before it's linked: the stage's in, if it's lensed. */
	@JvmStatic
	fun attach(program: Int) {
		made.remove(program)?.forEach { shader ->
			if (attached(program).contains(shader)) GL20.glDetachShader(program, shader)
			GL20.glDeleteShader(shader)
		}
		if (!enabled) return
		val shaders = attached(program)
		val vertex = shaders.firstOrNull { GL20.glGetShaderi(it, GL20.GL_SHADER_TYPE) == GL20.GL_VERTEX_SHADER } ?: return
		if (shaders.any { GL20.glGetShaderi(it, GL20.GL_SHADER_TYPE) == GL32.GL_GEOMETRY_SHADER }) return
		val source = GL20.glGetShaderSource(vertex, GL20.glGetShaderi(vertex, GL20.GL_SHADER_SOURCE_LENGTH))
		if (!source.contains("relativityUnbent") || !source.contains("relativityChecked")) return
		val outputs = outRegex.findAll(source).map { Output(it.groupValues[1].trim(), it.groupValues[2], it.groupValues[3]) }.toList()
		if (outputs.none { it.name == "relativityUnbent" } || outputs.none { it.name == "relativityChecked" }) return
		val renamedVertex = compile(GL20.GL_VERTEX_SHADER, vertexSource(source, outputs) ?: return, "vertex") ?: return
		val geometry = compile(GL32.GL_GEOMETRY_SHADER, geometrySource(source, outputs), "geometry")
		if (geometry == null) {
			GL20.glDeleteShader(renamedVertex)
			return
		}
		GL20.glDetachShader(program, vertex)
		GL20.glAttachShader(program, renamedVertex)
		GL20.glAttachShader(program, geometry)
		made[program] = intArrayOf(renamedVertex, geometry)
	}

	private fun attached(program: Int): IntArray {
		val count = GL20.glGetProgrami(program, GL20.GL_ATTACHED_SHADERS)
		if (count == 0) return IntArray(0)
		val shaders = IntArray(count)
		GL20.glGetAttachedShaders(program, intArrayOf(0), shaders)
		return shaders
	}

	private fun renamed(name: String) = "relativityStage_$name"

	/** The vertex shader with its outputs renamed, its main kept, and a new main that finds its other images after. */
	private fun vertexSource(source: String, outputs: List<Output>): String? {
		var text = source
		for (output in outputs) text = text.replace(Regex("""\b${output.name}\b"""), renamed(output.name))
		val main = Regex("""\bvoid\s+main\s*\(\s*\)""")
		if (main.findAll(text).count() != 1) return null
		text = text.replace(main, "void relativityStageMain()")
		val unbent = renamed("relativityUnbent")
		val checked = renamed("relativityChecked")
		return """$text
out vec4 relativityStageA;
out vec2 relativityStageB;
out vec2 relativityStageP;
out vec4 relativityStageT;

// Where the camera sees p in slot s (table position; 0 none).
vec2 relativityStageImage(vec3 p, int s) {
    vec3 seen;
    vec2 uv;
    if (!relativityTableImageIn(p, s, seen) || !relativityTableUv(seen, uv)) return vec2(0.0);
    return uv;
}

// Whether p is within the stage's reach of a hole.
bool relativityStageNear(vec3 p) {
    for (int k = 0; k < relativityFieldCount(); k += 1) {
        if (relativityFieldHole(k) && length(p - relativityFieldCentre(k)) < RelativityLensData[28].w * relativityFieldParams(k).x) return true;
    }
    return false;
}

void main() {
    relativityStageMain();
    // Tubes alone, seen from outside: by a tube's outline, which way the vertex is seen (w 1 in plain view, 2 through a
    // tube); else w 0.
    relativityStageT = vec4(0.0);
    bool tubesAlone = relativityWalls() && relativityFieldCount() == 0 && relativityTubeCount() > 0 && relativityTubeRegion(vec3(0.0)) < 0;
    if (tubesAlone && $checked > 0.5 && relativityTubeByWall($unbent)) {
        relativityStageT.w = 1.0;
        if (!relativityTubeClear($unbent)) relativityStageT.w = 2.0;
    }
    relativityStageA = vec4(0.0);
    relativityStageB = vec2(0.0);
    relativityStageP = vec2(0.0);
    if ($checked > 0.5 && relativityFieldComposed() && relativityTableOn() && relativityStageNear($unbent)) {
        // (Its primary too, as the vertex lens found it: a triangle across a seam between slots is matched across them.)
        relativityStageP = relativityStageImage($unbent, 0);
        relativityStageA = vec4(relativityStageImage($unbent, 1), relativityStageImage($unbent, 2));
        relativityStageB = relativityStageImage($unbent, 3);
    }
}
"""
	}

	/**
	 * The geometry shader: the triangle passed through; then, for each image of its first corner (any slot) with the
	 * others' nearest images (any slot) close by, drawn there too (counter-clockwise on screen, so culling keeps it; relativityChecked 2, or 3 where that turned it
	 * round: the pixel check checks it by the table alone and turns its normal back).
	 */
	private fun geometrySource(source: String, outputs: List<Output>): String {
		val block = Regex("""layout\s*\(\s*std140\s*\)\s*uniform\s+RelativityLens\s*\{[^}]*\}\s*;""").find(source)?.value
			?: "layout(std140) uniform RelativityLens {\n    vec4 RelativityLensData[151];\n};"
		val declarations = outputs.joinToString("\n") { o ->
			"${o.qualifiers} in ${o.type} ${renamed(o.name)}[];\n${o.qualifiers} out ${o.type} ${o.name};"
		}
		fun copy(corner: String, indent: String) = outputs.joinToString("\n") { o -> "$indent${o.name} = ${renamed(o.name)}[$corner];" }
		val hasBent = outputs.any { it.name == "relativityBent" }
		return """#version 150
layout(triangles) in;
layout(triangle_strip, max_vertices = ${3 * (EMITTED + 1)}) out;
$block
$declarations
in vec4 relativityStageA[];
in vec2 relativityStageB[];
in vec2 relativityStageP[];
in vec4 relativityStageT[];

vec2 relativityStageImage(int corner, int s) {
    if (s == 0) return relativityStageP[corner];
    if (s == 1) return relativityStageA[corner].xy;
    if (s == 2) return relativityStageA[corner].zw;
    return relativityStageB[corner];
}

vec3 relativityStageAt(int corner, vec2 uv) {
    vec2 t = (2.0 * uv / RelativityLensData[7].xy - 1.0) * RelativityLensData[6].xy;
    return normalize(vec3(t, -1.0)) * length(${renamed("relativityUnbent")}[corner]);
}

void relativityStageEmit(int corner, vec3 at, float checked) {
${copy("corner", "    ")}
    gl_Position = mat4(RelativityLensData[2], RelativityLensData[3], RelativityLensData[4], RelativityLensData[5]) * vec4(at, 1.0);
${if (hasBent) "    relativityBent = at;" else ""}
    relativityChecked = checked;
    EmitVertex();
}

void relativityStagePass(int corner) {
${copy("corner", "    ")}
    gl_Position = gl_in[corner].gl_Position;
    EmitVertex();
}

void main() {
    for (int corner = 0; corner < 3; corner += 1) relativityStagePass(corner);
    EndPrimitive();
${tubeCopies()}
    int emitted = 0;
    if (across) emitted = $EMITTED;
    for (int s = 0; s <= $OTHERS && emitted < $EMITTED; s += 1) {
        vec2 a = relativityStageImage(0, s);
        if (a.x <= 0.0) continue;
        vec2 b = vec2(0.0);
        vec2 c = vec2(0.0);
        int sb = -1;
        int sc = -1;
        float nb = 30.0;
        float nc = 30.0;
        for (int t = 0; t <= $OTHERS; t += 1) {
            vec2 cb = relativityStageImage(1, t);
            vec2 cc = relativityStageImage(2, t);
            float db = distance(cb, a) - (t == s ? 0.5 : 0.0);
            float dc = distance(cc, a) - (t == s ? 0.5 : 0.0);
            if (cb.x > 0.0 && db < nb) {
                nb = db;
                b = cb;
                sb = t;
            }
            if (cc.x > 0.0 && dc < nc) {
                nc = dc;
                c = cc;
                sc = t;
            }
        }
        if (sb < 0 || sc < 0 || (s == 0 && sb == 0 && sc == 0)) continue;
        // (Far apart on the table: on different sheets, across a fold.)
        if (distance(b, c) > 30.0) continue;
        vec3 pa = relativityStageAt(0, a);
        vec3 pb = relativityStageAt(1, b);
        vec3 pc = relativityStageAt(2, c);
        // (Its winding on the table's plane, which is the screen's: x right, y up.)
        vec2 ab = b - a;
        vec2 ac = c - a;
        if (ab.x * ac.y - ab.y * ac.x >= 0.0) {
            relativityStageEmit(0, pa, 2.0);
            relativityStageEmit(1, pb, 2.0);
            relativityStageEmit(2, pc, 2.0);
        } else {
            relativityStageEmit(0, pa, 3.0);
            relativityStageEmit(2, pc, 3.0);
            relativityStageEmit(1, pb, 3.0);
        }
        EndPrimitive();
        emitted += 1;
    }
}
"""
	}

	/**
	 * The geometry shader's tube copy: a triangle across a tube's outline (its corners seen through the tube and not:
	 * stretched across, the pixel check keeps little of it) drawn again with every corner seen straight (exact: outside
	 * the tubes light runs straight); the pixel check keeps the part that shows, so the outline's edge runs between
	 * pixels, not corners. (Drawn again seen through as well, by the through corners' images, it added nothing measurable
	 * and declaring the room for it cost a sixth of the frame rate.) In place of another image (the stage draws one more
	 * triangle at most): `across` says so.
	 */
	private fun tubeCopies(): String = """    float w0 = relativityStageT[0].w;
    float w1 = relativityStageT[1].w;
    float w2 = relativityStageT[2].w;
    bool across = w0 > 0.5 && w1 > 0.5 && w2 > 0.5 && (abs(w0 - w1) > 0.5 || abs(w0 - w2) > 0.5);
    if (across) {
        for (int corner = 0; corner < 3; corner += 1) {
            if (relativityStageT[corner].w < 1.5) relativityStagePass(corner);
            else relativityStageEmit(corner, ${renamed("relativityUnbent")}[corner], 1.0);
        }
        EndPrimitive();
    }"""


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
