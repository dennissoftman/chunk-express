package com.chunkexpress.metrics;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyHistogramTest {

    @Test
    void bucketsCoverEveryNonNegativeLongContiguously() {
        assertEquals(0, LatencyHistogram.bucketOf(0));
        assertEquals(LatencyHistogram.BUCKETS - 1, LatencyHistogram.bucketOf(Long.MAX_VALUE));
        for (int b = 0; b < LatencyHistogram.BUCKETS - 1; b++) {
            long lower = LatencyHistogram.lowerBound(b);
            long upper = LatencyHistogram.upperBoundInclusive(b);
            assertTrue(lower <= upper, "bucket " + b);
            assertEquals(b, LatencyHistogram.bucketOf(lower), "lower edge of bucket " + b);
            assertEquals(b, LatencyHistogram.bucketOf(upper), "upper edge of bucket " + b);
            assertEquals(upper + 1, LatencyHistogram.lowerBound(b + 1), "gap after bucket " + b);
        }
    }

    @Test
    void reportedPercentileIsNeverBelowTheTrueValueAndAtMostOneEighthAbove() {
        Random random = new Random(42);
        for (int i = 0; i < 100_000; i++) {
            long value = Math.abs(random.nextLong()) >>> random.nextInt(62);
            int bucket = LatencyHistogram.bucketOf(value);
            long reported = LatencyHistogram.upperBoundInclusive(bucket);
            assertTrue(reported >= value);
            if (value >= 8) {
                assertTrue((reported - value) <= value / 8, "value " + value + " reported " + reported);
            } else {
                assertEquals(value, reported, "small values are exact");
            }
        }
    }

    @Test
    void percentilesOfAUniformSpread() {
        LatencyHistogram h = new LatencyHistogram();
        for (int ms = 1; ms <= 1000; ms++) {
            h.record(TimeUnit.MILLISECONDS.toNanos(ms));
        }
        LatencyHistogram.Snapshot s = h.snapshot();
        assertEquals(1000, s.count());
        assertWithinEighth(500, s.p50());
        assertWithinEighth(950, s.p95());
        assertWithinEighth(990, s.p99());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(1000), s.max());
        assertEquals(TimeUnit.MICROSECONDS.toNanos(500_500), s.mean());
    }

    @Test
    void percentileNeverExceedsTheMaximumSeen() {
        LatencyHistogram h = new LatencyHistogram();
        h.record(1_000_001);
        LatencyHistogram.Snapshot s = h.snapshot();
        assertEquals(1_000_001, s.p99());
        assertEquals(1_000_001, s.max());
    }

    @Test
    void negativeDurationsCountAsZero() {
        LatencyHistogram h = new LatencyHistogram();
        h.record(-5);
        assertEquals(0, h.snapshot().max());
        assertEquals(1, h.snapshot().count());
    }

    @Test
    void emptyAndResetHistogramsReportNothing() {
        LatencyHistogram h = new LatencyHistogram();
        assertEquals(LatencyHistogram.Snapshot.EMPTY, h.snapshot());
        h.record(123);
        h.reset();
        assertEquals(LatencyHistogram.Snapshot.EMPTY, h.snapshot());
        assertEquals("n=0", h.snapshot().describe());
    }

    @Test
    void concurrentRecordingLosesNoSamples() throws Exception {
        LatencyHistogram h = new LatencyHistogram();
        int threads = 8;
        int perThread = 50_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int t = 0; t < threads; t++) {
                long offset = t;
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        h.record(i * 10L + offset);
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals((long) threads * perThread, h.snapshot().count());
        assertEquals((perThread - 1) * 10L + threads - 1, h.snapshot().max());
    }

    private static void assertWithinEighth(long expectedMillis, long actualNanos) {
        long expected = TimeUnit.MILLISECONDS.toNanos(expectedMillis);
        assertTrue(actualNanos >= expected && actualNanos <= expected + expected / 8,
                "expected ~" + expectedMillis + " ms, got " + Format.nanos(actualNanos));
    }
}
