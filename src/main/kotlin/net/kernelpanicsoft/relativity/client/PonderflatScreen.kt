package net.kernelpanicsoft.relativity.client

import net.kernelpanicsoft.relativity.CreateRelativity
import net.kernelpanicsoft.relativity.world.PonderflatGenerator
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen
import net.minecraft.client.gui.screens.worldselection.PresetEditor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.presets.WorldPreset
import net.neoforged.neoforge.client.event.RegisterPresetEditorsEvent
import kotlin.math.roundToInt

/**
 * Ponderflat's Customize screen (Create World, World Type), laid out as superflat's (CreateFlatWorldScreen): the
 * floor's two squares as a list, each block in its slot with its name, the squares' size, and Swap and Presets below. The blocks are
 * picked the way superflat's layers are, through the presets screen's code ([PonderflatPresetsScreen]). Done puts them
 * into the world's generator.
 */
class PonderflatScreen(
	private val parent: CreateWorldScreen,
	private val generator: PonderflatGenerator,
) : Screen(Component.translatable("createWorld.customize.create_relativity.ponderflat.title")) {
	/** The squares' blocks as edited: light, then dark. */
	var light: Block = generator.light.block
	var dark: Block = generator.dark.block
	var size: Int = generator.size

	private lateinit var list: SquaresList

	override fun init() {
		list = addRenderableWidget(SquaresList())
		addRenderableWidget(SizeSlider(width / 2 - 155, height - 76, 310))
		addRenderableWidget(Button.builder(Component.translatable("createWorld.customize.create_relativity.ponderflat.swap")) {
			val was = light
			light = dark
			dark = was
		}.bounds(width / 2 - 155, height - 52, 150, 20).build())
		addRenderableWidget(Button.builder(Component.translatable("createWorld.customize.presets")) {
			minecraft?.setScreen(PonderflatPresetsScreen(this))
		}.bounds(width / 2 + 5, height - 52, 150, 20).build())
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE) {
			val light = light.defaultBlockState()
			val dark = dark.defaultBlockState()
			parent.uiState.updateDimensions { registries, dimensions ->
				dimensions.replaceOverworldGenerator(registries, PonderflatGenerator(generator.biomeSource, light, dark, size))
			}
			minecraft?.setScreen(parent)
		}.bounds(width / 2 - 155, height - 28, 150, 20).build())
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL) { onClose() }.bounds(width / 2 + 5, height - 28, 150, 20).build())
	}

	override fun onClose() {
		minecraft?.setScreen(parent)
	}

	override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
		super.render(graphics, mouseX, mouseY, partialTick)
		graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF)
		val left = width / 2 - 92 - 16
		val block = Component.translatable("createWorld.customize.flat.tile")
		val squares = Component.translatable("createWorld.customize.create_relativity.ponderflat.squares")
		graphics.drawString(font, block, left, 32, 0xFFFFFF)
		graphics.drawString(font, squares, left + 2 + 213 - font.width(squares), 32, 0xFFFFFF)
	}

	/** The two squares, light then dark (superflat's layer list). */
	/** The squares' size, 1 to PonderflatGenerator.MAX_SIZE blocks across. */
	private inner class SizeSlider(x: Int, y: Int, width: Int) : net.minecraft.client.gui.components.AbstractSliderButton(
		x, y, width, 20, Component.empty(), (size - 1.0) / (PonderflatGenerator.MAX_SIZE - 1),
	) {
		init {
			updateMessage()
		}

		override fun updateMessage() {
			message = Component.translatable("createWorld.customize.create_relativity.ponderflat.size", size)
		}

		override fun applyValue() {
			size = 1 + (value * (PonderflatGenerator.MAX_SIZE - 1)).roundToInt()
		}
	}

	private inner class SquaresList : ObjectSelectionList<SquaresList.Entry>(minecraft!!, width, height - 127, 43, 24) {
		init {
			addEntry(Entry(true))
			addEntry(Entry(false))
		}

		inner class Entry(private val isLight: Boolean) : ObjectSelectionList.Entry<Entry>() {
			private val label get() = Component.translatable("createWorld.customize.create_relativity.ponderflat.${if (isLight) "light" else "dark"}")
			private val block get() = if (isLight) light else dark

			override fun render(graphics: GuiGraphics, index: Int, top: Int, left: Int, width: Int, height: Int, mouseX: Int, mouseY: Int, hovering: Boolean, partialTick: Float) {
				val stack = ItemStack(block)
				slot(graphics, left, top, stack)
				graphics.drawString(font, stack.hoverName, left + 18 + 5, top + 3, 0xFFFFFF, false)
				graphics.drawString(font, label, left + 2 + 213 - font.width(label), top + 3, 0xFFFFFF, false)
			}

			override fun getNarration(): Component = Component.translatable("narrator.select", ItemStack(block).hoverName)

			override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
				selected = this
				return super.mouseClicked(mouseX, mouseY, button)
			}
		}
	}

	companion object {
		private val SLOT_SPRITE: ResourceLocation = ResourceLocation.withDefaultNamespace("container/slot")

		/** An item in an inventory slot, as superflat's screens draw their blocks. */
		fun slot(graphics: GuiGraphics, x: Int, y: Int, stack: ItemStack) {
			graphics.blitSprite(SLOT_SPRITE, x + 1, y + 1, 0, 18, 18)
			if (!stack.isEmpty) graphics.renderFakeItem(stack, x + 2, y + 2)
		}

		private val PRESET: ResourceKey<WorldPreset> =
			ResourceKey.create(Registries.WORLD_PRESET, ResourceLocation.fromNamespaceAndPath(CreateRelativity.MOD_ID, "ponderflat"))

		fun onRegisterEditors(event: RegisterPresetEditorsEvent) {
			event.register(PRESET) { parent, context ->
				val current = context.selectedDimensions().overworld() as? PonderflatGenerator
					?: PonderflatGenerator(context.selectedDimensions().overworld().biomeSource)
				PonderflatScreen(parent, current)
			}
		}
	}
}

/**
 * Ponderflat's presets (superflat's PresetFlatWorldScreen): the floor as a code to share or edit
 * (`light_block;dark_block;size`, block ids and the squares' size, 1 if left off) and a list of presets, Ponder
 * (Create's plates) and Contrast (white and blue concrete in 2-block squares: the lens tests' floor). Use Preset takes
 * the code.
 */
class PonderflatPresetsScreen(private val parent: PonderflatScreen) : Screen(Component.translatable("createWorld.customize.presets.title")) {
	private lateinit var code: EditBox
	private lateinit var use: Button

	private class Preset(val name: String, val light: Block, val dark: Block, val size: Int)

	private val presets = listOf(
		Preset("ponder", Blocks.WHITE_CONCRETE, Blocks.SNOW_BLOCK, 1),
		Preset("contrast", Blocks.WHITE_CONCRETE, Blocks.BLUE_CONCRETE, 2),
	)

	override fun init() {
		code = EditBox(font, 50, 40, width - 100, 20, Component.translatable("createWorld.customize.presets.share"))
		code.setMaxLength(256)
		code.value = save(parent.light, parent.dark, parent.size)
		code.setResponder { updateValidity() }
		addWidget(code)
		addRenderableWidget(PresetsList())
		use = addRenderableWidget(Button.builder(Component.translatable("createWorld.customize.presets.select")) {
			parse(code.value)?.let { (blocks, size) ->
				parent.light = blocks.first
				parent.dark = blocks.second
				parent.size = size
			}
			minecraft?.setScreen(parent)
		}.bounds(width / 2 - 155, height - 28, 150, 20).build())
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL) { onClose() }.bounds(width / 2 + 5, height - 28, 150, 20).build())
		updateValidity()
	}

	private fun updateValidity() {
		val valid = parse(code.value) != null
		use.active = valid
		code.setTextColor(if (valid) 0xE0E0E0 else 0xFF5555)
	}

	override fun onClose() {
		minecraft?.setScreen(parent)
	}

	override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
		super.render(graphics, mouseX, mouseY, partialTick)
		graphics.pose().pushPose()
		graphics.pose().translate(0f, 0f, 400f)
		graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF)
		graphics.drawString(font, Component.translatable("createWorld.customize.presets.share"), 51, 30, 0xA0A0A0)
		graphics.drawString(font, Component.translatable("createWorld.customize.presets.list"), 51, 68, 0xA0A0A0)
		graphics.pose().popPose()
		code.render(graphics, mouseX, mouseY, partialTick)
	}

	private inner class PresetsList : ObjectSelectionList<PresetsList.Entry>(minecraft!!, width, height - 117, 80, 24) {
		init {
			for (preset in presets) addEntry(Entry(preset))
		}

		inner class Entry(private val preset: Preset) : ObjectSelectionList.Entry<Entry>() {
			private val name = Component.translatable("createWorld.customize.create_relativity.ponderflat.preset.${preset.name}")

			override fun render(graphics: GuiGraphics, index: Int, top: Int, left: Int, width: Int, height: Int, mouseX: Int, mouseY: Int, hovering: Boolean, partialTick: Float) {
				PonderflatScreen.slot(graphics, left, top, ItemStack(preset.dark))
				graphics.drawString(font, name, left + 18 + 5, top + 6, 0xFFFFFF, false)
			}

			override fun getNarration(): Component = Component.translatable("narrator.select", name)

			override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
				selected = this
				code.value = save(preset.light, preset.dark, preset.size)
				code.moveCursorToStart(false)
				return super.mouseClicked(mouseX, mouseY, button)
			}
		}
	}

	companion object {
		fun save(light: Block, dark: Block, size: Int): String =
			"${BuiltInRegistries.BLOCK.getKey(light)};${BuiltInRegistries.BLOCK.getKey(dark)}" + if (size != 1) ";$size" else ""

		/** `light;dark[;size]` as two blocks (not air) and a size (1 if left off), or null. */
		fun parse(code: String): Pair<Pair<Block, Block>, Int>? {
			val parts = code.split(';')
			if (parts.size !in 2..3) return null
			val blocks = parts.take(2).map { part ->
				val location = ResourceLocation.tryParse(part.trim()) ?: return null
				BuiltInRegistries.BLOCK.getOptional(location).orElse(null)?.takeIf { it != Blocks.AIR } ?: return null
			}
			val size = if (parts.size == 3) parts[2].trim().toIntOrNull()?.takeIf { it in 1..PonderflatGenerator.MAX_SIZE } ?: return null else 1
			return (blocks[0] to blocks[1]) to size
		}
	}
}
