package net.kernelpanicsoft.relativity.world

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nyon.klf.MOD_BUS
import net.kernelpanicsoft.relativity.CreateRelativity
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.WorldGenRegion
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.NoiseColumn
import net.minecraft.world.level.StructureManager
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.biome.BiomeManager
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.RandomState
import net.minecraft.world.level.levelgen.blending.Blender
import net.minecraft.world.level.levelgen.structure.StructureSet
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream

/**
 * Ponderflat: an endless flat floor in a checkerboard of [light] and [dark] squares [size] blocks across (by default Create's
 * Ponder plates: white concrete and snow; the world-type screen's Customize picks them, PonderflatScreen),
 * over two layers of smooth stone and bedrock, the floor's top at y -61 as superflat's. No structures, nothing grown
 * on it: a clean stage for builds, and for the GameTests' scenes. The biome ([biomeSource]) only sets the sky and
 * what spawns: the preset's is mushroom fields, where nothing hostile spawns and mooshrooms only on mycelium, so
 * nothing at all.
 */
class PonderflatGenerator(
	biomeSource: BiomeSource,
	val light: BlockState = DEFAULT_LIGHT,
	val dark: BlockState = DEFAULT_DARK,
	val size: Int = 1,
) : ChunkGenerator(biomeSource) {
	override fun codec(): MapCodec<out ChunkGenerator> = CODEC

	/** The floor block at ([x], [z]): [light] in squares whose indices (floor(x / size), floor(z / size)) sum even, [dark] in the rest. */
	fun floor(x: Int, z: Int): BlockState = if (((Math.floorDiv(x, size) + Math.floorDiv(z, size)) and 1) == 0) light else dark

	private fun column(x: Int, z: Int): List<BlockState> =
		listOf(Blocks.BEDROCK.defaultBlockState(), Blocks.SMOOTH_STONE.defaultBlockState(), Blocks.SMOOTH_STONE.defaultBlockState(), floor(x, z))

	override fun createState(structureSets: HolderLookup<StructureSet>, randomState: RandomState, seed: Long): ChunkGeneratorStructureState =
		ChunkGeneratorStructureState.createForFlat(randomState, seed, biomeSource, Stream.empty())

	override fun fillFromNoise(blender: Blender, randomState: RandomState, structureManager: StructureManager, chunk: ChunkAccess): CompletableFuture<ChunkAccess> {
		val pos = BlockPos.MutableBlockPos()
		val ocean = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG)
		val surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG)
		val baseX = chunk.pos.minBlockX
		val baseZ = chunk.pos.minBlockZ
		for (x in 0 until 16) for (z in 0 until 16) {
			for ((index, state) in column(baseX + x, baseZ + z).withIndex()) {
				val y = chunk.minBuildHeight + index
				chunk.setBlockState(pos.set(x, y, z), state, false)
				ocean.update(x, y, z, state)
				surface.update(x, y, z, state)
			}
		}
		return CompletableFuture.completedFuture(chunk)
	}

	// (Nothing grown or carved: a bare floor.)
	override fun applyBiomeDecoration(level: WorldGenLevel, chunk: ChunkAccess, structureManager: StructureManager) = Unit

	override fun applyCarvers(level: WorldGenRegion, seed: Long, random: RandomState, biomeManager: BiomeManager, structureManager: StructureManager, chunk: ChunkAccess, step: GenerationStep.Carving) = Unit

	override fun buildSurface(level: WorldGenRegion, structureManager: StructureManager, random: RandomState, chunk: ChunkAccess) = Unit

	override fun spawnOriginalMobs(level: WorldGenRegion) = Unit

	override fun getBaseHeight(x: Int, z: Int, type: Heightmap.Types, level: LevelHeightAccessor, random: RandomState): Int = level.minBuildHeight + LAYERS

	override fun getBaseColumn(x: Int, z: Int, height: LevelHeightAccessor, random: RandomState): NoiseColumn =
		NoiseColumn(height.minBuildHeight, column(x, z).toTypedArray())

	override fun getSpawnHeight(level: LevelHeightAccessor): Int = level.minBuildHeight + LAYERS

	override fun addDebugScreenInfo(info: MutableList<String>, random: RandomState, pos: BlockPos) = Unit

	override fun getMinY(): Int = -64

	override fun getGenDepth(): Int = 384

	override fun getSeaLevel(): Int = -63

	companion object {
		/** Layers from the bottom: bedrock, two of smooth stone, the floor. */
		private const val LAYERS = 4

		val DEFAULT_LIGHT: BlockState = Blocks.WHITE_CONCRETE.defaultBlockState()
		val DEFAULT_DARK: BlockState = Blocks.SNOW_BLOCK.defaultBlockState()

		/** The largest square (blocks across). */
		const val MAX_SIZE = 64

		val CODEC: MapCodec<PonderflatGenerator> = RecordCodecBuilder.mapCodec { instance ->
			instance.group(
				BiomeSource.CODEC.fieldOf("biome_source").forGetter { it.biomeSource },
				BlockState.CODEC.optionalFieldOf("light", DEFAULT_LIGHT).forGetter { it.light },
				BlockState.CODEC.optionalFieldOf("dark", DEFAULT_DARK).forGetter { it.dark },
				Codec.intRange(1, MAX_SIZE).optionalFieldOf("size", 1).forGetter { it.size },
			).apply(instance) { source: BiomeSource, light: BlockState, dark: BlockState, size: Int -> PonderflatGenerator(source, light, dark, size) }
		}

		private val REGISTER = DeferredRegister.create(Registries.CHUNK_GENERATOR, CreateRelativity.MOD_ID)

		init {
			REGISTER.register("ponderflat") { -> CODEC }
		}

		fun register() {
			REGISTER.register(MOD_BUS)
		}
	}
}
