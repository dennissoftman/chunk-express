package com.chunkexpress.bootstrap;

import com.chunkexpress.chunk.ChunkLifecycleTracker;
import com.chunkexpress.chunk.ChunkWaitMonitor;
import com.chunkexpress.generation.GenerationMonitor;
import com.chunkexpress.config.Settings;
import com.chunkexpress.integration.compatibility.CompatibilityReport;
import com.chunkexpress.metrics.TickMonitor;
import com.chunkexpress.storage.StorageMonitor;
import com.chunkexpress.util.Logs;

import java.time.Duration;
import java.time.Instant;

/**
 * Everything Chunk Express holds for one running server. Created at {@code ServerAboutToStartEvent},
 * discarded at {@code ServerStoppedEvent}.
 *
 * <p>Why per server rather than static: in singleplayer one JVM runs many servers in a row (one
 * per world opened). Static metrics would leak one world's numbers, and one world's loaded-chunk
 * set, into the next.
 *
 * <p>Hooks that run on other threads (I/O completions) capture the runtime when the operation
 * starts, so a write that finishes after the server stopped updates the old runtime's counters,
 * never the next server's.
 *
 * <p>Holds no Minecraft objects, so status rendering can be unit-tested.
 *
 * <p>Thread safety: {@link #current()} is a volatile read and safe anywhere. The monitors document
 * their own rules: {@link ChunkLifecycleTracker} and {@link TickMonitor} are server-thread only,
 * {@link StorageMonitor} is safe from any thread.
 */
public final class ChunkExpressRuntime {
    private static volatile ChunkExpressRuntime current;

    private final Settings settings;
    private final String serverKind;
    private final boolean synchronousWrites;
    private final Instant startedAt = Instant.now();
    private final ChunkLifecycleTracker chunks;
    private final StorageMonitor storage = new StorageMonitor();
    private final TickMonitor ticks = new TickMonitor();
    private final GenerationMonitor generation = new GenerationMonitor();
    private final ChunkWaitMonitor waits = new ChunkWaitMonitor();
    private final CompatibilityReport compatibility;
    private volatile RuntimePhase phase = RuntimePhase.STARTING;
    private volatile Instant countersResetAt = this.startedAt;

    public ChunkExpressRuntime(Settings settings, String serverKind, boolean synchronousWrites,
                             Thread serverThread, CompatibilityReport compatibility) {
        this.settings = settings;
        this.serverKind = serverKind;
        this.synchronousWrites = synchronousWrites;
        this.chunks = new ChunkLifecycleTracker(serverThread);
        this.compatibility = compatibility;
    }

    /** The running server's runtime, or null between servers and on a pure client. */
    public static ChunkExpressRuntime current() {
        return current;
    }

    /** {@link #current()} if instrumentation is enabled, else null. Hooks call this. */
    public static ChunkExpressRuntime active() {
        ChunkExpressRuntime rt = current;
        return rt != null && rt.settings.instrumentation() ? rt : null;
    }

    static void install(ChunkExpressRuntime runtime) {
        ChunkExpressRuntime previous = current;
        if (previous != null) {
            Logs.LOG.warn("A new server is starting but the previous server's Chunk Express runtime (phase {}) "
                    + "was never stopped. Replacing it.", previous.phase);
        }
        current = runtime;
    }

    static void uninstall(ChunkExpressRuntime runtime) {
        if (current == runtime) {
            current = null;
        }
    }

    /** Moves to {@code next}; an unexpected order is logged, never thrown. Returns the old phase. */
    RuntimePhase moveTo(RuntimePhase next) {
        RuntimePhase previous = this.phase;
        if (!previous.canMoveTo(next)) {
            Logs.LOG.warn("Unexpected server lifecycle order: {} -> {}. Metrics may be incomplete.", previous, next);
        }
        this.phase = next;
        return previous;
    }

    /** Zeroes metrics (not the set of loaded chunks or in-flight writes). Server thread only. */
    public void resetCounters() {
        this.chunks.resetCounters();
        this.storage.reset();
        this.ticks.reset();
        this.generation.reset();
        this.waits.reset();
        this.countersResetAt = Instant.now();
    }

    public Settings settings() {
        return this.settings;
    }

    /** {@code dedicated}, {@code integrated} (singleplayer/LAN) or {@code embedded} (e.g. game-test server). */
    public String serverKind() {
        return this.serverKind;
    }

    /**
     * Whether vanilla opens region files with {@code DSYNC}: {@code sync-chunk-writes} in
     * server.properties on a dedicated server, the "Sync Chunk Writes" video option in
     * singleplayer.
     */
    public boolean synchronousWrites() {
        return this.synchronousWrites;
    }

    public RuntimePhase phase() {
        return this.phase;
    }

    public Duration uptime() {
        return Duration.between(this.startedAt, Instant.now());
    }

    public Duration sinceCountersReset() {
        return Duration.between(this.countersResetAt, Instant.now());
    }

    public ChunkLifecycleTracker chunks() {
        return this.chunks;
    }

    public StorageMonitor storage() {
        return this.storage;
    }

    public TickMonitor ticks() {
        return this.ticks;
    }

    public GenerationMonitor generation() {
        return this.generation;
    }

    public ChunkWaitMonitor waits() {
        return this.waits;
    }

    public CompatibilityReport compatibility() {
        return this.compatibility;
    }
}
