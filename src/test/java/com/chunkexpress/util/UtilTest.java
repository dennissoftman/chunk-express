package com.chunkexpress.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilTest {

    @Test
    void rateLimiterLetsOneMessagePerIntervalAndReportsSuppressed() {
        AtomicLong now = new AtomicLong(0);
        RateLimitedLog log = new RateLimitedLog(1, TimeUnit.MINUTES, now::get);

        assertEquals(0, log.tryAcquire("a"));
        assertEquals(-1, log.tryAcquire("a"));
        assertEquals(-1, log.tryAcquire("a"));
        assertEquals(0, log.tryAcquire("b"), "keys are independent");

        now.addAndGet(TimeUnit.SECONDS.toNanos(61));
        assertEquals(2, log.tryAcquire("a"), "two messages were suppressed");
        assertEquals(-1, log.tryAcquire("a"));
    }

    @Test
    void probeGuardDisablesItselfAfterRepeatedFailures() {
        ProbeGuard guard = new ProbeGuard("test");
        for (int i = 1; i < ProbeGuard.MAX_FAILURES; i++) {
            guard.failed(new IllegalStateException("bug " + i));
            assertTrue(guard.enabled());
        }
        guard.failed(new IllegalStateException("last straw"));
        assertFalse(guard.enabled());
        assertEquals(ProbeGuard.MAX_FAILURES, guard.failures());
    }

    @Test
    void probeGuardNeverSwallowsJvmErrors() {
        ProbeGuard guard = new ProbeGuard("test");
        assertThrows(StackOverflowError.class, () -> guard.failed(new StackOverflowError()));
    }
}
