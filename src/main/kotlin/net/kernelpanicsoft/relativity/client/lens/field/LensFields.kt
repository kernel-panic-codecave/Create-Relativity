package net.kernelpanicsoft.relativity.client.lens.field

import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.lens.LensFieldType
import net.kernelpanicsoft.relativity.lens.LensFieldWriter
import net.kernelpanicsoft.relativity.lens.LensFrame
import net.kernelpanicsoft.relativity.lens.RelativityRegistries
import net.minecraft.resources.ResourceLocation
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL31
import org.lwjgl.system.MemoryUtil

/**
 * The lens's fields on the GPU: every registered [LensFieldType] (RelativityRegistries.LENS_FIELD_TYPES, from any mod),
 * its share of one uniform block (RelativityLensFields: a header vec4 a type, x how many of it, then each type's share in
 * order), and one include of all their GLSL ([include]: lens_fields/common.glsl, then each type's accessors and code,
 * then lensFieldAt, the field at a point with every type's added in). A type's names starting `field` and a capital are
 * renamed for it (lensField_<namespace>_<path>_...), so types from different mods can't clash.
 */
object LensFields {
	/** The uniform block's name, as the include declares it. */
	const val BLOCK = "RelativityLensFields"

	private class Entry(val id: ResourceLocation, val type: LensFieldType, val header: Int, val base: Int)

	/** The types in order and their places, settled the first time they're needed (registries are frozen by then). */
	private val entries: List<Entry> by lazy {
		val types = RelativityRegistries.LENS_FIELD_TYPES.entrySet()
			.map { it.key.location() to it.value }
			.sortedWith(compareBy({ it.second.order }, { it.first.toString() }))
		var base = types.size
		types.mapIndexed { i, (id, type) -> Entry(id, type, i, base).also { base += type.slots } }
	}

	/** The block's size (vec4s). */
	val size: Int get() = entries.lastOrNull()?.let { it.base + it.type.slots } ?: 1

	/** The name prefix a type's own names get. */
	private fun prefix(id: ResourceLocation) = "lensField_" + "${id.namespace}_${id.path}".replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')

	private fun resource(id: ResourceLocation, from: Class<*>): String? =
		from.classLoader.getResourceAsStream("assets/${id.namespace}/${id.path}")?.use { String(it.readAllBytes(), Charsets.UTF_8) }

	/** The include: the block, the common code, every type's accessors and code, and the field at a point. */
	val include: String by lazy {
		val out = StringBuilder()
		out.appendLine("// The lens's fields (LensFields): put together from every registered field type's GLSL.")
		out.appendLine("layout(std140) uniform $BLOCK {")
		out.appendLine("    vec4 LensFieldData[$size];")
		out.appendLine("};")
		out.appendLine(resource(CreateRelativity.id("lens_fields/common.glsl"), LensFields::class.java) ?: error("lens_fields/common.glsl missing"))
		val calls = ArrayList<String>()
		for (entry in entries) {
			val code = resource(entry.type.glsl, entry.type.javaClass)
			if (code == null) {
				CreateRelativity.LOGGER.error("Lens field type {}: its GLSL {} is missing; left out", entry.id, entry.type.glsl)
				continue
			}
			val name = prefix(entry.id)
			out.appendLine("// ---- ${entry.id} (${entry.type.glsl}) ----")
			out.appendLine("int ${name}_Count() { return int(LensFieldData[${entry.header}].x + 0.5); }")
			out.appendLine("vec4 ${name}_Data(int i) { return LensFieldData[${entry.base} + clamp(i, 0, ${entry.type.slots - 1})]; }")
			out.appendLine(code.replace(Regex("""\bfield([A-Z][A-Za-z0-9_]*)\b""")) { "${name}_${it.groupValues[1]}" })
			calls += "    if (${name}_Count() > 0) ${name}_Sample(x, s);"
		}
		out.appendLine("// The field at x: every type's sources added in.")
		out.appendLine("LensFieldSample lensFieldAt(vec3 x) {")
		out.appendLine("    LensFieldSample s = lensFieldFlat();")
		calls.forEach { out.appendLine(it) }
		out.appendLine("    return s;")
		out.appendLine("}")
		out.toString()
	}

	private var buffer = 0
	private var binding = -1

	/** Each frame: every type's sources into the block (each its own share), the header's counts with them. */
	fun upload(frame: LensFrame) {
		val floats = MemoryUtil.memAllocFloat(4 * size)
		try {
			for (i in 0 until 4 * size) floats.put(i, 0f)
			for (entry in entries) {
				val count = try {
					entry.type.upload(frame, LensFieldWriter(floats, entry.base, entry.type.slots))
				} catch (e: Exception) {
					CreateRelativity.LOGGER.error("Lens field type {} failed to upload", entry.id, e)
					0
				}
				floats.put(4 * entry.header, count.toFloat())
			}
			if (buffer == 0) {
				buffer = GL15.glGenBuffers()
				GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, buffer)
				GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, 16L * size, GL15.GL_DYNAMIC_DRAW)
			}
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, buffer)
			GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0, floats)
			GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0)
			GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, binding(), buffer)
		} finally {
			MemoryUtil.memFree(floats)
		}
	}

	/** The block's binding point: the one below the lens's own (VertexLens). */
	private fun binding(): Int {
		if (binding < 0) binding = GL11.glGetInteger(GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS) - 2
		return binding
	}

	/** Points [program]'s field block (if it has one) at the binding. */
	fun link(program: Int) {
		val block = GL31.glGetUniformBlockIndex(program, BLOCK)
		if (block != GL31.GL_INVALID_INDEX) GL31.glUniformBlockBinding(program, block, binding())
	}
}
