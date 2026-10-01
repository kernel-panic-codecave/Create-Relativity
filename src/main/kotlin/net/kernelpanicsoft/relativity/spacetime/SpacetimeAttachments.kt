package net.kernelpanicsoft.relativity.spacetime

import net.kernelpanicsoft.archie.serialization.AttachmentRegistry
import net.kernelpanicsoft.archie.serialization.attachment
import net.kernelpanicsoft.relativity.CreateRelativity

/** Level-scoped persistent state. Archie can't sync level attachments, so [RelativityNetwork] pushes them. */
object SpacetimeAttachments : AttachmentRegistry(CreateRelativity.MOD_ID) {
	val blackHoles by attachment<List<BlackHole>> { emptyList() }
}
