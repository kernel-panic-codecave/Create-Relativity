package net.kernelpanicsoft.relativity.spacetime

import net.kernelpanicsoft.archie.serialization.AttachmentRegistry
import net.kernelpanicsoft.relativity.CreateRelativity

object SpacetimeAttachments : AttachmentRegistry(CreateRelativity.MOD_ID) {
	/** Seconds a player's own clock has fallen behind world time (proper time vs coordinate time). Synced for the HUD. */
	val properTimeLag by doubleAttachment(sync = true)
}
