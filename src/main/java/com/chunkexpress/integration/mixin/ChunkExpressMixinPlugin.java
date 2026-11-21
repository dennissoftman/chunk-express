package com.chunkexpress.integration.mixin;

import com.chunkexpress.integration.ProbeRegistry;
import com.chunkexpress.integration.compatibility.CompatibilityManager;
import com.chunkexpress.integration.compatibility.CompatibilityReport;
import com.chunkexpress.integration.neoforge.InstalledMods;
import com.chunkexpress.util.Logs;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Decides, before any Minecraft class is transformed, which Chunk Express mixins (probes and
 * optimisations) to apply.
 *
 * <p>Runs during mod discovery: the FML mod list exists but no mod is constructed and the config
 * file is not loaded yet. So only two inputs count here: the installed mods (via
 * {@link CompatibilityManager}) and the {@code -Dchunkexpress.disableProbes} JVM property. The
 * config's {@code enabled} switch is applied later, at run time, by making the hooks no-ops.
 *
 * <p>Must not touch any Minecraft class: loading one here would load it before other mods'
 * mixins are registered.
 */
public final class ChunkExpressMixinPlugin implements IMixinConfigPlugin {
    private CompatibilityReport report;

    @Override
    public void onLoad(String mixinPackage) {
        String setting = System.getProperty(CompatibilityManager.DISABLE_PROBES_PROPERTY);
        this.report = CompatibilityManager.evaluate(InstalledMods.fromLoadingModList(), setting);
        ProbeRegistry.reportEvaluated(this.report);
        for (String unknown : CompatibilityManager.unknownProbeNames(setting)) {
            Logs.LOG.warn("-D{} names unknown probe '{}'; ignored.", CompatibilityManager.DISABLE_PROBES_PROPERTY, unknown);
        }
        this.report.disabledProbes().forEach((probe, reason) ->
                Logs.LOG.info("Chunk Express probe {} will not be applied: {}", probe, reason));
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String probe = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        boolean apply = this.report == null || this.report.probeAllowed(probe);
        ProbeRegistry.decided(probe, apply);
        return apply;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        ProbeRegistry.applied(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1));
    }
}
