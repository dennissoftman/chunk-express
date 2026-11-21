package com.chunkexpress.generation;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewedPatchesTest {
    private static final String BANDS = "dev.worldgen.lithostitched.mixin.common.bandlands.NoiseBasedChunkGeneratorMixin";
    private static final String OTHER = "com.example.SurfaceSystemMixin";

    @Test
    void reviewedMixinPassesOnlyForTheReviewedVersion() {
        ReviewedPatches.Split ok = ReviewedPatches.split(List.of(BANDS), Map.of("lithostitched", "1.8.1"));
        assertEquals(List.of("lithostitched 1.8.1: NoiseBasedChunkGeneratorMixin"), ok.reviewed());
        assertTrue(ok.unreviewed().isEmpty());

        ReviewedPatches.Split newer = ReviewedPatches.split(List.of(BANDS), Map.of("lithostitched", "1.8.2"));
        assertTrue(newer.reviewed().isEmpty());
        assertTrue(newer.unreviewed().get(0).contains("reviewed for lithostitched 1.8.1, installed: 1.8.2"), newer.unreviewed().get(0));
    }

    @Test
    void modernFixIsReviewedForItsExactVersion() {
        String surface = "org.embeddedt.modernfix.common.mixin.perf.optimize_surface_rules.SurfaceSystemMixin";
        assertEquals(1, ReviewedPatches.split(List.of(surface), Map.of("modernfix", "5.27.27+mc1.21.1")).reviewed().size());
        assertEquals(1, ReviewedPatches.split(List.of(surface), Map.of("modernfix", "5.27.28+mc1.21.1")).unreviewed().size());
    }

    @Test
    void unknownMixinsStayUnreviewed() {
        ReviewedPatches.Split s = ReviewedPatches.split(List.of(BANDS, OTHER), Map.of("lithostitched", "1.8.1"));
        assertEquals(List.of(OTHER), s.unreviewed());
        assertEquals(1, s.reviewed().size());
    }

    @Test
    void everyEntryIsComplete() {
        Set<String> keys = new HashSet<>();
        for (ReviewedPatches.Patch p : ReviewedPatches.all()) {
            assertTrue(keys.add(p.key()), "duplicate " + p.key());
            assertFalse(p.version().isBlank() || p.finding().isBlank() || p.modId().isBlank(), p.toString());
        }
    }

    @Test
    void policyNamesReviewedPatchesWhenItOffloads() {
        StageOffloadPolicy.Decision d = StageOffloadPolicy.decide("surface", "offloadSurface", true,
                "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator", true, List.of(), List.of(),
                List.of("lithostitched 1.8.1: NoiseBasedChunkGeneratorMixin"));
        assertTrue(d.offload());
        assertTrue(d.reason().contains("reviewed as safe: lithostitched 1.8.1"), d.reason());

        StageOffloadPolicy.Decision blocked = StageOffloadPolicy.decide("surface", "offloadSurface", true,
                "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator", true, List.of(), List.of(OTHER),
                List.of("lithostitched 1.8.1: NoiseBasedChunkGeneratorMixin"));
        assertFalse(blocked.offload(), "one unreviewed patch keeps the stage vanilla");
    }

    @Test
    void ruleTypesAreReadFromTheEncodedTree() {
        String json = """
                {"type":"minecraft:sequence","sequence":[
                  {"type":"minecraft:condition","if_true":{"type":"minecraft:stone_depth","surface_type":"floor"},
                   "then_run":{"type":"minecraft:block","result_state":{"Name":"minecraft:grass_block"}}},
                  {"type":"minecraft:condition","if_true":{"type":"lithostitched:slope","min":1},
                   "then_run":{"type":"minecraft:bandlands"}}]}""";
        Set<String> types = RuleTypes.collect(JsonParser.parseString(json));
        assertEquals(Set.of("minecraft:sequence", "minecraft:condition", "minecraft:stone_depth", "minecraft:block",
                "lithostitched:slope", "minecraft:bandlands"), types);
        assertEquals(Set.of("lithostitched:slope"), RuleTypes.nonVanilla(types));
        assertEquals(Set.of(), RuleTypes.nonVanilla(Set.of("minecraft:block", "bandlands")));
    }
}
