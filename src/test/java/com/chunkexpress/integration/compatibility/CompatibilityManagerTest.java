package com.chunkexpress.integration.compatibility;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatibilityManagerTest {

    @Test
    void vanillaInstallAllowsEveryProbe() {
        CompatibilityReport r = CompatibilityManager.evaluate(Map.of("minecraft", "1.21.1", "neoforge", "21.1.265"), null);
        assertTrue(r.detected().isEmpty());
        assertTrue(r.disabledProbes().isEmpty());
        for (String probe : KnownMods.ALL_MIXINS) {
            assertTrue(r.probeAllowed(probe));
        }
    }

    @Test
    void chunkSystemReplacementsSwitchOffStorageAndLoadProbesOnly() {
        CompatibilityReport r = CompatibilityManager.evaluate(Map.of("moonrise", "0.2.0"), null);
        assertFalse(r.probeAllowed(KnownMods.CHUNK_MAP_PROBE));
        assertFalse(r.probeAllowed(KnownMods.IO_WORKER_PROBE));
        assertTrue(r.probeAllowed(KnownMods.FAILURE_PROBE));
        assertEquals(1, r.count(CompatibilityStatus.INCOMPATIBLE));
        assertTrue(r.disabledProbes().get(KnownMods.CHUNK_MAP_PROBE).contains("Moonrise 0.2.0"));
    }

    @Test
    void reasonsFromSeveralSourcesAreCombined() {
        CompatibilityReport r = CompatibilityManager.evaluate(Map.of("moonrise", "1", "c2me", "2"), "IOWorkerMixin");
        String reason = r.disabledProbes().get(KnownMods.IO_WORKER_PROBE);
        assertTrue(reason.contains("Moonrise") && reason.contains("C2ME") && reason.contains("-Dchunkexpress"), reason);
    }

    @Test
    void untestedPriorityModsAreDetectedButChangeNothing() {
        CompatibilityReport r = CompatibilityManager.evaluate(Map.of("create", "6.0.6", "ferritecore", "7.0.3"), null);
        assertEquals(2, r.detected().size());
        assertEquals(2, r.count(CompatibilityStatus.UNKNOWN));
        assertTrue(r.disabledProbes().isEmpty());
    }

    /** Lithium replaces ServerChunkCache.getChunk: only the chunk-wait probe steps aside, nothing else. */
    @Test
    void lithiumSwitchesOffOnlyTheChunkWaitProbe() {
        CompatibilityReport r = CompatibilityManager.evaluate(Map.of("lithium", "0.15.4"), null);
        assertEquals(1, r.count(CompatibilityStatus.CONFLICTING));
        assertEquals(Set.of(KnownMods.CHUNK_WAIT_PROBE), r.disabledProbes().keySet());
        assertTrue(r.probeAllowed(KnownMods.STAGE_OFFLOAD));
    }

    @Test
    void killSwitchAcceptsAllOrANameList() {
        assertEquals(Set.copyOf(KnownMods.ALL_MIXINS), CompatibilityManager.parseDisableSetting(" ALL "));
        assertEquals(Set.of(KnownMods.IO_WORKER_PROBE, KnownMods.CHUNK_MAP_PROBE),
                CompatibilityManager.parseDisableSetting("ioworkermixin, ChunkMapMixin,nonsense"));
        assertEquals(Set.of(), CompatibilityManager.parseDisableSetting(""));
        assertEquals(List.of("nonsense"), CompatibilityManager.unknownProbeNames("IOWorkerMixin,nonsense"));
    }

    /**
     * Policy check: nothing may claim VERIFIED until the scenario suite in docs/COMPATIBILITY.md has
     * actually run. Changing this test is a deliberate, reviewed act.
     */
    @Test
    void noModIsClaimedVerifiedOrCompatibleYet() {
        for (KnownMods.KnownMod mod : KnownMods.ALL) {
            assertNotEquals(CompatibilityStatus.VERIFIED, mod.status(), mod.modId());
            assertNotEquals(CompatibilityStatus.COMPATIBLE, mod.status(), mod.modId());
        }
    }

    @Test
    void knownModTableIsConsistent() {
        Set<String> ids = new HashSet<>();
        for (KnownMods.KnownMod mod : KnownMods.ALL) {
            assertTrue(ids.add(mod.modId()), "duplicate " + mod.modId());
            assertTrue(KnownMods.ALL_MIXINS.containsAll(mod.disabledProbes()), mod.modId() + " names an unknown probe");
            boolean stepsAside = mod.status() == CompatibilityStatus.CONFLICTING
                    || mod.status() == CompatibilityStatus.INCOMPATIBLE;
            assertEquals(stepsAside, !mod.disabledProbes().isEmpty(),
                    mod.modId() + ": conflicting and incompatible mods, and only they, must switch probes off");
        }
    }
}
