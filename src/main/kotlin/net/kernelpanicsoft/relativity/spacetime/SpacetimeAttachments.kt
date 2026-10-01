package net.kernelpanicsoft.relativity.spacetime

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.AttachmentRegistry
import net.kernelpanicsoft.archie.serialization.attachment
import net.kernelpanicsoft.relativity.CreateRelativity

/** Level-scoped persistent state. Archie can't sync level attachments, so [RelativityNetwork] pushes them. */
object SpacetimeAttachments : AttachmentRegistry(CreateRelativity.MOD_ID) {
	val blackHoles by attachment<BlackHoles> { BlackHoles() }

	/** Seconds a player's own clock has fallen behind world time (proper time vs coordinate time). Synced for the HUD. */
	val properTimeLag by doubleAttachment(sync = true)
}

/**
 * Wrapper rather than a bare list: Archie's NBT bridge can't read back an empty top-level `ListTag`
 * (`ListTag.fromMinecraft` returns null for element type 0), and reading a level attachment persists its
 * default, so every dimension without holes failed to load. Inside a compound the empty list is just omitted.
 */
@Serializable
data class BlackHoles(val holes: List<BlackHole> = emptyList())
