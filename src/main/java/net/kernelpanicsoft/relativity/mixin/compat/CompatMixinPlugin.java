package net.kernelpanicsoft.relativity.mixin.compat;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Applies the compatibility mixins (package compat.<mod id>) only when that mod is loaded. */
public class CompatMixinPlugin implements IMixinConfigPlugin {
	private static final String PACKAGE = "net.kernelpanicsoft.relativity.mixin.compat.";

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		String rest = mixinClassName.substring(PACKAGE.length());
		String modId = rest.substring(0, rest.indexOf('.'));
		return LoadingModList.get().getModFileById(modId) != null;
	}

	@Override public void onLoad(String mixinPackage) {}
	@Override public String getRefMapperConfig() { return null; }
	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
