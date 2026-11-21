package com.chunkexpress.storage;

import com.chunkexpress.metrics.LatencyHistogram;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counters and latencies for vanilla's chunk storage pipeline, fed by observe-only probes.
 *
 * <p>What each stage means in Minecraft 1.21.1 (see docs/PERSISTENCE.md for the evidence):
 * <ul>
 *   <li><b>save call</b> - {@code ChunkMap.save(ChunkAccess)} on the server thread: clears the
 *       chunk's unsaved flag, serialises it to NBT and hands the NBT to the I/O worker.
 *       {@code serialized} is its return value.</li>
 *   <li><b>write</b> - {@code IOWorker.store}: from submission until vanilla's
 *       {@code RegionFile.write} returned. "Completed" means the bytes reached the operating
 *       system, and also the disk only if the server uses synchronous chunk writes. It is
 *       <em>not</em> a durability guarantee by itself.</li>
 *   <li><b>read</b> - {@code IOWorker.loadAsync}: region read and decompression.</li>
 *   <li><b>chunk load</b> - {@code ChunkMap.scheduleChunkLoad}: read, data-fix upgrade and
 *       main-thread deserialisation, until a {@code ChunkAccess} exists.</li>
 * </ul>
 * Storage types are vanilla's {@code RegionStorageInfo.type()}: {@code chunk}, {@code entities},
 * {@code poi}, or whatever a mod passes.
 *
 * <p>Thread safety: every method may be called from any thread. Writes complete on vanilla's I/O
 * pool; save calls and load completions happen on the server thread.
 */
public final class StorageMonitor {
    /** Mods may create their own region storages; cap the number tracked separately. */
    static final int MAX_TYPES = 32;
    static final String OTHER_TYPE = "other";

    private final ConcurrentHashMap<String, TypeStats> types = new ConcurrentHashMap<>();
    private final FailureLog failures = new FailureLog(32);

    private final LongAdder saveCalls = new LongAdder();
    private final LongAdder savesSerialized = new LongAdder();
    private final LatencyHistogram saveCallTime = new LatencyHistogram();

    private final LongAdder loadsRequested = new LongAdder();
    private final LongAdder loadsFromDisk = new LongAdder();
    private final LongAdder loadsEmpty = new LongAdder();
    private final LongAdder loadsFailed = new LongAdder();
    private final LatencyHistogram loadTime = new LatencyHistogram();

    public void saveCall(long nanos, boolean serialized) {
        this.saveCalls.increment();
        if (serialized) {
            this.savesSerialized.increment();
        }
        this.saveCallTime.record(nanos);
    }

    public void loadRequested() {
        this.loadsRequested.increment();
    }

    public void loadFinished(long nanos, LoadOutcome outcome) {
        switch (outcome) {
            case FROM_DISK -> this.loadsFromDisk.increment();
            case EMPTY -> this.loadsEmpty.increment();
            case FAILED -> this.loadsFailed.increment();
        }
        this.loadTime.record(nanos);
    }

    public void writeSubmitted(String type, boolean clear) {
        TypeStats t = this.type(type);
        t.writesSubmitted.increment();
        if (clear) {
            t.clears.increment();
        }
        long pending = t.pendingWrites.incrementAndGet();
        t.peakPendingWrites.accumulateAndGet(pending, Math::max);
    }

    public void writeFinished(String type, long nanos, boolean succeeded) {
        TypeStats t = this.type(type);
        t.pendingWrites.decrementAndGet();
        if (succeeded) {
            t.writesCompleted.increment();
        } else {
            t.writesFailed.increment();
        }
        t.writeTime.record(nanos);
    }

    public void readSubmitted(String type) {
        this.type(type).readsSubmitted.increment();
    }

    public void readFinished(String type, long nanos, ReadOutcome outcome) {
        TypeStats t = this.type(type);
        switch (outcome) {
            case FOUND -> t.readsFound.increment();
            case MISSING -> t.readsMissing.increment();
            case FAILED -> t.readsFailed.increment();
        }
        t.readTime.record(nanos);
    }

    public FailureLog failures() {
        return this.failures;
    }

    /** Writes submitted but not yet finished, over all storage types. */
    public long pendingWrites() {
        long total = 0;
        for (TypeStats t : this.types.values()) {
            total += t.pendingWrites.get();
        }
        return total;
    }

    /**
     * Zeroes counters and latencies. Pending-write gauges are kept: those writes are really still
     * in flight. Not atomic with concurrent recording.
     */
    public void reset() {
        this.saveCalls.reset();
        this.savesSerialized.reset();
        this.saveCallTime.reset();
        this.loadsRequested.reset();
        this.loadsFromDisk.reset();
        this.loadsEmpty.reset();
        this.loadsFailed.reset();
        this.loadTime.reset();
        for (TypeStats t : this.types.values()) {
            t.reset();
        }
        this.failures.reset();
    }

    public Snapshot snapshot() {
        Map<String, TypeSnapshot> byType = new TreeMap<>();
        for (Map.Entry<String, TypeStats> e : this.types.entrySet()) {
            byType.put(e.getKey(), e.getValue().snapshot(e.getKey()));
        }
        return new Snapshot(
                this.saveCalls.sum(), this.savesSerialized.sum(), this.saveCallTime.snapshot(),
                this.loadsRequested.sum(), this.loadsFromDisk.sum(), this.loadsEmpty.sum(), this.loadsFailed.sum(),
                this.loadTime.snapshot(),
                new ArrayList<>(byType.values()),
                this.failures.saveFailures(), this.failures.loadFailures());
    }

    private TypeStats type(String type) {
        TypeStats existing = this.types.get(type);
        if (existing != null) {
            return existing;
        }
        if (this.types.size() >= MAX_TYPES) {
            return this.types.computeIfAbsent(OTHER_TYPE, k -> new TypeStats());
        }
        return this.types.computeIfAbsent(type, k -> new TypeStats());
    }

    public enum LoadOutcome {
        /** Deserialised from stored data. */
        FROM_DISK,
        /** No usable data: a new chunk, or one vanilla replaced after a load failure. */
        EMPTY,
        /** The load future failed; vanilla turned this into a crash report. */
        FAILED
    }

    public enum ReadOutcome { FOUND, MISSING, FAILED }

    private static final class TypeStats {
        final LongAdder writesSubmitted = new LongAdder();
        final LongAdder writesCompleted = new LongAdder();
        final LongAdder writesFailed = new LongAdder();
        final LongAdder clears = new LongAdder();
        final AtomicLong pendingWrites = new AtomicLong();
        final AtomicLong peakPendingWrites = new AtomicLong();
        final LatencyHistogram writeTime = new LatencyHistogram();
        final LongAdder readsSubmitted = new LongAdder();
        final LongAdder readsFound = new LongAdder();
        final LongAdder readsMissing = new LongAdder();
        final LongAdder readsFailed = new LongAdder();
        final LatencyHistogram readTime = new LatencyHistogram();

        void reset() {
            this.writesSubmitted.reset();
            this.writesCompleted.reset();
            this.writesFailed.reset();
            this.clears.reset();
            this.peakPendingWrites.set(this.pendingWrites.get());
            this.writeTime.reset();
            this.readsSubmitted.reset();
            this.readsFound.reset();
            this.readsMissing.reset();
            this.readsFailed.reset();
            this.readTime.reset();
        }

        TypeSnapshot snapshot(String type) {
            return new TypeSnapshot(type,
                    this.writesSubmitted.sum(), this.writesCompleted.sum(), this.writesFailed.sum(), this.clears.sum(),
                    this.pendingWrites.get(), this.peakPendingWrites.get(), this.writeTime.snapshot(),
                    this.readsSubmitted.sum(), this.readsFound.sum(), this.readsMissing.sum(), this.readsFailed.sum(),
                    this.readTime.snapshot());
        }
    }

    public record TypeSnapshot(String type,
                               long writesSubmitted, long writesCompleted, long writesFailed, long clears,
                               long pendingWrites, long peakPendingWrites, LatencyHistogram.Snapshot writeTime,
                               long readsSubmitted, long readsFound, long readsMissing, long readsFailed,
                               LatencyHistogram.Snapshot readTime) {
    }

    public record Snapshot(long saveCalls, long savesSerialized, LatencyHistogram.Snapshot saveCallTime,
                           long loadsRequested, long loadsFromDisk, long loadsEmpty, long loadsFailed,
                           LatencyHistogram.Snapshot loadTime,
                           List<TypeSnapshot> types,
                           long reportedSaveFailures, long reportedLoadFailures) {
        public TypeSnapshot type(String type) {
            for (TypeSnapshot t : this.types) {
                if (t.type().equals(type)) {
                    return t;
                }
            }
            return new TypeSnapshot(type, 0, 0, 0, 0, 0, 0, LatencyHistogram.Snapshot.EMPTY,
                    0, 0, 0, 0, LatencyHistogram.Snapshot.EMPTY);
        }

        public long pendingWrites() {
            long total = 0;
            for (TypeSnapshot t : this.types) {
                total += t.pendingWrites();
            }
            return total;
        }
    }
}
