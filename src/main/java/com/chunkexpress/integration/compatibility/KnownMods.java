package com.chunkexpress.integration.compatibility;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Everything Chunk Express knows about specific other mods. One entry per mod id.
 *
 * <p>Policy: nothing here is {@link CompatibilityStatus#VERIFIED} until the scenario suite in
 * docs/COMPATIBILITY.md has run against a pinned version of that mod. A unit test enforces this
 * until then, so raising a status is a deliberate, reviewed change.
 */
public final class KnownMods {
    /** Probes that read vanilla's chunk storage and load path; see {@code integration.mixin.hook.probe}. */
    public static final String CHUNK_MAP_PROBE = "ChunkMapMixin";
    public static final String IO_WORKER_PROBE = "IOWorkerMixin";
    public static final String FAILURE_PROBE = "MinecraftServerMixin";
    public static final String REGION_ENTRY_PROBE = "RegionFileMixin";
    public static final String GENERATION_PROBE = "ChunkStepMixin";
    public static final String CHUNK_WAIT_PROBE = "ServerChunkCacheMixin";

    /** Every probe mixin (observe-only). */
    public static final List<String> ALL_PROBES = List.of(CHUNK_MAP_PROBE, IO_WORKER_PROBE, FAILURE_PROBE,
            REGION_ENTRY_PROBE, GENERATION_PROBE, CHUNK_WAIT_PROBE);

    /** Mixins that change behaviour; each also has a config switch and falls back to vanilla on its own. */
    public static final String STAGE_OFFLOAD = "StageOffloadMixin";
    public static final String FLUID_SETTLING = "FluidSettlingMixin";
    public static final String FLUID_READS = "FlowingFluidReadMixin";
    public static final List<String> OPTIMIZATIONS = List.of(STAGE_OFFLOAD, FLUID_SETTLING, FLUID_READS);

    /** Every mixin we ship, for the kill switch and status output. Must match chunkexpress.mixins.json. */
    public static final List<String> ALL_MIXINS = java.util.stream.Stream.concat(ALL_PROBES.stream(), OPTIMIZATIONS.stream())
            .toList();

    /** Probes on code that chunk-system replacements bypass or rewrite. */
    private static final Set<String> STORAGE_AND_LOAD_PROBES =
            Set.of(CHUNK_MAP_PROBE, IO_WORKER_PROBE, GENERATION_PROBE, CHUNK_WAIT_PROBE, STAGE_OFFLOAD, FLUID_SETTLING, FLUID_READS);

    public static final List<KnownMod> ALL = List.of(
            // Chunk-system replacements: declared incompatible in neoforge.mods.toml, so NeoForge will
            // not start with them. Should that check ever be bypassed, the storage and load probes
            // still switch off, because these mods do not go through the vanilla methods they hook.
            new KnownMod("moonrise", "Moonrise", CompatibilityStatus.INCOMPATIBLE,
                    "Replaces the chunk system and region I/O. Declared incompatible; NeoForge should not "
                            + "have started. Storage and load probes are off.",
                    STORAGE_AND_LOAD_PROBES),
            new KnownMod("c2me", "C2ME", CompatibilityStatus.INCOMPATIBLE,
                    "Replaces chunk I/O and generation scheduling. Declared incompatible; NeoForge should not "
                            + "have started. Storage and load probes are off.",
                    STORAGE_AND_LOAD_PROBES),
            new KnownMod("c2me_base", "C2ME (modules)", CompatibilityStatus.INCOMPATIBLE,
                    "Core of C2ME's modules. Declared incompatible; NeoForge should not have started. "
                            + "Storage and load probes are off.",
                    STORAGE_AND_LOAD_PROBES),

            // Priority compatibility targets (docs/COMPATIBILITY.md). Untested so far.
            new KnownMod("create", "Create", CompatibilityStatus.UNKNOWN,
                    "Priority target. Scenario suite (factories, contraptions, chunk loaders, restarts) not run yet.",
                    Set.of()),
            // Lithium replaces ServerChunkCache.getChunk (mixin.world.chunk_access), and Mixin refuses
            // to inject into a method another mixin has replaced. The chunk-wait probe steps aside;
            // the optimisations do not touch getChunk and stay on.
            new KnownMod("lithium", "Lithium", CompatibilityStatus.CONFLICTING,
                    "Replaces the server's chunk lookup, so the chunk-wait measurement is off while it is "
                            + "installed. Optimisations are unaffected.", Set.of(CHUNK_WAIT_PROBE)),
            new KnownMod("ferritecore", "FerriteCore", CompatibilityStatus.UNKNOWN, "Priority target.", Set.of()),
            new KnownMod("farmersdelight", "Farmer's Delight", CompatibilityStatus.UNKNOWN, "Priority target.", Set.of()),
            new KnownMod("ae2", "Applied Energistics 2", CompatibilityStatus.UNKNOWN,
                    "Priority target (spatial storage and chunk loading).", Set.of()),
            new KnownMod("immersiveengineering", "Immersive Engineering", CompatibilityStatus.UNKNOWN,
                    "Priority target.", Set.of()),
            new KnownMod("terralith", "Terralith", CompatibilityStatus.UNKNOWN,
                    "Priority target. Data pack: its terrain runs in the stages O1/O2 move, through vanilla code; identity check pending (RELEASE_CRITERIA B4).", Set.of()),
            new KnownMod("tectonic", "Tectonic", CompatibilityStatus.UNKNOWN,
                    "Priority target (world generation; identity check pending, RELEASE_CRITERIA B4).", Set.of()),

            // Mods that touch the same areas and need review before Chunk Express changes behaviour.
            new KnownMod("modernfix", "ModernFix", CompatibilityStatus.UNKNOWN,
                    "Patches some chunk and world-loading paths; not reviewed yet.", Set.of()),
            new KnownMod("scalablelux", "ScalableLux", CompatibilityStatus.UNKNOWN,
                    "Replaces the light engine. Chunk Express does not touch lighting.", Set.of()),
            new KnownMod("noisium", "Noisium", CompatibilityStatus.UNKNOWN,
                    "Optimises noise generation. Chunk Express does not move the noise stage.", Set.of()),
            new KnownMod("chunky", "Chunky", CompatibilityStatus.UNKNOWN,
                    "Pre-generator; planned as the benchmark driver.", Set.of()));

    public static final Map<String, KnownMod> BY_ID =
            ALL.stream().collect(Collectors.toUnmodifiableMap(KnownMod::modId, Function.identity()));

    private KnownMods() {
    }

    /**
     * @param modId           the other mod's id as declared in its mods.toml
     * @param displayName     for messages
     * @param status          what we know
     * @param note            one actionable sentence for logs and {@code /chunkexpress compat}
     * @param disabledProbes  Chunk Express probe mixins switched off while this mod is present
     */
    public record KnownMod(String modId, String displayName, CompatibilityStatus status, String note,
                           Set<String> disabledProbes) {
    }
}
