package com.chunkexpress.integration;

import com.chunkexpress.bootstrap.ChunkExpressRuntime;
import com.chunkexpress.generation.GenerationMonitor;
import com.chunkexpress.generation.StageHashes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import com.chunkexpress.metrics.Format;
import com.chunkexpress.storage.FailureLog;
import com.chunkexpress.storage.StorageMonitor;
import com.chunkexpress.storage.StorageMonitor.LoadOutcome;
import com.chunkexpress.storage.StorageMonitor.ReadOutcome;
import com.chunkexpress.util.Logs;
import com.chunkexpress.util.ProbeGuard;
import com.chunkexpress.util.RateLimitedLog;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The bodies of the probe mixins. The mixins themselves only forward here, so that the logic is
 * ordinary, readable Java and the mixin classes stay a few lines each.
 *
 * <h2>Observe-only contract</h2>
 * Every method here:
 * <ul>
 *   <li>never changes arguments, return values or control flow of the hooked method;</li>
 *   <li>never completes, cancels or replaces a vanilla future - it only attaches a
 *       {@code whenComplete} observer, whose own result is discarded;</li>
 *   <li>never blocks, and does O(1) work on the calling thread;</li>
 *   <li>never lets an exception escape (see {@link ProbeGuard}).</li>
 * </ul>
 *
 * <h2>Threads</h2>
 * {@code chunkSaveCall} and {@code chunkLoadScheduled} are called on the server thread.
 * {@code writeSubmitted}/{@code readSubmitted} are called on whichever thread calls
 * {@code IOWorker}, usually the server thread; their completion observers run on vanilla's I/O
 * pool. Failure reports may arrive on any thread. Everything recorded here goes to
 * {@link StorageMonitor}, which is thread-safe.
 */
public final class ProbeHooks {
    private static final ProbeGuard SAVE_GUARD = new ProbeGuard("chunk-save");
    private static final ProbeGuard LOAD_GUARD = new ProbeGuard("chunk-load");
    private static final ProbeGuard WRITE_GUARD = new ProbeGuard("region-write");
    private static final ProbeGuard READ_GUARD = new ProbeGuard("region-read");
    private static final ProbeGuard FAILURE_GUARD = new ProbeGuard("io-failure");
    private static final ProbeGuard GENERATION_GUARD = new ProbeGuard("generation-step");
    private static final ProbeGuard WAIT_GUARD = new ProbeGuard("chunk-wait");
    private static final RateLimitedLog RATE = new RateLimitedLog(1, TimeUnit.MINUTES);

    private ProbeHooks() {
    }

    /** End of {@code ChunkMap.save(ChunkAccess)}; {@code serialized} is its return value. */
    public static void chunkSaveCall(long nanos, boolean serialized) {
        ChunkExpressRuntime rt = ChunkExpressRuntime.active();
        if (rt == null || !SAVE_GUARD.enabled()) {
            return;
        }
        try {
            rt.storage().saveCall(nanos, serialized);
        } catch (Throwable t) {
            SAVE_GUARD.failed(t);
        }
    }

    /** Return of {@code ChunkMap.scheduleChunkLoad}. */
    public static void chunkLoadScheduled(CompletableFuture<ChunkAccess> future) {
        ChunkExpressRuntime rt = ChunkExpressRuntime.active();
        if (rt == null || future == null || !LOAD_GUARD.enabled()) {
            return;
        }
        try {
            long start = System.nanoTime();
            StorageMonitor monitor = rt.storage();
            monitor.loadRequested();
            future.whenComplete((chunk, error) -> {
                try {
                    monitor.loadFinished(System.nanoTime() - start, classify(chunk, error));
                } catch (Throwable t) {
                    LOAD_GUARD.failed(t);
                }
            });
        } catch (Throwable t) {
            LOAD_GUARD.failed(t);
        }
    }

    private static LoadOutcome classify(ChunkAccess chunk, Throwable error) {
        if (error != null || chunk == null) {
            return LoadOutcome.FAILED;
        }
        // Vanilla returns a fresh ProtoChunk at EMPTY when there is no usable data (absent, invalid,
        // or after a load failure). Stored chunks come back as an ImposterProtoChunk (full chunks) or
        // a ProtoChunk at their saved status. A stored chunk saved at EMPTY is rare and counts as empty.
        if (chunk instanceof ImposterProtoChunk || chunk.getPersistedStatus() != ChunkStatus.EMPTY) {
            return LoadOutcome.FROM_DISK;
        }
        return LoadOutcome.EMPTY;
    }

    /** Return of {@code IOWorker.store}. {@code data == null} means the entry is being deleted. */
    public static void writeSubmitted(RegionStorageInfo info, ChunkPos pos, CompoundTag data, CompletableFuture<Void> future) {
        ChunkExpressRuntime rt = ChunkExpressRuntime.active();
        if (rt == null || future == null || !WRITE_GUARD.enabled()) {
            return;
        }
        try {
            long start = System.nanoTime();
            String type = info.type();
            StorageMonitor monitor = rt.storage();
            long warnNanos = TimeUnit.MILLISECONDS.toNanos(rt.settings().slowWriteWarnMillis());
            monitor.writeSubmitted(type, data == null);
            future.whenComplete((ignored, error) -> {
                try {
                    long nanos = System.nanoTime() - start;
                    monitor.writeFinished(type, nanos, error == null);
                    if (warnNanos > 0 && nanos > warnNanos) {
                        warnSlowWrite(info, pos, nanos);
                    }
                } catch (Throwable t) {
                    WRITE_GUARD.failed(t);
                }
            });
        } catch (Throwable t) {
            WRITE_GUARD.failed(t);
        }
    }

    /** Return of {@code IOWorker.loadAsync}. */
    public static void readSubmitted(RegionStorageInfo info, CompletableFuture<Optional<CompoundTag>> future) {
        ChunkExpressRuntime rt = ChunkExpressRuntime.active();
        if (rt == null || future == null || !READ_GUARD.enabled()) {
            return;
        }
        try {
            long start = System.nanoTime();
            String type = info.type();
            StorageMonitor monitor = rt.storage();
            monitor.readSubmitted(type);
            future.whenComplete((result, error) -> {
                try {
                    ReadOutcome outcome = error != null ? ReadOutcome.FAILED
                            : result != null && result.isPresent() ? ReadOutcome.FOUND : ReadOutcome.MISSING;
                    monitor.readFinished(type, System.nanoTime() - start, outcome);
                } catch (Throwable t) {
                    READ_GUARD.failed(t);
                }
            });
        } catch (Throwable t) {
            READ_GUARD.failed(t);
        }
    }

    /**
     * Head of {@code MinecraftServer.reportChunkLoadFailure} / {@code reportChunkSaveFailure}.
     * Vanilla has already logged the exception; this adds what it means for the world and what the
     * operator can do, because vanilla's own message does not say.
     */
    public static void ioFailureReported(boolean save, RegionStorageInfo info, ChunkPos pos, Throwable error) {
        if (!FAILURE_GUARD.enabled()) {
            return;
        }
        try {
            ChunkExpressRuntime rt = ChunkExpressRuntime.current();
            if (rt != null && !rt.settings().instrumentation()) {
                return;
            }
            String dimension = String.valueOf(info.dimension().location());
            String type = info.type();
            String message = error == null ? "unknown error" : error.getClass().getSimpleName() + ": " + error.getMessage();
            if (rt != null) {
                rt.storage().failures().add(new FailureLog.Failure(Instant.now(),
                        save ? FailureLog.Kind.SAVE : FailureLog.Kind.LOAD, type, dimension, pos.x, pos.z, message));
            }
            String key = (save ? "save-failure:" : "load-failure:") + type;
            long suppressed = RATE.tryAcquire(key);
            if (suppressed < 0) {
                return;
            }
            String region = Format.regionFile(pos.x, pos.z);
            if (save) {
                Logs.LOG.error("Chunk Express: saving {} data for chunk [{}, {}] in {} failed ({}). Vanilla already marked "
                                + "this chunk as saved, so it will not retry until the chunk changes again; the file "
                                + "{} most likely still holds the previous save. ({} similar messages suppressed)",
                        type, pos.x, pos.z, dimension, message, region, suppressed);
            } else if ("chunk".equals(type)) {
                Logs.LOG.error("Chunk Express: chunk [{}, {}] in {} could not be read ({}). Vanilla now treats it as "
                                + "missing and generates it again; when that new chunk is saved, the unreadable data in "
                                + "{} is overwritten. Chunk Express does not change this. To keep the original, stop "
                                + "the server and back up that region file first. ({} similar messages suppressed)",
                        pos.x, pos.z, dimension, message, region, suppressed);
            } else {
                Logs.LOG.error("Chunk Express: {} data for chunk [{}, {}] in {} could not be read ({}). Vanilla continues "
                                + "without it, and the stored copy in {} is overwritten the next time this chunk's {} are "
                                + "saved. Back up that file first to keep it. ({} similar messages suppressed)",
                        type, pos.x, pos.z, dimension, message, region, type, suppressed);
            }
        } catch (Throwable t) {
            FAILURE_GUARD.failed(t);
        }
    }

    /**
     * {@code RegionFile.getChunkDataInputStream} returned null for an entry the header says exists.
     * Vanilla has logged the specific damage; it will now treat the chunk as absent.
     */
    public static void unreadableRegionEntry(RegionStorageInfo info, Path file, ChunkPos pos) {
        if (!FAILURE_GUARD.enabled()) {
            return;
        }
        try {
            ChunkExpressRuntime rt = ChunkExpressRuntime.current();
            if (rt != null && !rt.settings().instrumentation()) {
                return;
            }
            String dimension = String.valueOf(info.dimension().location());
            if (rt != null) {
                rt.storage().failures().add(new FailureLog.Failure(Instant.now(), FailureLog.Kind.LOAD, info.type(),
                        dimension, pos.x, pos.z, "damaged region entry; vanilla treats the chunk as absent"));
            }
            long suppressed = RATE.tryAcquire("damaged-entry:" + info.type());
            if (suppressed >= 0) {
                Logs.LOG.error("Chunk Express: the {} entry for chunk [{}, {}] in {} exists in {} but is damaged (see the "
                                + "vanilla message just before this). Vanilla treats it as absent: chunk data is generated "
                                + "again, and the damaged entry is overwritten on the next save. Chunk Express does not "
                                + "change this. Back up that file now to keep what is left. ({} similar messages suppressed)",
                        info.type(), pos.x, pos.z, dimension, file, suppressed);
            }
        } catch (Throwable t) {
            FAILURE_GUARD.failed(t);
        }
    }

    /** Return of a {@code ChunkStep.apply} that generates (not a pass-through for a stored chunk). */
    public static void generationStep(ChunkStatus status, long startNanos, CompletableFuture<ChunkAccess> future) {
        ChunkExpressRuntime rt = ChunkExpressRuntime.active();
        if (rt == null || future == null || !GENERATION_GUARD.enabled()) {
            return;
        }
        try {
            long inline = System.nanoTime() - startNanos;
            int index = status.getIndex();
            String name = status.getName();
            GenerationMonitor monitor = rt.generation();
            future.whenComplete((chunk, error) -> {
                try {
                    monitor.stageFinished(index, name, inline, System.nanoTime() - startNanos, error != null);
                } catch (Throwable t) {
                    GENERATION_GUARD.failed(t);
                }
            });
        } catch (Throwable t) {
            GENERATION_GUARD.failed(t);
        }
    }

    /**
     * Development check ({@link StageHashes}): hash of the chunk's block states as a stage starts.
     * Uses global block-state ids, which are identical between runs with the same mods.
     */
    public static void stageInput(ChunkStatus status, ChunkAccess chunk) {
        String name = status.getName();
        if (StageHashes.wanted(name)) {
            hashChunk(name, chunk);
        }
    }

    /** Records the hash of a chunk's block states under {@code key} ({@link StageHashes}). */
    public static void hashChunk(String key, ChunkAccess chunk) {
        long hash = StageHashes.SEED;
        LevelChunkSection[] sections = chunk.getSections();
        for (int s = 0; s < sections.length; s++) {
            PalettedContainer<BlockState> states = sections[s].getStates();
            hash = StageHashes.mix(hash, s);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        hash = StageHashes.mix(hash, Block.getId(states.get(x, y, z)));
                    }
                }
            }
        }
        StageHashes.record(key, chunk.getPos().x, chunk.getPos().z, hash);
    }

    /** The server thread waited inside {@code ServerChunkCache.getChunk} for a chunk. */
    public static void serverThreadChunkWait(long nanos) {
        ChunkExpressRuntime rt = ChunkExpressRuntime.active();
        if (rt == null || !WAIT_GUARD.enabled()) {
            return;
        }
        try {
            rt.waits().serverThreadWaited(nanos);
        } catch (Throwable t) {
            WAIT_GUARD.failed(t);
        }
    }

    /** {@code ServerChunkCache.getChunk} was called from a thread other than the server thread. */
    public static void offThreadGetChunk() {
        ChunkExpressRuntime rt = ChunkExpressRuntime.active();
        if (rt == null || !WAIT_GUARD.enabled()) {
            return;
        }
        try {
            rt.waits().offThreadCall();
        } catch (Throwable t) {
            WAIT_GUARD.failed(t);
        }
    }

    /** {@code ChunkMap.isChunkDataValid} rejected stored data for having no "Status" tag. */
    public static void chunkDataWithoutStatus(CompoundTag tag) {
        if (!FAILURE_GUARD.enabled()) {
            return;
        }
        try {
            ChunkExpressRuntime rt = ChunkExpressRuntime.current();
            if (rt != null && !rt.settings().instrumentation()) {
                return;
            }
            // Chunk data stores its own position; the dimension is not known at this point.
            boolean hasPos = tag != null && tag.contains("xPos", 99) && tag.contains("zPos", 99);
            int x = hasPos ? tag.getInt("xPos") : 0;
            int z = hasPos ? tag.getInt("zPos") : 0;
            if (rt != null) {
                rt.storage().failures().add(new FailureLog.Failure(Instant.now(), FailureLog.Kind.LOAD, "chunk",
                        "(dimension unknown)", x, z, "stored data has no Status tag; vanilla regenerates the chunk"));
            }
            long suppressed = RATE.tryAcquire("missing-status");
            if (suppressed >= 0) {
                Logs.LOG.error("Chunk Express: stored data for chunk {} has no \"Status\" tag (see vanilla's \"missing level "
                                + "data\" message). Vanilla treats it as missing and generates it again; the stored entry "
                                + "in {} is overwritten on the next save. Back up that region file to keep it. "
                                + "({} similar messages suppressed)",
                        hasPos ? "[" + x + ", " + z + "]" : "(position unknown)",
                        hasPos ? Format.regionFile(x, z) : "its region file", suppressed);
            }
        } catch (Throwable t) {
            FAILURE_GUARD.failed(t);
        }
    }

    /** Opening a region file zeroed {@code count} invalid header entries in memory. */
    public static void regionEntriesDiscarded(RegionStorageInfo info, Path file, int count) {
        if (!FAILURE_GUARD.enabled()) {
            return;
        }
        try {
            ChunkExpressRuntime rt = ChunkExpressRuntime.current();
            if (rt != null && !rt.settings().instrumentation()) {
                return;
            }
            String dimension = String.valueOf(info.dimension().location());
            int[] region = regionCoordinates(file);
            if (rt != null) {
                rt.storage().failures().add(new FailureLog.Failure(Instant.now(), FailureLog.Kind.LOAD, info.type(), dimension,
                        region[0] * 32, region[1] * 32, count + " invalid header entries discarded when the file was opened"));
            }
            long suppressed = RATE.tryAcquire("discarded-entries:" + info.type());
            if (suppressed >= 0) {
                Logs.LOG.error("Chunk Express: opening {} discarded {} invalid {} header entries (see vanilla's \"invalid sector\" "
                                + "warnings). The chunks they pointed to are now treated as absent, and the next write to this "
                                + "region makes that permanent. Chunk Express does not change this. To keep what is left, stop "
                                + "the server now and back up that file. ({} similar messages suppressed)",
                        file, count, info.type(), suppressed);
            }
        } catch (Throwable t) {
            FAILURE_GUARD.failed(t);
        }
    }

    /** {@code r.X.Z.mca} to {X, Z}; {0, 0} if the name has another shape. */
    static int[] regionCoordinates(Path file) {
        String[] parts = String.valueOf(file.getFileName()).split("\\.");
        if (parts.length == 4 && parts[0].equals("r")) {
            try {
                return new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return new int[]{0, 0};
    }

    private static void warnSlowWrite(RegionStorageInfo info, ChunkPos pos, long nanos) {
        long suppressed = RATE.tryAcquire("slow-write:" + info.type());
        if (suppressed >= 0) {
            Logs.LOG.warn("Chunk Express: a {} write for chunk [{}, {}] in {} took {} from submission to completion "
                            + "(includes time queued behind other writes). Slow or overloaded storage? "
                            + "({} similar messages suppressed)",
                    info.type(), pos.x, pos.z, info.dimension().location(), Format.nanos(nanos), suppressed);
        }
    }
}
