package com.chunkexpress.metrics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TickMonitorTest {
    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);

    private static void tick(TickMonitor m, long start, long durationMs) {
        m.tickStarted(start);
        m.tickEnded(start + durationMs * MS);
    }

    @Test
    void emptyMonitorReportsNoWindow() {
        TickMonitor.Snapshot s = new TickMonitor().snapshot();
        assertEquals(0, s.work().count());
        assertEquals(0, s.interval().count());
        assertEquals(0, s.totalTicks());
    }

    @Test
    void workStatisticsOverAPartialWindow() {
        TickMonitor m = new TickMonitor();
        for (int i = 1; i <= 100; i++) {
            tick(m, i * 1_000 * MS, i);
        }
        TickMonitor.Window w = m.snapshot().work();
        assertEquals(100, w.count());
        assertEquals(50 * MS, w.p50());
        assertEquals(95 * MS, w.p95());
        assertEquals(99 * MS, w.p99());
        assertEquals(100 * MS, w.max());
        assertEquals(50 * MS + MS / 2, w.mean());
        assertEquals(50, m.snapshot().overBudgetTicks(), "ticks of 51..100 ms are over the 50 ms budget");
    }

    /** Short ticks with long gaps: work looks fine, the interval shows the server is late. */
    @Test
    void intervalCatchesWorkBetweenTicks() {
        TickMonitor m = new TickMonitor();
        long start = 0;
        for (int i = 0; i < 10; i++) {
            tick(m, start, 5);
            start += (i % 2 == 0 ? 50 : 200) * MS;
        }
        TickMonitor.Snapshot s = m.snapshot();
        assertEquals(5 * MS, s.work().max());
        assertEquals(9, s.interval().count(), "intervals need two tick starts");
        assertEquals(200 * MS, s.interval().max());
        assertEquals(4, s.lateIntervals());
        assertEquals(0, s.overBudgetTicks());
    }

    @Test
    void windowKeepsOnlyTheLatestTicks() {
        TickMonitor m = new TickMonitor();
        for (int i = 0; i < TickMonitor.WINDOW; i++) {
            tick(m, i * 1_000 * MS, 200);
        }
        for (int i = 0; i < TickMonitor.WINDOW; i++) {
            tick(m, (TickMonitor.WINDOW + i) * 1_000 * MS, 10);
        }
        TickMonitor.Snapshot s = m.snapshot();
        assertEquals(TickMonitor.WINDOW, s.work().count());
        assertEquals(10 * MS, s.work().max(), "old slow ticks have left the window");
        assertEquals(2L * TickMonitor.WINDOW, s.totalTicks());
        assertEquals(TickMonitor.WINDOW, s.overBudgetTicks(), "all-time count keeps them");
    }

    @Test
    void endWithoutStartIsIgnoredAndCounted() {
        TickMonitor m = new TickMonitor();
        m.tickEnded(5 * MS);
        assertEquals(0, m.snapshot().totalTicks());
        assertEquals(1, m.unpairedEnds());
        tick(m, 10 * MS, 3);
        m.tickEnded(20 * MS);
        assertEquals(1, m.snapshot().totalTicks());
        assertEquals(2, m.unpairedEnds());
    }

    @Test
    void resetClearsStatisticsButKeepsMeasuringIntervals() {
        TickMonitor m = new TickMonitor();
        tick(m, 0, 80);
        m.reset();
        assertEquals(0, m.snapshot().work().count());
        assertEquals(0, m.snapshot().overBudgetTicks());
        tick(m, 60 * MS, 1);
        assertEquals(60 * MS, m.snapshot().interval().max());
    }
}
