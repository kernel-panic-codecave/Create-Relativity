package net.kernelpanicsoft.relativity.mixin.create;

import com.simibubi.create.content.trains.entity.Navigation;
import com.simibubi.create.content.trains.graph.TrackNode;
import net.createmod.catnip.data.Couple;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(value = Navigation.class, remap = false)
public interface NavigationAccessor {
	/** The route ahead: each edge as the nodes it runs between, in the order travelled. */
	@Accessor("currentPath")
	List<Couple<TrackNode>> create_relativity$getCurrentPath();
}
