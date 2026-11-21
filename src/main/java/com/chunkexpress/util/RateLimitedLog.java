package com.chunkexpress.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Lets at most one message per key through per interval, so a failing disk or a misbehaving mod
 * cannot flood the log. Suppressed messages are counted and reported with the next one let through.
 *
 * <p>Keys must come from a small fixed set (e.g. {@code "slow-write:chunk"}), never from data such
 * as chunk coordinates, or the key map would grow without bound.
 *
 * <p>Thread safety: safe to call from any thread.
 */
public final class RateLimitedLog {
    private static final long NEVER = Long.MIN_VALUE;

    private final long intervalNanos;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();

    public RateLimitedLog(long interval, TimeUnit unit) {
        this(interval, unit, System::nanoTime);
    }

    RateLimitedLog(long interval, TimeUnit unit, LongSupplier clock) {
        this.intervalNanos = unit.toNanos(interval);
        this.clock = clock;
    }

    /**
     * Returns -1 if the message for {@code key} must be dropped now; otherwise the number of
     * messages suppressed since the last one let through (0 or more), and the caller should log.
     */
    public long tryAcquire(String key) {
        Slot slot = this.slots.computeIfAbsent(key, k -> new Slot());
        long now = this.clock.getAsLong();
        long last = slot.lastEmit.get();
        if (last != NEVER && now - last < this.intervalNanos) {
            slot.suppressed.incrementAndGet();
            return -1;
        }
        if (!slot.lastEmit.compareAndSet(last, now)) {
            slot.suppressed.incrementAndGet();
            return -1;
        }
        return slot.suppressed.getAndSet(0);
    }

    private static final class Slot {
        final AtomicLong lastEmit = new AtomicLong(NEVER);
        final AtomicLong suppressed = new AtomicLong();
    }
}
