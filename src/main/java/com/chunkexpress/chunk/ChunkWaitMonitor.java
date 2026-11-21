package com.chunkexpress.chunk;

import com.chunkexpress.metrics.LatencyHistogram;

import java.util.concurrent.atomic.LongAdder;

/**
 * Times the server thread spends blocked in {@code ServerChunkCache.getChunk} waiting for a
 * chunk that is not ready, fed by the observe-only {@code ServerChunkCacheMixin}.
 *
 * <p>This is the classic chunk stall: some code on the server thread (a mod, a command, a block
 * update at a chunk border, an entity) asks for a chunk synchronously, and the whole tick waits
 * for it to be loaded or generated. While waiting, vanilla's {@code managedBlock} keeps running
 * queued server-thread tasks, so some of the waiting time does useful work; the number is still
 * time the tick could not move on.
 *
 * <p>Also counts {@code getChunk} calls from other threads, which vanilla turns into a blocking
 * hop to the server thread: a common source of worker-thread stalls and deadlocks in mods.
 *
 * <p>Thread safety: safe from any thread.
 */
public final class ChunkWaitMonitor {
    private final LongAdder waits = new LongAdder();
    private final LongAdder waitedNanos = new LongAdder();
    private final LatencyHistogram waitTime = new LatencyHistogram();
    private final LongAdder offThreadCalls = new LongAdder();

    public void serverThreadWaited(long nanos) {
        this.waits.increment();
        this.waitedNanos.add(nanos);
        this.waitTime.record(nanos);
    }

    public void offThreadCall() {
        this.offThreadCalls.increment();
    }

    public void reset() {
        this.waits.reset();
        this.waitedNanos.reset();
        this.waitTime.reset();
        this.offThreadCalls.reset();
    }

    public Snapshot snapshot() {
        return new Snapshot(this.waits.sum(), this.waitedNanos.sum(), this.waitTime.snapshot(), this.offThreadCalls.sum());
    }

    public record Snapshot(long waits, long waitedNanos, LatencyHistogram.Snapshot waitTime, long offThreadCalls) {
    }
}
