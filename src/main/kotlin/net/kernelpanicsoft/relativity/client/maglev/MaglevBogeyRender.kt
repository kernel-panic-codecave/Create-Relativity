package net.kernelpanicsoft.relativity.client.maglev

import com.mojang.blaze3d.vertex.PoseStack
import com.simibubi.create.content.trains.bogey.BogeyRenderer
import com.simibubi.create.content.trains.bogey.BogeyStyle
import com.simibubi.create.content.trains.bogey.BogeyVisual
import com.simibubi.create.content.trains.bogey.BogeyVisualizer
import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.model.baked.BakedModelBuilder
import net.kernelpanicsoft.relativity.CreateRelativity
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.client.renderer.block.model.ItemOverrides
import net.minecraft.client.renderer.block.model.ItemTransforms
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.client.resources.model.Material
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.neoforge.client.ChunkRenderTypeSet
import net.neoforged.neoforge.client.event.ModelEvent
import net.neoforged.neoforge.client.model.IDynamicBakedModel
import net.neoforged.neoforge.client.model.data.ModelData
import net.neoforged.neoforge.client.model.pipeline.QuadBakingVertexConsumer

/**
 * The maglev bogey below Create's top plate: a 2 x 2 of magnets hovering in the guideway (2 px over its floor), north
 * poles forward (+z: red faces forward, blue back, coils red-then-blue along the sides), under a steel chassis plate
 * and a column up to the top plate. Coordinates as Create's bogeys': x across, z along, from the bottom of the track's
 * block (its floor is 1 px up), in pixels.
 */
object MaglevBogeyRender {
	private lateinit var sprite: TextureAtlasSprite

	/** The bogey's quads (once the texture's baked). */
	private val quads: List<BakedQuad> by lazy { build() }

	/** A box, each face textured from a region of the bogey's texture (pixels: u0, v0, u1, v1), none for faces left out. */
	private class Box(val x0: Float, val y0: Float, val z0: Float, val x1: Float, val y1: Float, val z1: Float, val faces: Map<Direction, FloatArray>)

	private fun build(): List<BakedQuad> {
		val boxes = ArrayList<Box>()
		val north = floatArrayOf(0f, 0f, 16f, 16f)
		val south = floatArrayOf(16f, 0f, 32f, 16f)
		val coil = floatArrayOf(0f, 16f, 16f, 24f)
		val cap = floatArrayOf(16f, 16f, 32f, 24f)
		// The magnets: two across, two along; each north (red) face forward (+z).
		for (x in listOf(-14f to -1f, 1f to 14f)) for (z in listOf(-16f to -2f, 2f to 16f)) {
			boxes += Box(x.first, 3f, z.first, x.second, 11f, z.second, mapOf(
				Direction.SOUTH to north, Direction.NORTH to south,
				Direction.EAST to coil, Direction.WEST to coil, Direction.UP to cap, Direction.DOWN to cap,
			))
		}
		val plate = floatArrayOf(0f, 24f, 16f, 32f)
		val column = floatArrayOf(16f, 24f, 32f, 32f)
		val all = { region: FloatArray -> Direction.entries.associateWith { region } }
		boxes += Box(-15f, 11f, -17f, 15f, 14f, 17f, all(plate))
		boxes += Box(-4f, 14f, -4f, 4f, 21f, 4f, all(column))
		return boxes.flatMap { box -> Direction.entries.mapNotNull { face -> box.faces[face]?.let { quad(box, face, it) } } }
	}

	private fun quad(b: Box, face: Direction, uv: FloatArray): BakedQuad {
		val (x0, y0, z0) = Triple(b.x0 / 16f, b.y0 / 16f, b.z0 / 16f)
		val (x1, y1, z1) = Triple(b.x1 / 16f, b.y1 / 16f, b.z1 / 16f)
		// Corners counter-clockwise seen from outside, with (u, v) running across then down the face.
		val corners: List<FloatArray> = when (face) {
			Direction.SOUTH -> listOf(floatArrayOf(x0, y1, z1), floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1))
			Direction.NORTH -> listOf(floatArrayOf(x1, y1, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x0, y0, z0), floatArrayOf(x0, y1, z0))
			Direction.EAST -> listOf(floatArrayOf(x1, y1, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z0))
			Direction.WEST -> listOf(floatArrayOf(x0, y1, z0), floatArrayOf(x0, y0, z0), floatArrayOf(x0, y0, z1), floatArrayOf(x0, y1, z1))
			Direction.UP -> listOf(floatArrayOf(x0, y1, z0), floatArrayOf(x0, y1, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x1, y1, z0))
			Direction.DOWN -> listOf(floatArrayOf(x0, y0, z1), floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y0, z1))
		}
		// (East faces run from the front: the coil's red half forward there, mirrored on the west.)
		val us = if (face == Direction.WEST) listOf(uv[2], uv[2], uv[0], uv[0]) else listOf(uv[0], uv[0], uv[2], uv[2])
		val vs = listOf(uv[1], uv[3], uv[3], uv[1])
		val baker = QuadBakingVertexConsumer()
		baker.setSprite(sprite)
		baker.setDirection(face)
		baker.setShade(true)
		baker.setHasAmbientOcclusion(false)
		for (i in 0..3) {
			val c = corners[i]
			baker.addVertex(c[0], c[1], c[2])
			baker.setColor(255, 255, 255, 255)
			baker.setUv(sprite.getU(us[i] / 32f), sprite.getV(vs[i] / 32f))
			baker.setNormal(face.stepX.toFloat(), face.stepY.toFloat(), face.stepZ.toFloat())
		}
		return baker.bakeQuad()
	}

	/** The style's renderers: straight into a buffer, or as a Flywheel instance. */
	@JvmStatic
	fun sizeRenderer(): BogeyStyle.SizeRenderer = BogeyStyle.SizeRenderer(
		BogeyRenderer { _, _, _, pose, buffers, light, overlay, _ ->
			val buffer = buffers.getBuffer(RenderType.solid())
			for (quad in quads) buffer.putBulkData(pose.last(), quad, 1f, 1f, 1f, 1f, light, overlay)
		},
		BogeyVisualizer { context, _, _ ->
			val model = BakedModelBuilder(QuadsModel(quads)).build()
			Visual(context.instancerProvider().instancer(InstanceTypes.TRANSFORMED, model).createInstance())
		},
	)

	private class Visual(private val instance: TransformedInstance) : BogeyVisual {
		override fun update(data: net.minecraft.nbt.CompoundTag, wheelAngle: Float, pose: PoseStack) {
			instance.setTransform(pose).setChanged()
		}

		override fun hide() {
			instance.setZeroTransform().setChanged()
		}

		override fun updateLight(light: Int) {
			instance.light(light).setChanged()
		}

		override fun collectCrumblingInstances(consumer: java.util.function.Consumer<Instance?>) = consumer.accept(instance)

		override fun delete() = instance.delete()
	}

	/** Takes the bogey's texture once models are baked. */
	fun onModifyBakingResult(event: ModelEvent.ModifyBakingResult) {
		sprite = event.textureGetter.apply(Material(InventoryMenu.BLOCK_ATLAS, CreateRelativity.id("block/maglev_bogey")))
	}

	/** A model of given quads, to make a Flywheel model of. */
	private class QuadsModel(private val quads: List<BakedQuad>) : IDynamicBakedModel {
		override fun getQuads(state: BlockState?, side: Direction?, rand: RandomSource, data: ModelData, renderType: RenderType?): List<BakedQuad> =
			if (side == null) quads else emptyList()
		override fun useAmbientOcclusion() = false
		override fun isGui3d() = false
		override fun usesBlockLight() = true
		override fun isCustomRenderer() = false
		@Deprecated("Deprecated in Java")
		override fun getParticleIcon(): TextureAtlasSprite = sprite
		override fun getTransforms(): ItemTransforms = ItemTransforms.NO_TRANSFORMS
		override fun getOverrides(): ItemOverrides = ItemOverrides.EMPTY
		override fun getRenderTypes(state: BlockState, rand: RandomSource, data: ModelData): ChunkRenderTypeSet = ChunkRenderTypeSet.of(RenderType.solid())
	}
}
