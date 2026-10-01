package net.kernelpanicsoft.relativity.mixin.client.network;

import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Ids and positions some packets keep private (needed before their entity exists on the client). */
public final class PacketEntityIdAccessors {
	private PacketEntityIdAccessors() {}

	@Mixin(ClientboundMoveEntityPacket.class)
	public interface MoveEntity {
		@Accessor("entityId")
		int create_relativity$getEntityId();
	}

	@Mixin(ClientboundRotateHeadPacket.class)
	public interface RotateHead {
		@Accessor("entityId")
		int create_relativity$getEntityId();
	}

	@Mixin(ClientboundEntityEventPacket.class)
	public interface EntityEvent {
		@Accessor("entityId")
		int create_relativity$getEntityId();
	}

	@Mixin(ClientboundSectionBlocksUpdatePacket.class)
	public interface SectionBlocks {
		@Accessor("sectionPos")
		SectionPos create_relativity$getSectionPos();
	}
}
