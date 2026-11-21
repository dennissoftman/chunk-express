package com.chunkexpress.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Wraps one observation point (a mixin hook or event handler) so that a bug in Chunk Express can
 * never break the game. Instrumentation is optional; Minecraft's own work is not.
 *
 * <p>Every hook body runs inside {@code try { ... } catch (Throwable t) { guard.failed(t); }}.
 * The first failure is logged with its stack trace. After {@link #MAX_FAILURES} failures the guard
 * disables itself and the hook becomes a no-op for the rest of the JVM's life.
 *
 * <p>{@link VirtualMachineError}s (out of memory, stack overflow) are rethrown: swallowing them
 * would hide a JVM in an undefined state.
 *
 * <p>Thread safety: safe to call from any thread.
 */
public final class ProbeGuard {
    public static final int MAX_FAILURES = 10;

    private static final AtomicLong TOTAL_FAILURES = new AtomicLong();

    private final String name;
    private final AtomicLong failures = new AtomicLong();
    private volatile boolean disabled;

    public ProbeGuard(String name) {
        this.name = name;
    }

    public boolean enabled() {
        return !this.disabled;
    }

    public void failed(Throwable t) {
        if (t instanceof VirtualMachineError error) {
            throw error;
        }
        TOTAL_FAILURES.incrementAndGet();
        long n = this.failures.incrementAndGet();
        if (n == 1) {
            Logs.LOG.error("Chunk Express probe '{}' failed. Minecraft's own operation was not affected; "
                    + "please report this with the stack trace below.", this.name, t);
        } else if (n == MAX_FAILURES) {
            this.disabled = true;
            Logs.LOG.error("Chunk Express probe '{}' failed {} times and is now disabled until restart. "
                    + "Its metrics will stop updating. Last error:", this.name, n, t);
        }
    }

    public String name() {
        return this.name;
    }

    public long failures() {
        return this.failures.get();
    }

    /** Failures across every guard since the JVM started. Shown by {@code /chunkexpress status}. */
    public static long totalFailures() {
        return TOTAL_FAILURES.get();
    }
}
