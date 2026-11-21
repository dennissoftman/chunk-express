package com.chunkexpress.chunk;

import com.chunkexpress.chunk.ChunkLifecycleTracker.DimensionView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkLifecycleTrackerTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    private final ChunkLifecycleTracker tracker = new ChunkLifecycleTracker(Thread.currentThread());

    private DimensionView view(String dimension) {
        return this.tracker.dimensions().stream().filter(d -> d.dimension().equals(dimension)).findFirst().orElseThrow();
    }

    @Test
    void countsLoadsUnloadsAndNewChunksPerDimension() {
        this.tracker.onLoad(OVERWORLD, 1L, true, 0);
        this.tracker.onLoad(OVERWORLD, 2L, false, 0);
        this.tracker.onLoad(NETHER, 1L, false, 0);
        this.tracker.onUnload(OVERWORLD, 1L, 10 * SECOND);

        DimensionView ow = view(OVERWORLD);
        assertEquals(1, ow.loaded());
        assertEquals(2, ow.peakLoaded());
        assertEquals(2, ow.loads());
        assertEquals(1, ow.newChunks());
        assertEquals(1, ow.unloads());
        assertEquals(0, ow.shortLived());
        assertEquals(0, ow.anomalies());
        assertEquals(10 * SECOND, ow.residency().max());
        assertEquals(1, view(NETHER).loaded());
        assertTrue(this.tracker.isLoaded(OVERWORLD, 2L));
        assertFalse(this.tracker.isLoaded(OVERWORLD, 1L));
    }

    @Test
    void sameKeyInDifferentDimensionsIsDifferentChunks() {
        this.tracker.onLoad(OVERWORLD, 7L, false, 0);
        this.tracker.onLoad(NETHER, 7L, false, 0);
        assertEquals(0, view(OVERWORLD).anomalies());
        assertEquals(0, view(NETHER).anomalies());
    }

    @Test
    void quickUnloadCountsAsShortLived() {
        this.tracker.onLoad(OVERWORLD, 1L, false, 0);
        this.tracker.onUnload(OVERWORLD, 1L, 4 * SECOND);
        assertEquals(1, view(OVERWORLD).shortLived());
    }

    @Test
    void doubleLoadIsAnAnomaly() {
        this.tracker.onLoad(OVERWORLD, 1L, false, 0);
        this.tracker.onLoad(OVERWORLD, 1L, false, SECOND);
        DimensionView ow = view(OVERWORLD);
        assertEquals(1, ow.duplicateLoads());
        assertEquals(1, ow.loaded());
    }

    @Test
    void unloadOfUntrackedChunkIsAnAnomaly() {
        this.tracker.onUnload(OVERWORLD, 99L, 0);
        DimensionView ow = view(OVERWORLD);
        assertEquals(1, ow.unknownUnloads());
        assertEquals(1, ow.unloads());
        assertEquals(0, ow.residency().count());
    }

    @Test
    void eventsFromOtherThreadsAreCountedButNotApplied() throws Exception {
        Thread other = new Thread(() -> {
            this.tracker.onLoad(OVERWORLD, 1L, false, 0);
            this.tracker.onDataSaved(OVERWORLD);
        });
        other.start();
        other.join();
        assertEquals(2, this.tracker.offThreadEvents());
        assertTrue(this.tracker.dimensions().isEmpty());
    }

    @Test
    void resetKeepsLoadedChunksSoLaterUnloadsAreNotAnomalies() {
        this.tracker.onLoad(OVERWORLD, 1L, false, 0);
        this.tracker.onDataLoaded(OVERWORLD);
        this.tracker.resetCounters();

        DimensionView afterReset = view(OVERWORLD);
        assertEquals(0, afterReset.loads());
        assertEquals(0, afterReset.dataLoads());
        assertEquals(1, afterReset.loaded());

        this.tracker.onUnload(OVERWORLD, 1L, 10 * SECOND);
        assertEquals(0, view(OVERWORLD).anomalies());
    }

    @Test
    void dimensionUnloadReportsAndForgetsRemainingChunks() {
        this.tracker.onLoad(OVERWORLD, 1L, false, 0);
        this.tracker.onLoad(OVERWORLD, 2L, false, 0);
        assertEquals(2, this.tracker.onDimensionUnloaded(OVERWORLD));
        assertEquals(0, view(OVERWORLD).loaded());
        assertEquals(0, this.tracker.onDimensionUnloaded("minecraft:never_seen"));
    }

    @Test
    void dimensionsAreListedInStableOrder() {
        this.tracker.onDataSaved(NETHER);
        this.tracker.onDataSaved(OVERWORLD);
        List<String> ids = this.tracker.dimensions().stream().map(DimensionView::dimension).toList();
        assertEquals(List.of(OVERWORLD, NETHER), ids);
    }
}
