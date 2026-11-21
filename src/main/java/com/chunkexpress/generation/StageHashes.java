package com.chunkexpress.generation;

import com.chunkexpress.util.Logs;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Development check for generation optimisations: records a hash of each chunk's blocks at the
 * <em>start</em> of chosen generation stages, i.e. the exact output of everything before them.
 *
 * <p>Why stage boundaries: vanilla 1.21.1 terrain is not reproducible as a whole. Features
 * (ores, clay, vegetation, cave decoration) depend on the order chunks happen to be generated in,
 * and in two vanilla runs of the same seed about 40% of chunks end up different. The stages before
 * features (noise, surface, carvers) depend only on seed and position, so their output must be
 * identical in any two runs - with or without an optimisation that moves them to other threads.
 *
 * <p>Enabled with {@code -Dchunkexpress.debug.stageHashes=carvers,features} (statuses whose input is
 * hashed) and optionally {@code -Dchunkexpress.debug.stageHashesTag=name}. Writes
 * {@code chunkexpress-debug/<tag>-<status>.csv} with lines {@code x,z,hash}. Off by default; when off
 * the only cost is one static boolean check per generation step.
 *
 * <p>Hashing happens on the thread about to run the stage, before it runs; the stage's declared
 * dependencies guarantee nothing else writes the chunk then.
 *
 * <p>Thread safety: writers are per status and synchronized.
 */
public final class StageHashes {
    public static final String PROPERTY = "chunkexpress.debug.stageHashes";
    public static final String TAG_PROPERTY = "chunkexpress.debug.stageHashesTag";

    private static final Set<String> STAGES = parse(System.getProperty(PROPERTY));
    public static final boolean ENABLED = !STAGES.isEmpty();
    private static final String TAG = System.getProperty(TAG_PROPERTY, "run");
    private static final Map<String, BufferedWriter> WRITERS = new ConcurrentHashMap<>();

    private StageHashes() {
    }

    static Set<String> parse(String value) {
        Set<String> stages = new TreeSet<>();
        if (value == null) {
            return stages;
        }
        for (String part : value.split(",")) {
            String name = part.trim().toLowerCase(Locale.ROOT);
            if (!name.isEmpty()) {
                stages.add(name.contains(":") ? name : "minecraft:" + name);
            }
        }
        return stages;
    }

    public static boolean wanted(String status) {
        return ENABLED && STAGES.contains(status);
    }

    public static void record(String status, int chunkX, int chunkZ, long hash) {
        BufferedWriter writer = WRITERS.computeIfAbsent(status, StageHashes::open);
        if (writer == null) {
            return;
        }
        synchronized (writer) {
            try {
                writer.write(chunkX + "," + chunkZ + "," + Long.toHexString(hash));
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                Logs.LOG.warn("Stage hash write failed: {}", e.toString());
            }
        }
    }

    private static BufferedWriter open(String status) {
        try {
            Path dir = Path.of("chunkexpress-debug");
            Files.createDirectories(dir);
            Path file = dir.resolve(TAG + "-" + status.replace(':', '_') + ".csv");
            Logs.LOG.info("Chunk Express debug: hashing chunk blocks at the start of {} into {}", status, file.toAbsolutePath());
            return Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Logs.LOG.warn("Cannot open stage hash file for {}: {}", status, e.toString());
            return null;
        }
    }

    /** 64-bit FNV-1a step. */
    public static long mix(long hash, int value) {
        hash ^= value & 0xFF;
        hash *= 0x100000001B3L;
        hash ^= (value >>> 8) & 0xFF;
        hash *= 0x100000001B3L;
        hash ^= (value >>> 16) & 0xFF;
        hash *= 0x100000001B3L;
        hash ^= value >>> 24;
        hash *= 0x100000001B3L;
        return hash;
    }

    public static final long SEED = 0xCBF29CE484222325L;
}
