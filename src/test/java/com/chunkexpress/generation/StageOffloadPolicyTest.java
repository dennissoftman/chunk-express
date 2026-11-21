package com.chunkexpress.generation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageOffloadPolicyTest {
    private static final String VANILLA = "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator";

    private static StageOffloadPolicy.Decision decide(boolean enabled, String generator, boolean vanilla,
                                                      List<String> types, List<String> mixins) {
        return StageOffloadPolicy.decide("carvers", "offloadCarvers", enabled, generator, vanilla, types, mixins);
    }

    @Test
    void offloadsOnlyWhenEveryGatePasses() {
        assertTrue(decide(true, VANILLA, true, List.of(), List.of()).offload());
    }

    @Test
    void configSwitchWinsAndIsNamed() {
        StageOffloadPolicy.Decision d = decide(false, VANILLA, true, List.of(), List.of());
        assertFalse(d.offload());
        assertTrue(d.reason().contains("optimizations.offloadCarvers = false"), d.reason());
    }

    @Test
    void moddedGeneratorStaysOnTheLane() {
        StageOffloadPolicy.Decision d = decide(true, "com.example.SkyGenerator", false, List.of(), List.of());
        assertFalse(d.offload());
        assertTrue(d.reason().contains("com.example.SkyGenerator"));
    }

    @Test
    void modAddedTypesStayOnTheLaneAndAreNamed() {
        StageOffloadPolicy.Decision d = decide(true, VANILLA, true, List.of("examplemod:fancy_carver"), List.of());
        assertFalse(d.offload());
        assertTrue(d.reason().contains("examplemod:fancy_carver") && d.reason().contains("carvers"), d.reason());
    }

    @Test
    void foreignMixinsStayOnTheLaneAndAreNamed() {
        StageOffloadPolicy.Decision d = decide(true, VANILLA, true, List.of(), List.of("terrain.mixins.json:CaveCarverMixin"));
        assertFalse(d.offload());
        assertTrue(d.reason().contains("terrain.mixins.json:CaveCarverMixin"));
    }

    @Test
    void stageHashOptionNamesGetTheMinecraftNamespace() {
        assertEquals(Set.of("minecraft:surface", "minecraft:carvers_out", "mod:custom"),
                StageHashes.parse(" Surface, carvers_out ,mod:custom,"));
        assertTrue(StageHashes.parse(null).isEmpty());
    }

    @Test
    void hashMixingIsOrderSensitive() {
        long ab = StageHashes.mix(StageHashes.mix(StageHashes.SEED, 1), 2);
        long ba = StageHashes.mix(StageHashes.mix(StageHashes.SEED, 2), 1);
        assertFalse(ab == ba);
    }
}
