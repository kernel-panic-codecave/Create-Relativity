package net.kernelpanicsoft.relativity.gametest

import net.minecraft.client.Minecraft

/**
 * Holds the client still for client GameTests: fullscreen at the display's own resolution, the mouse ignored
 * (MouseHandlerMixin), no pausing when focus is lost. So screenshots and timings repeat, whatever the user does.
 */
object ClientTestInput {
	@JvmField
	@Volatile
	var locked = false

	private var wasFullscreen = false
	private var pausedOnFocusLoss = true

	/**
	 * Whether to run the test named [name]: all of them, unless `gametest-only.txt` in the game directory (run/) lists
	 * some (one name per line; for iterating on one test without the whole suite).
	 */
	fun selected(name: String): Boolean {
		val file = java.nio.file.Path.of("gametest-only.txt")
		if (!java.nio.file.Files.exists(file)) return true
		val names = java.nio.file.Files.readAllLines(file).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
		return names.isEmpty() || name in names
	}

	/** On the client thread. */
	fun lock(minecraft: Minecraft) {
		if (locked) return
		locked = true
		wasFullscreen = minecraft.window.isFullscreen
		pausedOnFocusLoss = minecraft.options.pauseOnLostFocus
		minecraft.options.pauseOnLostFocus = false
		if (!wasFullscreen) minecraft.window.toggleFullScreen()
	}

	/** On the client thread. */
	fun unlock(minecraft: Minecraft) {
		if (!locked) return
		locked = false
		minecraft.options.pauseOnLostFocus = pausedOnFocusLoss
		if (minecraft.window.isFullscreen != wasFullscreen) minecraft.window.toggleFullScreen()
	}
}
