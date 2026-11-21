package com.chunkexpress.metrics;

import com.chunkexpress.bootstrap.ChunkExpressRuntime;
import com.chunkexpress.chunk.ChunkLifecycleTracker.DimensionView;
import com.chunkexpress.chunk.ChunkWaitMonitor;
import com.chunkexpress.generation.GenerationMonitor;
import com.chunkexpress.integration.ProbeRegistry;
import com.chunkexpress.integration.compatibility.CompatibilityReport;
import com.chunkexpress.integration.compatibility.CompatibilityStatus;
import com.chunkexpress.storage.FailureLog;
import com.chunkexpress.storage.StorageMonitor;
import com.chunkexpress.util.ProbeGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Renders {@code /chunkexpress status} and the shutdown summary as plain lines. Kept free of
 * Minecraft types so the exact output is unit-tested.
 *
 * <p>Thread safety: call on the server thread (it reads server-thread-confined monitors).
 */
public final class StatusReport {
    static final int RECENT_FAILURES_SHOWN = 5;

    private StatusReport() {
    }

    public static List<String> render(ChunkExpressRuntime rt, String version, Map<String, ProbeRegistry.State> probes) {
        return render(rt, version, probes, List.of());
    }

    /**
     * @param optimizationLines one line per optimisation and dimension, saying whether it is active
     *                          and why; produced by the game-side code that makes the decisions
     */
    public static List<String> render(ChunkExpressRuntime rt, String version, Map<String, ProbeRegistry.State> probes,
                                      List<String> optimizationLines) {
        List<String> lines = new ArrayList<>();
        lines.add("Chunk Express " + version + " | " + (rt.settings().anyOptimisation() ? "optimisations on" : "observe-only")
                + " | "
                + rt.serverKind() + " server | " + rt.phase() + " | up " + Format.duration(rt.uptime())
                + ", counters cover " + Format.duration(rt.sinceCountersReset()));
        if (!rt.settings().instrumentation()) {
            lines.add("Instrumentation is disabled in config/chunkexpress-common.toml; numbers below are not updating.");
        }

        TickMonitor.Snapshot t = rt.ticks().snapshot();
        if (t.work().count() == 0) {
            lines.add("Ticks: none measured yet");
        } else {
            TickMonitor.Window w = t.work();
            lines.add("Tick work (last " + w.count() + "): avg " + Format.nanos(w.mean()) + ", p95 " + Format.nanos(w.p95())
                    + ", p99 " + Format.nanos(w.p99()) + ", max " + Format.nanos(w.max())
                    + " | over " + Format.nanos(TickMonitor.BUDGET_NANOS) + ": " + t.overBudgetTicks() + " of " + t.totalTicks());
            TickMonitor.Window i = t.interval();
            lines.add("Tick interval, start to start (includes server-thread work between ticks): avg " + Format.nanos(i.mean())
                    + ", p95 " + Format.nanos(i.p95()) + ", p99 " + Format.nanos(i.p99()) + ", max " + Format.nanos(i.max())
                    + " | late (over 75 ms): " + t.lateIntervals());
        }

        List<DimensionView> dims = rt.chunks().dimensions();
        if (dims.isEmpty()) {
            lines.add("Chunks: no chunk events seen yet");
        }
        long anomalies = 0;
        for (DimensionView d : dims) {
            anomalies += d.anomalies();
            lines.add("Chunks [" + d.dimension() + "]: " + d.loaded() + " loaded (peak " + d.peakLoaded() + ") | loads "
                    + d.loads() + " (" + d.newChunks() + " new) | unloads " + d.unloads() + " (" + d.shortLived()
                    + " within 5s of loading) | NBT read " + d.dataLoads() + ", written " + d.dataSaves());
        }

        ChunkWaitMonitor.Snapshot w = rt.waits().snapshot();
        lines.add("Server thread waiting for chunks: " + w.waits() + " waits, " + Format.nanos(w.waitedNanos()) + " in all | "
                + w.waitTime().describe() + " | getChunk from other threads: " + w.offThreadCalls());

        List<GenerationMonitor.StageSnapshot> stages = rt.generation().snapshot();
        if (stages.isEmpty()) {
            lines.add("Generation: no chunks generated yet");
        } else {
            lines.add("Generation by stage (inline = on the calling thread, done = until the stage finished):");
            for (GenerationMonitor.StageSnapshot g : stages) {
                lines.add("  " + g.name() + ": " + g.count() + (g.failed() > 0 ? " (" + g.failed() + " failed)" : "")
                        + " | inline " + Format.nanos(g.inlineSumNanos()) + " in all, p95 " + Format.nanos(g.inline().p95())
                        + " | done p50 " + Format.nanos(g.total().p50()) + ", p95 " + Format.nanos(g.total().p95())
                        + ", max " + Format.nanos(g.total().max()));
            }
        }

        StorageMonitor.Snapshot s = rt.storage().snapshot();
        lines.add("Chunk loads: " + s.loadsRequested() + " requested | " + s.loadsFromDisk() + " from disk, "
                + s.loadsEmpty() + " empty, " + s.loadsFailed() + " failed | " + s.loadTime().describe());
        lines.add("Save calls (server thread): " + s.saveCalls() + ", " + s.savesSerialized() + " serialised | "
                + s.saveCallTime().describe());
        for (StorageMonitor.TypeSnapshot ts : s.types()) {
            lines.add("Writes [" + ts.type() + "]: " + ts.writesSubmitted() + " submitted, " + ts.writesCompleted()
                    + " completed, " + ts.writesFailed() + " failed, " + ts.pendingWrites() + " pending (peak "
                    + ts.peakPendingWrites() + ") | " + ts.writeTime().describe());
            lines.add("Reads [" + ts.type() + "]: " + ts.readsFound() + " found, " + ts.readsMissing() + " absent, "
                    + ts.readsFailed() + " failed | " + ts.readTime().describe());
        }
        lines.add(rt.synchronousWrites()
                ? "Region writes are synchronous (DSYNC): a completed write has reached the storage device."
                : "Region writes are OS-buffered: a completed write has reached the operating system, not necessarily "
                + "the disk, until the server stops cleanly.");

        lines.add("Chunk I/O failures: " + s.reportedSaveFailures() + " save, " + s.reportedLoadFailures()
                + " load (load includes damaged entries vanilla silently treats as absent)");
        List<FailureLog.Failure> recent = rt.storage().failures().recent();
        for (int i = Math.max(0, recent.size() - RECENT_FAILURES_SHOWN); i < recent.size(); i++) {
            FailureLog.Failure f = recent.get(i);
            lines.add("  " + f.at() + " " + f.kind() + " " + f.storageType() + " " + f.dimension() + " ["
                    + f.chunkX() + ", " + f.chunkZ() + "] (" + Format.regionFile(f.chunkX(), f.chunkZ()) + "): " + f.error());
        }

        if (!optimizationLines.isEmpty()) {
            lines.add("Optimisations:");
            optimizationLines.forEach(line -> lines.add("  " + line));
        }

        StringJoiner probeLine = new StringJoiner(", ", "Mixins: ", "");
        probes.forEach((name, state) -> probeLine.add(name + " " + state.name().toLowerCase(java.util.Locale.ROOT)));
        lines.add(probeLine + " | hook errors " + ProbeGuard.totalFailures());

        lines.add("Anomalies: " + anomalies + " impossible chunk transitions, " + rt.chunks().offThreadEvents()
                + " chunk events off the server thread, " + rt.ticks().unpairedEnds() + " unpaired tick ends");

        CompatibilityReport c = rt.compatibility();
        lines.add("Compatibility: " + c.detected().size() + " known mods installed ("
                + c.count(CompatibilityStatus.INCOMPATIBLE) + " incompatible, "
                + c.count(CompatibilityStatus.CONFLICTING) + " conflicting, "
                + c.count(CompatibilityStatus.UNKNOWN) + " untested) | details: /chunkexpress compat");
        return lines;
    }
}
