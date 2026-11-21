package com.chunkexpress.generation;

import java.util.List;

/**
 * Decides whether one generation stage of one dimension may leave the serial worldgen lane and run
 * on vanilla's worker pool. Pure logic over facts gathered by the caller, so every rule is
 * unit-tested. Used for O1 (surface) and O2 (carvers); see docs/OPTIMIZATIONS.md.
 *
 * <h2>Why moving these stages is safe for vanilla generation (Minecraft 1.21.1)</h2>
 * <ul>
 *   <li>{@code ChunkPyramid.GENERATION_PYRAMID} declares both with block-state write radius 0 (only
 *       their own chunk), like NOISE, which vanilla already runs on the worker pool. SURFACE has
 *       exactly NOISE's dependencies; CARVERS depends on structure starts within 8.</li>
 *   <li>Shared state they touch is immutable ({@code SurfaceSystem}, noises, registry objects such as
 *       {@code BiomeGenerationSettings}) or concurrent ({@code RandomState}'s caches are
 *       {@code ConcurrentHashMap}s). {@code SurfaceRules} has no static mutable state.</li>
 *   <li>Carvers' one write outside their chunk is {@code ChunkAccess.carverBiome}, an unsynchronised
 *       lazy cache in neighbouring chunks. Every racing writer stores the same, already-published
 *       registry object (the generation settings of the biome at that position), so the race is
 *       idempotent and cannot expose a partly built object.</li>
 *   <li>Randomness is seeded by position, so output does not depend on thread or order. Verified by
 *       stage-boundary hashes (docs/OPTIMIZATIONS.md).</li>
 *   <li>A failure takes vanilla's path for off-lane stages: the failed future reaches
 *       {@code GenerationChunkHolder.applyStep}, which records a fatal crash report.</li>
 * </ul>
 *
 * <h2>Why it is not always safe</h2>
 * Mod code can run inside these stages, and vanilla never ran that code concurrently. So a stage stays
 * on the lane (vanilla behaviour) when any of these holds:
 * <ul>
 *   <li>the dimension's generator is not exactly vanilla's {@code NoiseBasedChunkGenerator};</li>
 *   <li>the dimension's surface rule uses rule or condition types a mod added (types a mod registers
 *       but no dimension uses never run); for carvers, also any mod-registered carver type;</li>
 *   <li>another mod's mixins modify the stage's code path, unless each of them was reviewed for the
 *       installed version of that mod ({@link ReviewedPatches}).</li>
 * </ul>
 */
public final class StageOffloadPolicy {
    private StageOffloadPolicy() {
    }

    /**
     * @param stage            stage name for messages, e.g. "surface"
     * @param configKey        the config option, for messages
     * @param enabledInConfig  the option's value
     * @param generatorClass   the dimension's chunk generator class name
     * @param vanillaGenerator whether that class is exactly vanilla's noise generator
     * @param customTypes      mod-added types that would run in the stage
     * @param foreignMixins    other mods' unreviewed mixins on the stage's code path, as "config:mixin"
     */
    public static Decision decide(String stage, String configKey, boolean enabledInConfig, String generatorClass,
                                  boolean vanillaGenerator, List<String> customTypes, List<String> foreignMixins) {
        return decide(stage, configKey, enabledInConfig, generatorClass, vanillaGenerator, customTypes, foreignMixins, List.of());
    }

    /** As above; {@code reviewedMixins} are other mods' mixins on the stage's code that were reviewed as safe. */
    public static Decision decide(String stage, String configKey, boolean enabledInConfig, String generatorClass,
                                  boolean vanillaGenerator, List<String> customTypes, List<String> foreignMixins,
                                  List<String> reviewedMixins) {
        if (!enabledInConfig) {
            return new Decision(false, "disabled in config (optimizations." + configKey + " = false)");
        }
        if (!vanillaGenerator) {
            return new Decision(false, "the generator " + generatorClass + " is not vanilla's noise generator");
        }
        if (!customTypes.isEmpty()) {
            return new Decision(false, "mod-added types would run in the " + stage
                    + " stage, which vanilla never ran in parallel: " + String.join(", ", customTypes));
        }
        if (!foreignMixins.isEmpty()) {
            return new Decision(false, "other mods patch the " + stage + " code: " + String.join(", ", foreignMixins));
        }
        if (!reviewedMixins.isEmpty()) {
            return new Decision(true, "vanilla noise generator, no mod-added " + stage + " types in use, and other mods' "
                    + "patches to the " + stage + " code were reviewed as safe: " + String.join(", ", reviewedMixins));
        }
        return new Decision(true, "vanilla noise generator, no mod-added " + stage + " types in use, no other mod patches the "
                + stage + " code");
    }

    public record Decision(boolean offload, String reason) {
    }
}
