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
            // Structure code (O3), reviewed 2026-11-21; docs/OPTIMIZATIONS.md has the details.
            lithostitched("ChunkGeneratorAccessor",
                    "Getters, and setters called only by BiomeInjectorManager and modifiers at server start, before any chunk generates."),
            lithostitched("JigsawStructureAccessor",
                    "Pool-alias getter, and a setter called only by Lithostitched's world-generation modifiers, applied at server start before any chunk generates."),
            lithostitched("ListPoolElementAccessor",
                    "Getters only."),
            lithostitched("SinglePoolElementAccessor",
                    "Getters, and a processors setter called only by Lithostitched's world-generation modifiers, applied at server start before any chunk generates."),
            lithostitched("StructureProcessorListAccessor",
                    "Processors setter called only by Lithostitched's world-generation modifiers, applied at server start before any chunk generates."),
            lithostitched("StructureSetAccessor",
                    "Structures setter called only by Lithostitched's world-generation modifiers, applied at server start before any chunk generates."),
            lithostitched("StructureTemplatePoolAccessor",
                    "Getters, and template-list setters called only by AddTemplatePoolElementsModifier at server start."),
            lithostitched("JigsawStructureMixin",
                    "findGenerationPoint (runs during structure starts): routes to AlternateJigsawStructure.generate when its alternate config is used; only static constants."),
            lithostitched("StructureTemplatePoolMixin",
                    "Template choice (runs during structure starts): each pool's LithostitchedTemplates is filled at server start; shuffle(random) copies the entries (WeightedEntry::copy) and sorts only the copies."),
            lithostitched("PoolAliasLookupMixin",
                    "create (runs during structure starts): no field writes, no helper calls."),
            lithostitched("template.NetherFossilStructureMixin",
                    "findGenerationPoint (runs during structure starts): picks a template id with TemplateLists.getRandom(registryAccess, key, random); registry read, caller's random, static constants only."),
            lithostitched("template.RuinedPortalStructureMixin",
                    "findGenerationPoint (runs during structure starts): as NetherFossilStructureMixin."),
            lithostitched("template.ShipwreckStructureMixin",
                    "generatePieces (runs during structure starts): as NetherFossilStructureMixin."),
            lithostitched("template.mansion.FloorRoomCollectionMixin",
                    "Adds a registries field to the per-mansion room collections vanilla creates for each mansion."),
            lithostitched("template.mansion.PiecePlacerMixin",
                    "createMansion (runs during structure starts): writes registries into the per-mansion MansionPiecePlacer."),
            lithostitched("template.mansion.WoodlandMansionPieceMixin",
                    "makeLocation (runs during structure starts): reads registries, no shared writes."),
            lithostitched("template.mansion.WoodlandMansionStructureMixin",
                    "generatePieces (runs during structure starts): hands registries to the per-mansion placer."),
            lithostitched("template.mansion.floor.FirstFloorMixin",
                    "Room pickers (runs during structure starts): read the per-mansion registries and the static breaksSeedParity flag."),
            lithostitched("template.mansion.floor.SecondFloorMixin",
                    "As FirstFloorMixin."),
            lithostitched("template.mansion.floor.ThirdFloorMixin",
                    "As FirstFloorMixin."),
            lithostitched("processor.EndCityPieceMixin",
                    "makeSettings (piece constructors, runs during structure starts) builds the piece's own StructurePlaceSettings with a new UnboundReferenceProcessor; its other handlers runs only when the structure is placed into the world (features stage), which O3 does not move."),
            lithostitched("processor.IglooPieceMixin",
                    "makeSettings (piece constructors, runs during structure starts) builds the piece's own StructurePlaceSettings with a new UnboundReferenceProcessor; its other handlers runs only when the structure is placed into the world (features stage), which O3 does not move."),
            lithostitched("processor.NetherFossilPieceMixin",
                    "makeSettings (piece constructors, runs during structure starts) builds the piece's own StructurePlaceSettings with a new UnboundReferenceProcessor; its other handlers runs only when the structure is placed into the world (features stage), which O3 does not move."),
            lithostitched("processor.OceanRuinPieceMixin",
                    "makeSettings (piece constructors, runs during structure starts) builds the piece's own StructurePlaceSettings with a new UnboundReferenceProcessor; its other handlers runs only when the structure is placed into the world (features stage), which O3 does not move."),
            lithostitched("processor.RuinedPortalPieceMixin",
                    "makeSettings (piece constructors, runs during structure starts) builds the piece's own StructurePlaceSettings with a new UnboundReferenceProcessor; its other handlers runs only when the structure is placed into the world (features stage), which O3 does not move."),
            lithostitched("processor.ShipwreckPieceMixin",
                    "makeSettings (piece constructors, runs during structure starts) builds the piece's own StructurePlaceSettings with a new UnboundReferenceProcessor; its other handlers runs only when the structure is placed into the world (features stage), which O3 does not move."),
            lithostitched("processor.WoodlandMansionPieceMixin",
                    "makeSettings (piece constructors, runs during structure starts) builds the piece's own StructurePlaceSettings with a new UnboundReferenceProcessor; its other handlers runs only when the structure is placed into the world (features stage), which O3 does not move."),
            lithostitched("processor.DesertPyramidStructureMixin",
                    "Its handlers (postProcess, placeInChunk, afterPlace, placeBlock, createChest, ...) runs only when the structure is placed into the world (features stage), which O3 does not move."),
            lithostitched("processor.MineshaftCorridorMixin",
                    "Its handlers (postProcess, placeInChunk, afterPlace, placeBlock, createChest, ...) runs only when the structure is placed into the world (features stage), which O3 does not move."),
            lithostitched("processor.MineshaftPieceMixin",
                    "Its handlers (postProcess, placeInChunk, afterPlace, placeBlock, createChest, ...) runs only when the structure is placed into the world (features stage), which O3 does not move."),
            lithostitched("processor.MonsterThroneMixin",
                    "Its handlers (postProcess, placeInChunk, afterPlace, placeBlock, createChest, ...) runs only when the structure is placed into the world (features stage), which O3 does not move."),
            lithostitched("processor.PortalRoomMixin",
                    "Its handlers (postProcess, placeInChunk, afterPlace, placeBlock, createChest, ...) runs only when the structure is placed into the world (features stage), which O3 does not move."),
            lithostitched("processor.StructurePieceMixin",
                    "Its handlers (postProcess, placeInChunk, afterPlace, placeBlock, createChest, ...) runs only when the structure is placed into the world (features stage), which O3 does not move."),
            lithostitched("processor.StructureStartMixin",
                    "Its handlers (postProcess, placeInChunk, afterPlace, placeBlock, createChest, ...) runs only when the structure is placed into the world (features stage), which O3 does not move."),
            lithostitched("processor.WoodlandMansionStructureMixin",
                    "Its handlers (postProcess, placeInChunk, afterPlace, placeBlock, createChest, ...) runs only when the structure is placed into the world (features stage), which O3 does not move."),
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

    /** A mod-added type (by registry id) that runs in a moved stage and was reviewed as safe. */
    public record ReviewedType(String modId, String version, String typeId, String finding) {
    }

    public static final List<ReviewedType> TYPES = List.of(
            new ReviewedType(LITHOSTITCHED, LITHOSTITCHED_REVIEWED, "lithostitched:internal/random_entries",
                    "Pool-alias binding (structure starts): an immutable record that picks pools with the caller's random source."));

    /** Mod-added types split into those reviewed for the installed version, and the rest (with why). */
    public static Split splitTypes(List<String> types, Map<String, String> modVersions) {
        List<String> reviewed = new ArrayList<>();
        List<String> unreviewed = new ArrayList<>();
        for (String type : types) {
            ReviewedType known = TYPES.stream().filter(t -> t.typeId().equals(type)).findFirst().orElse(null);
            if (known == null) {
                unreviewed.add(type);
            } else if (known.version().equals(modVersions.get(known.modId()))) {
                reviewed.add(type);
            } else {
                unreviewed.add(type + " (reviewed for " + known.modId() + " " + known.version() + ", installed: "
                        + modVersions.getOrDefault(known.modId(), "none") + ")");
            }
        }
        return new Split(List.copyOf(reviewed), List.copyOf(unreviewed));
    }

    /** Create and its bundled Flywheel, for O4 (fluid settling). Reviewed 2026-11-21. */
    private static final List<Patch> CREATE_PATCHES = List.of(
            new Patch("create", "6.0.10", "create.mixins.json", "com.simibubi.create.foundation.mixin.WaterWheelFluidSpreadMixin",
                    "At the start of FlowingFluid.canPassThrough, lets water pass water-wheel structure blocks: reads blocks "
                            + "through the level (exact under O4), writes nothing."),
            new Patch("create", "6.0.10", "create.mixins.json", "com.simibubi.create.foundation.mixin.accessor.FlowingFluidAccessor",
                    "Invoker for FlowingFluid.getNewLiquid: calls vanilla's method, whose reads are exact under O4."),
            new Patch("flywheel", "1.0.6", "flywheel.impl.mixins.json", "dev.engine_room.flywheel.impl.mixin.visualmanage.LevelChunkMixin",
                    "Queues block-entity visuals in LevelChunk.setBlockEntity (rendering); no block or fluid reads."));

    /** Every reviewed patch. */
    public static List<Patch> all() {
        List<Patch> all = new ArrayList<>(ALL);
        all.addAll(MODERNFIX_PATCHES);
        all.addAll(CREATE_PATCHES);
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
