package com.chunkexpress.generation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Other mods' mixins on the code a stage offload moves, which we have read and found safe to run
 * concurrently. Each entry is pinned to the exact mod version that was reviewed: any other version
 * counts as unreviewed, so the stage stays on the worldgen lane until someone reviews it.
 *
 * <p>Adding an entry is a deliberate act: read the mixin (and whatever it calls) for shared mutable
 * state, write the finding here, then run the stage-hash comparison with that mod installed
 * (docs/OPTIMIZATIONS.md) before shipping it.
 */
public final class ReviewedPatches {
    /**
     * @param modId       the mod that ships the mixin
     * @param version     the exact version reviewed
     * @param mixinConfig its mixin config file name
     * @param mixinClass  the mixin's fully qualified class name
     * @param finding     why it is safe, in one sentence
     */
    public record Patch(String modId, String version, String mixinConfig, String mixinClass, String finding) {
        String key() {
            return mixinClass;
        }
    }

    private static final String LITHOSTITCHED = "lithostitched";
    private static final String LITHOSTITCHED_REVIEWED = "1.8.1";
    private static final String LITHOSTITCHED_CONFIG = "lithostitched.mixins.json";
    private static final String LITHOSTITCHED_MIXINS = "dev.worldgen.lithostitched.mixin.common.";

    /** Lithostitched is the library Terralith and many worldgen data packs depend on. Reviewed 2026-11-20. */
    public static final List<Patch> ALL = List.of(
            lithostitched("SurfaceRulesContextMixin",
                    "Shadows SurfaceRules.Context fields and adds getters and a biome condition: reads only, and the context belongs to one chunk."),
            lithostitched("bandlands.SurfaceSystemMixin",
                    "Shadows two final SurfaceSystem fields and adds getters: reads only."),
            lithostitched("bandlands.NoiseBasedChunkGeneratorMixin",
                    "At the start of buildSurface, lazily fills each Bandlands registry entry's band array once: the array "
                            + "is built in full in a local variable from a fixed seed and only then stored, so concurrent "
                            + "callers store identical, complete arrays (the store is published to workers by the stage "
                            + "warm-up, see StageOffload)."),
            lithostitched("NoiseBasedChunkGeneratorMixin",
                    "Wraps a biome lookup in spawnOriginalMobs, which is not part of the surface or carvers stage."),
            lithostitched("NoiseBasedChunkGeneratorAccessor",
                    "Setter for NoiseBasedChunkGenerator.settings, called only by RereferenceNoiseSettingsModifier.apply(RegistryAccess) "
                            + "while worldgen modifiers are applied at server start, before any chunk generates."),
            lithostitched("compat.terrablender.NoiseBasedChunkGeneratorMixin",
                    "Wraps biome resolution in doCreateBiomes, the biomes stage, which vanilla already runs on worker threads."));

    private static final String MODERNFIX = "modernfix";
    private static final String MODERNFIX_REVIEWED = "5.27.27+mc1.21.1";
    private static final String MODERNFIX_CONFIG = "modernfix-modernfix.mixins.json";
    private static final String MODERNFIX_MIXINS = "org.embeddedt.modernfix.common.mixin.";

    /** ModernFix's surface-rule and allocation optimisations, and a FULL-stage fix. Reviewed 2026-11-21. */
    private static final List<Patch> MODERNFIX_PATCHES = List.of(
            modernfix("bugfix.chunk_deadlock.ChunkMapLoadMixin",
                    "Patches ChunkStatusTasks.full (the FULL stage, on the server thread), not the surface or carvers stage."),
            modernfix("perf.optimize_surface_rules.NoiseBasedChunkGeneratorMixin",
                    "Reads the biomes of nearby chunk sections and hands the set to the inner buildSurface call through a "
                            + "ThreadLocal it removes afterwards; the whole call runs on one thread."),
            modernfix("perf.optimize_surface_rules.SurfaceRulesContextMixin",
                    "Copies that ThreadLocal set into a new field of the per-chunk SurfaceRules.Context."),
            modernfix("perf.optimize_surface_rules.SurfaceSystemMixin",
                    "Faster biome lookup and block column for buildSurface, held in ThreadLocals and disposed at the end of the call."),
            modernfix("perf.optimize_surface_rules.BiomeConditionSourceMixin",
                    "Returns a constant condition when the context's possible biomes decide it; reads the immutable biome "
                            + "list and the per-chunk context only."),
            modernfix("perf.worldgen_allocation.SequenceRuleMixin",
                    "Replaces SequenceRule.tryApply's iterator with an index loop over the same immutable rule list: reads only."),
            modernfix("perf.worldgen_allocation.SurfaceRulesContextMixin",
                    "Replaces Context.updateY with a version that reuses a position and supplier owned by the per-chunk Context."));

    private ReviewedPatches() {
    }

    private static Patch modernfix(String mixin, String finding) {
        return new Patch(MODERNFIX, MODERNFIX_REVIEWED, MODERNFIX_CONFIG, MODERNFIX_MIXINS + mixin, finding);
    }

    private static Patch lithostitched(String mixin, String finding) {
        return new Patch(LITHOSTITCHED, LITHOSTITCHED_REVIEWED, LITHOSTITCHED_CONFIG, LITHOSTITCHED_MIXINS + mixin, finding);
    }

    /** Every reviewed patch. */
    public static List<Patch> all() {
        List<Patch> all = new ArrayList<>(ALL);
        all.addAll(MODERNFIX_PATCHES);
        return List.copyOf(all);
    }

    /** Foreign mixins split into those reviewed for the installed version, and the rest (with why). */
    public record Split(List<String> reviewed, List<String> unreviewed) {
    }

    /**
     * @param foreignMixins other mods' mixins on a stage's code, as fully qualified mixin class names
     * @param modVersions   installed mods: id to version
     */
    public static Split split(List<String> foreignMixins, Map<String, String> modVersions) {
        List<String> reviewed = new ArrayList<>();
        List<String> unreviewed = new ArrayList<>();
        for (String mixin : foreignMixins) {
            Patch patch = all().stream().filter(p -> p.key().equals(mixin)).findFirst().orElse(null);
            if (patch == null) {
                unreviewed.add(mixin);
                continue;
            }
            String installed = modVersions.get(patch.modId());
            if (patch.version().equals(installed)) {
                reviewed.add(patch.modId() + " " + installed + ": " + simpleName(patch.mixinClass()));
            } else {
                unreviewed.add(mixin + " (reviewed for " + patch.modId() + " " + patch.version() + ", installed: "
                        + (installed == null ? "none" : installed) + ")");
            }
        }
        return new Split(List.copyOf(reviewed), List.copyOf(unreviewed));
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }
}
