package com.chunkexpress.chunk;

import com.chunkexpress.metrics.LatencyHistogram;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks which full chunks ({@code LevelChunk}s) are loaded in each dimension, from NeoForge's
 * {@code ChunkEvent.Load} and {@code ChunkEvent.Unload}, and counts the transitions.
 *
 * <p>Chunk Express only observes the vanilla chunk lifecycle, so this cannot enforce anything. It
 * <em>detects</em> transitions the model says are impossible and counts them as anomalies:
 * <ul>
 *   <li>a load for a chunk already recorded as loaded ({@code duplicateLoads});</li>
 *   <li>an unload for a chunk not recorded as loaded ({@code unknownUnloads}).</li>
 * </ul>
 * A non-zero anomaly count means either our model of the vanilla lifecycle is wrong or another
 * mod fires or suppresses chunk events unusually. Both are worth knowing before Chunk Express ever
 * takes over chunk transitions itself (the planned scheduler work).
 *
 * <p>Keys are {@code ChunkPos.toLong()} values and dimension ids as strings, so this class has no
 * Minecraft dependency and is unit-tested directly.
 *
 * <p>Thread safety: confined to the owner thread (the server thread). Calls from any other
 * thread are counted in {@link #offThreadEvents()} and otherwise ignored, because the maps are not
 * thread-safe and the events are documented to fire on the server thread.
 */
public final class ChunkLifecycleTracker {
    /** A chunk unloaded within this long of loading counts as short-lived (load/unload churn). */
    public static final long SHORT_LIVED_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final Thread owner;
    private final Map<String, Dimension> dimensions = new TreeMap<>();
    private final AtomicLong offThreadEvents = new AtomicLong();

    public ChunkLifecycleTracker(Thread owner) {
        this.owner = owner;
    }

    public void onLoad(String dimension, long chunkPos, boolean newlyGenerated, long nanoTime) {
        if (!onOwnerThread()) {
            return;
        }
        Dimension d = this.dimension(dimension);
        long previous = d.loadedAt.put(chunkPos, nanoTime);
        d.loads++;
        if (newlyGenerated) {
            d.newChunks++;
        }
        if (previous != Dimension.ABSENT) {
            d.duplicateLoads++;
        }
        d.peakLoaded = Math.max(d.peakLoaded, d.loadedAt.size());
    }

    public void onUnload(String dimension, long chunkPos, long nanoTime) {
        if (!onOwnerThread()) {
            return;
        }
        Dimension d = this.dimension(dimension);
        long loadedAt = d.loadedAt.remove(chunkPos);
        d.unloads++;
        if (loadedAt == Dimension.ABSENT) {
            d.unknownUnloads++;
            return;
        }
        long residency = Math.max(0, nanoTime - loadedAt);
        d.residency.record(residency);
        if (residency < SHORT_LIVED_NANOS) {
            d.shortLived++;
        }
    }

    /** A chunk's NBT was deserialised ({@code ChunkDataEvent.Load}). */
    public void onDataLoaded(String dimension) {
        if (onOwnerThread()) {
            this.dimension(dimension).dataLoads++;
        }
    }

    /** A chunk's NBT was serialised for saving ({@code ChunkDataEvent.Save}). */
    public void onDataSaved(String dimension) {
        if (onOwnerThread()) {
            this.dimension(dimension).dataSaves++;
        }
    }

    /**
     * The dimension's level was unloaded ({@code LevelEvent.Unload}). Returns how many chunks were
     * still recorded as loaded and forgets them. During a normal shutdown vanilla unloads every
     * chunk first, so a non-zero result is worth a log line.
     */
    public int onDimensionUnloaded(String dimension) {
        if (!onOwnerThread()) {
            return 0;
        }
        Dimension d = this.dimensions.get(dimension);
        if (d == null) {
            return 0;
        }
        int remaining = d.loadedAt.size();
        d.loadedAt.clear();
        return remaining;
    }

    public boolean isLoaded(String dimension, long chunkPos) {
        Dimension d = this.dimensions.get(dimension);
        return d != null && d.loadedAt.containsKey(chunkPos);
    }

    /**
     * Zeroes the counters but keeps the set of loaded chunks: those chunks really are still
     * loaded, and forgetting them would turn their later unloads into false anomalies.
     */
    public void resetCounters() {
        for (Dimension d : this.dimensions.values()) {
            d.resetCounters();
        }
        this.offThreadEvents.set(0);
    }

    public List<DimensionView> dimensions() {
        List<DimensionView> views = new ArrayList<>(this.dimensions.size());
        for (Map.Entry<String, Dimension> e : this.dimensions.entrySet()) {
            Dimension d = e.getValue();
            views.add(new DimensionView(e.getKey(), d.loadedAt.size(), d.peakLoaded, d.loads, d.newChunks,
                    d.unloads, d.shortLived, d.duplicateLoads, d.unknownUnloads, d.dataLoads, d.dataSaves,
                    d.residency.snapshot()));
        }
        return views;
    }

    public long offThreadEvents() {
        return this.offThreadEvents.get();
    }

    private boolean onOwnerThread() {
        if (Thread.currentThread() == this.owner) {
            return true;
        }
        this.offThreadEvents.incrementAndGet();
        return false;
    }

    private Dimension dimension(String id) {
        return this.dimensions.computeIfAbsent(id, k -> new Dimension());
    }

    private static final class Dimension {
        static final long ABSENT = Long.MIN_VALUE;

        final Long2LongOpenHashMap loadedAt = new Long2LongOpenHashMap();
        final LatencyHistogram residency = new LatencyHistogram();
        long peakLoaded;
        long loads;
        long newChunks;
        long unloads;
        long shortLived;
        long duplicateLoads;
        long unknownUnloads;
        long dataLoads;
        long dataSaves;

        Dimension() {
            this.loadedAt.defaultReturnValue(ABSENT);
        }

        void resetCounters() {
            this.peakLoaded = this.loadedAt.size();
            this.loads = 0;
            this.newChunks = 0;
            this.unloads = 0;
            this.shortLived = 0;
            this.duplicateLoads = 0;
            this.unknownUnloads = 0;
            this.dataLoads = 0;
            this.dataSaves = 0;
            this.residency.reset();
        }
    }

    /** Immutable per-dimension view for status output. */
    public record DimensionView(String dimension, int loaded, long peakLoaded, long loads, long newChunks,
                                long unloads, long shortLived, long duplicateLoads, long unknownUnloads,
                                long dataLoads, long dataSaves, LatencyHistogram.Snapshot residency) {
        public long anomalies() {
            return this.duplicateLoads + this.unknownUnloads;
        }
    }
}
