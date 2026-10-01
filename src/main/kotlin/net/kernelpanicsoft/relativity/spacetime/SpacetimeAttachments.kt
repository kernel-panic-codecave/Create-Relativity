package net.kernelpanicsoft.relativity.spacetime

import net.kernelpanicsoft.archie.serialization.AttachmentRegistry
import net.kernelpanicsoft.relativity.CreateRelativity

object SpacetimeAttachments : AttachmentRegistry(CreateRelativity.MOD_ID) {
	/** Seconds a player's own clock has fallen behind world time (proper time vs coordinate time). Sent by [SpacetimeSync]. */
	val properTimeLag by doubleAttachment()

	/** Body stretch λ (length over rest length) from tides; 1 is unstretched. Sent by [SpacetimeSync]. */
	val bodyStretch by doubleAttachment { 1.0 }
}
