package com.chunkexpress.metrics;

import java.time.Duration;
import java.util.Locale;

/** Human-readable numbers for status output and logs. */
public final class Format {
    private Format() {
    }

    /** {@code 950 µs -> "0.95 ms"}, {@code 12_340_000 -> "12.3 ms"}, {@code 4.5 s -> "4.50 s"}. */
    public static String nanos(long nanos) {
        double ms = nanos / 1_000_000.0;
        if (ms < 1.0) {
            return String.format(Locale.ROOT, "%.2f ms", ms);
        }
        if (ms < 100.0) {
            return String.format(Locale.ROOT, "%.1f ms", ms);
        }
        if (ms < 1000.0) {
            return String.format(Locale.ROOT, "%.0f ms", ms);
        }
        return String.format(Locale.ROOT, "%.2f s", ms / 1000.0);
    }

    /** {@code "3h 04m 05s"}, {@code "4m 05s"}, {@code "5s"}. */
    public static String duration(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) {
            return String.format(Locale.ROOT, "%dh %02dm %02ds", h, m, s);
        }
        if (m > 0) {
            return String.format(Locale.ROOT, "%dm %02ds", m, s);
        }
        return s + "s";
    }

    /** The vanilla region file holding a chunk: {@code "r.-1.3.mca"}. */
    public static String regionFile(int chunkX, int chunkZ) {
        return "r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca";
    }
}
