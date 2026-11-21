package com.chunkexpress.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares two stage-hash files written by {@code com.chunkexpress.generation.StageHashes}: for every
 * chunk present in both, the hash of its blocks at the same stage boundary must be equal.
 * Usage: {@code StageHashDiff <a.csv> <b.csv>}. Exits with 1 if any common chunk differs.
 */
public final class StageHashDiff {
    private StageHashDiff() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args[0].isEmpty() || args[1].isEmpty()) {
            System.err.println("usage: StageHashDiff <a.csv> <b.csv>");
            System.exit(2);
        }
        Map<String, String> a = read(Path.of(args[0]));
        Map<String, String> b = read(Path.of(args[1]));
        int common = 0;
        int differing = 0;
        StringBuilder examples = new StringBuilder();
        for (Map.Entry<String, String> e : a.entrySet()) {
            String other = b.get(e.getKey());
            if (other == null) {
                continue;
            }
            common++;
            if (!other.equals(e.getValue())) {
                differing++;
                if (differing <= 10) {
                    examples.append("  chunk ").append(e.getKey()).append(": ").append(e.getValue()).append(" vs ").append(other).append('\n');
                }
            }
        }
        System.out.printf("chunks: %d in A, %d in B, %d in both, %d differ%n%s", a.size(), b.size(), common, differing, examples);
        if (differing > 0) {
            System.exit(1);
        }
    }

    /** Chunk "x,z" to hash. A chunk recorded twice keeps its last hash and is flagged by a differing duplicate. */
    static Map<String, String> read(Path file) throws IOException {
        Map<String, String> hashes = new HashMap<>();
        List<String> lines = Files.readAllLines(file);
        for (String line : lines) {
            int last = line.lastIndexOf(',');
            if (last <= 0) {
                continue;
            }
            String key = line.substring(0, last);
            String hash = line.substring(last + 1);
            String previous = hashes.put(key, hash);
            if (previous != null && !previous.equals(hash)) {
                hashes.put(key, "inconsistent(" + previous + "/" + hash + ")");
            }
        }
        return hashes;
    }
}
