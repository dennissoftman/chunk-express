package com.chunkexpress.storage;

import com.chunkexpress.storage.StorageMonitor.LoadOutcome;
import com.chunkexpress.storage.StorageMonitor.ReadOutcome;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StorageMonitorTest {

    @Test
    void pendingWritesTrackSubmissionsUntilTheyFinish() {
        StorageMonitor m = new StorageMonitor();
        m.writeSubmitted("chunk", false);
        m.writeSubmitted("chunk", false);
        m.writeSubmitted("entities", true);
        assertEquals(3, m.pendingWrites());

        m.writeFinished("chunk", 1_000, true);
        m.writeFinished("entities", 2_000, false);
        StorageMonitor.Snapshot s = m.snapshot();
        assertEquals(1, s.pendingWrites());
        assertEquals(2, s.type("chunk").peakPendingWrites());
        assertEquals(1, s.type("chunk").writesCompleted());
        assertEquals(1, s.type("entities").writesFailed());
        assertEquals(1, s.type("entities").clears());
    }

    @Test
    void resetKeepsWritesThatAreStillInFlight() {
        StorageMonitor m = new StorageMonitor();
        m.writeSubmitted("chunk", false);
        m.saveCall(5_000, true);
        m.reset();

        StorageMonitor.Snapshot s = m.snapshot();
        assertEquals(1, s.pendingWrites());
        assertEquals(0, s.type("chunk").writesSubmitted());
        assertEquals(0, s.saveCalls());

        m.writeFinished("chunk", 1_000, true);
        assertEquals(0, m.pendingWrites());
    }

    @Test
    void readsAndLoadsAreClassified() {
        StorageMonitor m = new StorageMonitor();
        m.readSubmitted("chunk");
        m.readFinished("chunk", 10, ReadOutcome.FOUND);
        m.readFinished("chunk", 10, ReadOutcome.MISSING);
        m.readFinished("chunk", 10, ReadOutcome.FAILED);
        m.loadRequested();
        m.loadFinished(10, LoadOutcome.FROM_DISK);
        m.loadFinished(10, LoadOutcome.EMPTY);

        StorageMonitor.Snapshot s = m.snapshot();
        assertEquals(1, s.type("chunk").readsFound());
        assertEquals(1, s.type("chunk").readsMissing());
        assertEquals(1, s.type("chunk").readsFailed());
        assertEquals(3, s.type("chunk").readTime().count());
        assertEquals(1, s.loadsFromDisk());
        assertEquals(1, s.loadsEmpty());
        assertEquals(0, s.loadsFailed());
    }

    @Test
    void unboundedStorageTypeNamesFallIntoOther() {
        StorageMonitor m = new StorageMonitor();
        for (int i = 0; i < StorageMonitor.MAX_TYPES + 10; i++) {
            m.readSubmitted("modded_" + i);
        }
        StorageMonitor.Snapshot s = m.snapshot();
        assertEquals(StorageMonitor.MAX_TYPES + 1, s.types().size());
        assertEquals(10, s.type(StorageMonitor.OTHER_TYPE).readsSubmitted());
    }

    @Test
    void failureLogIsBoundedButCountsEverything() {
        FailureLog log = new FailureLog(3);
        for (int i = 0; i < 5; i++) {
            log.add(new FailureLog.Failure(Instant.EPOCH, i % 2 == 0 ? FailureLog.Kind.LOAD : FailureLog.Kind.SAVE,
                    "chunk", "minecraft:overworld", i, 0, "boom " + i));
        }
        assertEquals(3, log.recent().size());
        assertEquals(2, log.recent().get(0).chunkX(), "oldest entries dropped first");
        assertEquals(3, log.loadFailures());
        assertEquals(2, log.saveFailures());
    }
}
