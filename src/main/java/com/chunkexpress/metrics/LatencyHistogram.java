package com.chunkexpress.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * A fixed-size, lock-free latency histogram for nanosecond durations.
 *
 * <p>Buckets are log-linear: every power of two is split into {@value #SUB_BUCKETS} equal
 * sub-buckets, and values below {@value #SUB_BUCKETS} ns get one bucket each. A percentile is
 * reported as the upper edge of the bucket it falls in, so it is never lower than the true value
 * and at most 12.5% higher. The whole histogram is {@value #BUCKETS} longs (about 4 KiB) whatever
 * the number of samples, so recording never allocates.
 *
 * <p>Thread safety: {@link #record} may be called concurrently from any thread. A
 * {@link #snapshot} taken while other threads record is not an atomic cut: a sample may be counted
 * in the bucket array but not yet in the sum. The error is bounded by the samples in flight and is
 * acceptable for monitoring. {@link #reset} is likewise not atomic with concurrent recording.
 */
public final class LatencyHistogram {
    static final int SUB_BUCKET_BITS = 3;
    static final int SUB_BUCKETS = 1 << SUB_BUCKET_BITS;
    /** Highest bucket covers values whose top bit is bit 62, i.e. all non-negative longs. */
    static final int BUCKETS = SUB_BUCKETS + (62 - SUB_BUCKET_BITS + 1) * SUB_BUCKETS;

    private final AtomicLongArray counts = new AtomicLongArray(BUCKETS);
    private final LongAdder sum = new LongAdder();
    private final AtomicLong max = new AtomicLong();

    public void record(long nanos) {
        long value = Math.max(0L, nanos);
        this.counts.incrementAndGet(bucketOf(value));
        this.sum.add(value);
        if (value > this.max.get()) {
            this.max.accumulateAndGet(value, Math::max);
        }
    }

    public void reset() {
        for (int i = 0; i < BUCKETS; i++) {
            this.counts.set(i, 0L);
        }
        this.sum.reset();
        this.max.set(0L);
    }

    public Snapshot snapshot() {
        long[] copy = new long[BUCKETS];
        long total = 0;
        for (int i = 0; i < BUCKETS; i++) {
            copy[i] = this.counts.get(i);
            total += copy[i];
        }
        long maxValue = this.max.get();
        if (total == 0) {
            return Snapshot.EMPTY;
        }
        return new Snapshot(
                total,
                this.sum.sum() / total,
                percentile(copy, total, 0.50, maxValue),
                percentile(copy, total, 0.95, maxValue),
                percentile(copy, total, 0.99, maxValue),
                maxValue);
    }

    private static long percentile(long[] counts, long total, double quantile, long maxValue) {
        long rank = Math.max(1L, (long) Math.ceil(quantile * total));
        long seen = 0;
        for (int i = 0; i < counts.length; i++) {
            seen += counts[i];
            if (seen >= rank) {
                // The bucket's upper edge can exceed the largest value ever seen; never report more.
                return Math.min(upperBoundInclusive(i), maxValue);
            }
        }
        return maxValue;
    }

    static int bucketOf(long value) {
        if (value < SUB_BUCKETS) {
            return (int) value;
        }
        int msb = 63 - Long.numberOfLeadingZeros(value);
        int shift = msb - SUB_BUCKET_BITS;
        int sub = (int) (value >>> shift) - SUB_BUCKETS;
        return SUB_BUCKETS + shift * SUB_BUCKETS + sub;
    }

    static long lowerBound(int bucket) {
        if (bucket < SUB_BUCKETS) {
            return bucket;
        }
        int shift = (bucket - SUB_BUCKETS) / SUB_BUCKETS;
        int sub = (bucket - SUB_BUCKETS) % SUB_BUCKETS;
        return (long) (SUB_BUCKETS + sub) << shift;
    }

    static long upperBoundInclusive(int bucket) {
        if (bucket == BUCKETS - 1) {
            return Long.MAX_VALUE;
        }
        return lowerBound(bucket + 1) - 1;
    }

    /** An immutable summary. All values are nanoseconds; {@code count == 0} means no samples. */
    public record Snapshot(long count, long mean, long p50, long p95, long p99, long max) {
        public static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0, 0, 0);

        /** e.g. {@code "n=120 p50 1.2 ms, p95 8.0 ms, p99 15 ms, max 40 ms"}. */
        public String describe() {
            if (this.count == 0) {
                return "n=0";
            }
            return "n=" + this.count
                    + " p50 " + Format.nanos(this.p50)
                    + ", p95 " + Format.nanos(this.p95)
                    + ", p99 " + Format.nanos(this.p99)
                    + ", max " + Format.nanos(this.max);
        }
    }
}
