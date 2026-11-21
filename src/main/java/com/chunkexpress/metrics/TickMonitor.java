package com.chunkexpress.metrics;

import java.util.Arrays;

/**
 * Server tick timing over a sliding window of the last {@value #WINDOW} ticks (one minute at
 * 20 TPS), plus all-time totals. Two different numbers, because they disagree badly under chunk
 * load:
 * <ul>
 *   <li><b>tick work</b> - from NeoForge's {@code ServerTickEvent.Pre} (highest priority) to
 *       {@code ServerTickEvent.Post} (lowest priority): the work inside
 *       {@code MinecraftServer.tickServer}. This is what vanilla's MSPT reports.</li>
 *   <li><b>tick interval</b> - from one tick's start to the next one's. It also contains the
 *       server-thread work vanilla runs <em>between</em> ticks (queued chunk tasks such as
 *       promoting generated chunks to full, deserialising loaded ones, network packets). In the
 *       vanilla ice-road benchmark tick work averaged 6 ms while the interval reached 207 ms at p95:
 *       only the interval shows what players feel. 50 ms is on time at 20 TPS.</li>
 * </ul>
 *
 * <p>Thread safety: confined to the server thread. Both tick events and command execution run
 * there, so no synchronisation is needed; do not call from other threads.
 */
public final class TickMonitor {
    public static final int WINDOW = 1200;
    /** One tick at the default 20 TPS. Ticks longer than this make the server fall behind. */
    public static final long BUDGET_NANOS = 50_000_000L;

    private final long[] work = new long[WINDOW];
    private final long[] intervals = new long[WINDOW];
    private int next;
    private int filled;
    private int nextInterval;
    private int filledIntervals;
    private long tickStart = -1;
    private long lastTickStart = -1;
    private long ticks;
    private long overBudget;
    private long lateIntervals;
    private long unpairedEnds;

    public void tickStarted(long nanoTime) {
        if (this.lastTickStart >= 0) {
            long interval = Math.max(0, nanoTime - this.lastTickStart);
            this.intervals[this.nextInterval] = interval;
            this.nextInterval = (this.nextInterval + 1) % WINDOW;
            this.filledIntervals = Math.min(this.filledIntervals + 1, WINDOW);
            if (interval > BUDGET_NANOS + BUDGET_NANOS / 2) {
                this.lateIntervals++;
            }
        }
        this.lastTickStart = nanoTime;
        this.tickStart = nanoTime;
    }

    public void tickEnded(long nanoTime) {
        if (this.tickStart < 0) {
            // e.g. the runtime was created part-way through a tick
            this.unpairedEnds++;
            return;
        }
        long duration = Math.max(0, nanoTime - this.tickStart);
        this.tickStart = -1;
        this.work[this.next] = duration;
        this.next = (this.next + 1) % WINDOW;
        this.filled = Math.min(this.filled + 1, WINDOW);
        this.ticks++;
        if (duration > BUDGET_NANOS) {
            this.overBudget++;
        }
    }

    /** Clears the statistics; the next interval is measured from the last tick start seen. */
    public void reset() {
        this.next = 0;
        this.filled = 0;
        this.nextInterval = 0;
        this.filledIntervals = 0;
        this.ticks = 0;
        this.overBudget = 0;
        this.lateIntervals = 0;
        this.unpairedEnds = 0;
    }

    public Snapshot snapshot() {
        return new Snapshot(Window.of(this.work, this.filled), Window.of(this.intervals, this.filledIntervals),
                this.ticks, this.overBudget, this.lateIntervals);
    }

    public long unpairedEnds() {
        return this.unpairedEnds;
    }

    /** Statistics over one ring buffer, in nanoseconds. */
    public record Window(int count, long mean, long p50, long p95, long p99, long max) {
        static final Window EMPTY = new Window(0, 0, 0, 0, 0, 0);

        static Window of(long[] ring, int filled) {
            if (filled == 0) {
                return EMPTY;
            }
            // Only the first `filled` slots were ever written; the ring's order does not matter once sorted.
            long[] sorted = Arrays.copyOf(ring, filled);
            Arrays.sort(sorted);
            long sum = 0;
            for (long v : sorted) {
                sum += v;
            }
            return new Window(filled, sum / filled, at(sorted, 0.50), at(sorted, 0.95), at(sorted, 0.99),
                    sorted[sorted.length - 1]);
        }

        private static long at(long[] sorted, double quantile) {
            int index = (int) Math.ceil(quantile * sorted.length) - 1;
            return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
        }
    }

    /**
     * @param work            tick work over the window
     * @param interval        tick start-to-start intervals over the window
     * @param totalTicks      all-time ticks measured
     * @param overBudgetTicks all-time ticks whose work exceeded 50 ms
     * @param lateIntervals   all-time intervals longer than 75 ms (the tick started late)
     */
    public record Snapshot(Window work, Window interval, long totalTicks, long overBudgetTicks, long lateIntervals) {
    }
}
