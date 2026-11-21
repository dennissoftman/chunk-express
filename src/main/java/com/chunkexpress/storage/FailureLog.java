package com.chunkexpress.storage;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * The most recent chunk I/O failures that vanilla reported through
 * {@code MinecraftServer.reportChunkLoadFailure} / {@code reportChunkSaveFailure}, kept so that
 * {@code /chunkexpress status} can show them without anyone digging through logs.
 *
 * <p>Bounded: keeps the last {@code capacity} entries and counts the rest.
 *
 * <p>Thread safety: safe from any thread (vanilla reports save failures from I/O threads).
 */
public final class FailureLog {
    private final int capacity;
    private final ArrayDeque<Failure> recent;
    private final LongAdder saveFailures = new LongAdder();
    private final LongAdder loadFailures = new LongAdder();

    public FailureLog(int capacity) {
        this.capacity = capacity;
        this.recent = new ArrayDeque<>(capacity);
    }

    public void add(Failure failure) {
        if (failure.kind() == Kind.SAVE) {
            this.saveFailures.increment();
        } else {
            this.loadFailures.increment();
        }
        synchronized (this.recent) {
            if (this.recent.size() == this.capacity) {
                this.recent.removeFirst();
            }
            this.recent.addLast(failure);
        }
    }

    /** Oldest first. */
    public List<Failure> recent() {
        synchronized (this.recent) {
            return List.copyOf(this.recent);
        }
    }

    public long saveFailures() {
        return this.saveFailures.sum();
    }

    public long loadFailures() {
        return this.loadFailures.sum();
    }

    void reset() {
        this.saveFailures.reset();
        this.loadFailures.reset();
        synchronized (this.recent) {
            this.recent.clear();
        }
    }

    public enum Kind { LOAD, SAVE }

    public record Failure(Instant at, Kind kind, String storageType, String dimension, int chunkX, int chunkZ,
                          String error) {
    }
}
