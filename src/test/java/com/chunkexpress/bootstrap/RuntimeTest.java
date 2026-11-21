package com.chunkexpress.bootstrap;

import com.chunkexpress.config.Settings;
import com.chunkexpress.integration.ProbeRegistry;
import com.chunkexpress.integration.compatibility.CompatibilityManager;
import com.chunkexpress.integration.compatibility.KnownMods;
import com.chunkexpress.metrics.StatusReport;
import com.chunkexpress.storage.FailureLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeTest {

    private static ChunkExpressRuntime runtime(Settings settings, Map<String, String> mods) {
        return new ChunkExpressRuntime(settings, "dedicated", true, Thread.currentThread(),
                CompatibilityManager.evaluate(mods, null));
    }

    @AfterEach
    void clearCurrent() {
        ChunkExpressRuntime rt = ChunkExpressRuntime.current();
        if (rt != null) {
            ChunkExpressRuntime.uninstall(rt);
        }
    }

    @Test
    void normalAndCrashLifecyclesAreAllowedButNothingLeavesStopped() {
        assertTrue(RuntimePhase.STARTING.canMoveTo(RuntimePhase.RUNNING));
        assertTrue(RuntimePhase.RUNNING.canMoveTo(RuntimePhase.STOPPING));
        assertTrue(RuntimePhase.STOPPING.canMoveTo(RuntimePhase.STOPPED));
        assertTrue(RuntimePhase.RUNNING.canMoveTo(RuntimePhase.STOPPED), "crash: ServerStopped without Stopping");
        assertTrue(RuntimePhase.STARTING.canMoveTo(RuntimePhase.STOPPED), "failure during startup");
        assertFalse(RuntimePhase.STOPPING.canMoveTo(RuntimePhase.RUNNING));
        for (RuntimePhase next : RuntimePhase.values()) {
            assertFalse(RuntimePhase.STOPPED.canMoveTo(next));
        }
    }

    @Test
    void moveToReturnsThePreviousPhaseEvenWhenUnexpected() {
        ChunkExpressRuntime rt = runtime(Settings.defaults(), Map.of());
        assertEquals(RuntimePhase.STARTING, rt.moveTo(RuntimePhase.RUNNING));
        assertEquals(RuntimePhase.RUNNING, rt.moveTo(RuntimePhase.STOPPED));
        assertEquals(RuntimePhase.STOPPED, rt.moveTo(RuntimePhase.RUNNING), "logged, not thrown");
    }

    /** Singleplayer opens one server after another in the same JVM. */
    @Test
    void eachServerGetsAFreshRuntime() {
        ChunkExpressRuntime first = runtime(Settings.defaults(), Map.of());
        ChunkExpressRuntime.install(first);
        first.chunks().onLoad("minecraft:overworld", 1L, false, 0);
        ChunkExpressRuntime.uninstall(first);
        assertNull(ChunkExpressRuntime.current());

        ChunkExpressRuntime second = runtime(Settings.defaults(), Map.of());
        ChunkExpressRuntime.install(second);
        assertSame(second, ChunkExpressRuntime.current());
        assertTrue(second.chunks().dimensions().isEmpty(), "no state leaks between worlds");

        ChunkExpressRuntime.uninstall(first);
        assertSame(second, ChunkExpressRuntime.current(), "a stale uninstall must not remove the new runtime");
    }

    @Test
    void disabledInstrumentationHidesTheRuntimeFromHooks() {
        ChunkExpressRuntime rt = runtime(new Settings(false, true, 0, false, false, false), Map.of());
        ChunkExpressRuntime.install(rt);
        assertSame(rt, ChunkExpressRuntime.current());
        assertNull(ChunkExpressRuntime.active());
    }

    @Test
    void statusReportRendersEmptyAndBusyStates() {
        Map<String, ProbeRegistry.State> probes = new LinkedHashMap<>();
        KnownMods.ALL_MIXINS.forEach(p -> probes.put(p, ProbeRegistry.State.APPLIED));

        ChunkExpressRuntime empty = runtime(Settings.defaults(), Map.of());
        List<String> emptyLines = StatusReport.render(empty, "0.1.0", probes);
        assertTrue(emptyLines.get(0).startsWith("Chunk Express 0.1.0 | optimisations on"));
        assertTrue(emptyLines.contains("Ticks: none measured yet"));
        assertTrue(emptyLines.stream().anyMatch(l -> l.startsWith("Region writes are synchronous")));

        ChunkExpressRuntime busy = runtime(Settings.defaults(), Map.of("moonrise", "0.2"));
        busy.chunks().onLoad("minecraft:overworld", 1L, true, 0);
        busy.chunks().onLoad("minecraft:overworld", 1L, true, 0);
        busy.ticks().tickStarted(0);
        busy.ticks().tickEnded(60_000_000L);
        busy.storage().writeSubmitted("chunk", false);
        busy.storage().failures().add(new FailureLog.Failure(Instant.EPOCH, FailureLog.Kind.LOAD, "chunk",
                "minecraft:overworld", -33, 5, "IOException: bad sector"));
        List<String> lines = StatusReport.render(busy, "0.1.0", probes);
        String all = String.join("\n", lines);
        assertTrue(all.contains("Chunks [minecraft:overworld]: 1 loaded"), all);
        assertTrue(all.contains("over 50.0 ms: 1 of 1"), all);
        assertTrue(all.contains("Writes [chunk]: 1 submitted, 0 completed, 0 failed, 1 pending"), all);
        assertTrue(all.contains("Anomalies: 1 impossible chunk transitions"), all);
        assertTrue(all.contains("[-33, 5] (r.-2.0.mca): IOException: bad sector"), all);
        assertTrue(all.contains("1 incompatible, 0 conflicting"), all);
    }

    @Test
    void statusReportWarnsWhenInstrumentationIsOff() {
        ChunkExpressRuntime rt = runtime(new Settings(false, true, 0, false, false, false), Map.of());
        List<String> lines = StatusReport.render(rt, "0.1.0", Map.of());
        assertTrue(lines.get(1).startsWith("Instrumentation is disabled"));
    }
}
