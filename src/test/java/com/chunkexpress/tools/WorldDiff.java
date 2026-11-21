package com.chunkexpress.tools;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Compares the generated terrain of two worlds: for every chunk stored as fully generated in both,
 * the block state and biome at every position. Used to check that a generation optimisation
 * produces the same world as vanilla.
 *
 * <p>Read-only, development-only, and independent of the game's own region code so that it cannot
 * be affected by the code under test: it reads Anvil region files directly (header offsets, length,
 * compression byte, zlib/gzip/uncompressed) and decodes palettes and packed data itself.
 *
 * <p>Comparison is by value (palette entry name + properties), not by palette index, because
 * palette order depends on the order blocks were written.
 *
 * <p>Usage: {@code WorldDiff <worldA> <worldB> [region-folder]} (default "region", the overworld).
 */
public final class WorldDiff {
    private WorldDiff() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args[0].isEmpty() || args[1].isEmpty()) {
            System.err.println("usage: WorldDiff <worldA> <worldB> [region-folder]");
            System.exit(2);
        }
        String folder = args.length > 2 && !args[2].isEmpty() ? args[2] : "region";
        Result result = compare(Path.of(args[0]).resolve(folder), Path.of(args[1]).resolve(folder));
        System.out.println(result.describe());
    }

    public static Result compare(Path regionsA, Path regionsB) throws IOException {
        Map<Long, CompoundTag> a = readFullChunks(regionsA);
        Map<Long, CompoundTag> b = readFullChunks(regionsB);
        Result result = new Result(a.size(), b.size());
        for (Map.Entry<Long, CompoundTag> e : new TreeMap<>(a).entrySet()) {
            CompoundTag other = b.get(e.getKey());
            if (other == null) {
                continue;
            }
            result.compared++;
            ChunkDiff diff = compareChunk(e.getValue(), other);
            if (diff.blocks == 0 && diff.biomes == 0) {
                result.identical++;
            } else {
                result.differing.add(new ChunkResult((int) (e.getKey() >> 32), (int) (long) e.getKey(), diff));
                result.blocks += diff.blocks;
                result.biomes += diff.biomes;
            }
        }
        return result;
    }

    static ChunkDiff compareChunk(CompoundTag a, CompoundTag b) {
        ChunkDiff diff = new ChunkDiff();
        Map<Integer, CompoundTag> sectionsA = sections(a);
        Map<Integer, CompoundTag> sectionsB = sections(b);
        for (Map.Entry<Integer, CompoundTag> e : sectionsA.entrySet()) {
            CompoundTag sb = sectionsB.get(e.getKey());
            String[] blocksA = decode(e.getValue().getCompound("block_states"), 4096, 4);
            String[] blocksB = sb == null ? null : decode(sb.getCompound("block_states"), 4096, 4);
            String[] biomesA = decode(e.getValue().getCompound("biomes"), 64, 1);
            String[] biomesB = sb == null ? null : decode(sb.getCompound("biomes"), 64, 1);
            diff.blocks += count(blocksA, blocksB, e.getKey(), diff, true);
            diff.biomes += count(biomesA, biomesB, e.getKey(), diff, false);
        }
        return diff;
    }

    private static int count(String[] a, String[] b, int sectionY, ChunkDiff diff, boolean blocks) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null || b == null) {
            return a == null ? b.length : a.length;
        }
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            if (!a[i].equals(b[i])) {
                n++;
                if (blocks && diff.examples.size() < 3) {
                    int x = i & 15;
                    int z = (i >> 4) & 15;
                    int y = sectionY * 16 + (i >> 8);
                    diff.examples.add(String.format(Locale.ROOT, "(%d, %d, %d): %s vs %s", x, y, z, a[i], b[i]));
                }
            }
        }
        return n;
    }

    private static Map<Integer, CompoundTag> sections(CompoundTag chunk) {
        Map<Integer, CompoundTag> result = new HashMap<>();
        ListTag list = chunk.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag section = list.getCompound(i);
            result.put((int) section.getByte("Y"), section);
        }
        return result;
    }

    /**
     * Decodes a paletted container as saved since 1.18: "palette" (list) and, unless the palette
     * has a single entry, "data" (packed longs, entries never spanning two longs). Returns one
     * string per position, or null when the container is absent.
     *
     * @param minBits 4 for block states, 1 for biomes (vanilla's serialisation minimums)
     */
    static String[] decode(CompoundTag container, int size, int minBits) {
        if (container == null || !container.contains("palette", Tag.TAG_LIST)) {
            return null;
        }
        ListTag palette = (ListTag) container.get("palette");
        String[] values = new String[palette.size()];
        for (int i = 0; i < values.length; i++) {
            Tag entry = palette.get(i);
            values[i] = entry instanceof CompoundTag c ? describe(c) : entry.getAsString();
        }
        String[] out = new String[size];
        if (values.length == 1 || !container.contains("data", Tag.TAG_LONG_ARRAY)) {
            java.util.Arrays.fill(out, values.length == 0 ? "?" : values[0]);
            return out;
        }
        long[] data = container.getLongArray("data");
        int bits = Math.max(minBits, 32 - Integer.numberOfLeadingZeros(values.length - 1));
        int perLong = 64 / bits;
        int expectedLongs = (size + perLong - 1) / perLong;
        if (data.length != expectedLongs) {
            // Not the layout we expect: refuse to guess.
            java.util.Arrays.fill(out, "undecodable(" + bits + " bits, " + data.length + " longs)");
            return out;
        }
        long mask = (1L << bits) - 1;
        for (int i = 0; i < size; i++) {
            long word = data[i / perLong];
            int index = (int) ((word >>> ((i % perLong) * bits)) & mask);
            out[i] = index < values.length ? values[index] : "invalid-index(" + index + ")";
        }
        return out;
    }

    /** "minecraft:oak_log[axis=y]" with properties sorted, so equal states compare equal. */
    static String describe(CompoundTag state) {
        String name = state.getString("Name");
        if (!state.contains("Properties", Tag.TAG_COMPOUND)) {
            return name;
        }
        CompoundTag props = state.getCompound("Properties");
        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;
        for (String key : new java.util.TreeSet<>(props.getAllKeys())) {
            if (!first) {
                sb.append(',');
            }
            sb.append(key).append('=').append(props.getString(key));
            first = false;
        }
        return sb.append(']').toString();
    }

    /** Chunk position (x << 32 | z) to NBT, for chunks whose saved status is full. */
    static Map<Long, CompoundTag> readFullChunks(Path regionFolder) throws IOException {
        Map<Long, CompoundTag> chunks = new HashMap<>();
        if (!Files.isDirectory(regionFolder)) {
            return chunks;
        }
        List<Path> files;
        try (Stream<Path> list = Files.list(regionFolder)) {
            files = list.filter(p -> p.getFileName().toString().endsWith(".mca")).toList();
        }
        for (Path file : files) {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length < 8192) {
                continue;
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            for (int index = 0; index < 1024; index++) {
                int entry = buffer.getInt(index * 4);
                if (entry == 0) {
                    continue;
                }
                int sector = entry >>> 8;
                long offset = sector * 4096L;
                if (offset + 5 > bytes.length) {
                    continue;
                }
                int length = buffer.getInt((int) offset);
                int compression = bytes[(int) offset + 4];
                if (length <= 1 || (compression & 0x80) != 0 || offset + 4 + length > bytes.length) {
                    continue; // empty, external (.mcc) or truncated: not compared
                }
                InputStream raw = new ByteArrayInputStream(bytes, (int) offset + 5, length - 1);
                InputStream in = switch (compression) {
                    case 1 -> new GZIPInputStream(raw);
                    case 2 -> new InflaterInputStream(raw);
                    case 3 -> raw;
                    default -> null;
                };
                if (in == null) {
                    continue;
                }
                CompoundTag tag;
                try (DataInputStream data = new DataInputStream(in)) {
                    tag = NbtIo.read(data, NbtAccounter.unlimitedHeap());
                }
                if ("minecraft:full".equals(tag.getString("Status")) || "full".equals(tag.getString("Status"))) {
                    long key = ((long) tag.getInt("xPos") << 32) | (tag.getInt("zPos") & 0xFFFFFFFFL);
                    chunks.put(key, tag);
                }
            }
        }
        return chunks;
    }

    static final class ChunkDiff {
        long blocks;
        long biomes;
        final List<String> examples = new ArrayList<>();
    }

    record ChunkResult(int x, int z, ChunkDiff diff) {
    }

    public static final class Result {
        final int chunksA;
        final int chunksB;
        int compared;
        int identical;
        long blocks;
        long biomes;
        final List<ChunkResult> differing = new ArrayList<>();

        Result(int chunksA, int chunksB) {
            this.chunksA = chunksA;
            this.chunksB = chunksB;
        }

        public int compared() {
            return this.compared;
        }

        public int differingChunks() {
            return this.differing.size();
        }

        public long differingBlocks() {
            return this.blocks;
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "full chunks: %d in A, %d in B, %d in both%n", this.chunksA, this.chunksB, this.compared));
            sb.append(String.format(Locale.ROOT, "identical: %d, differing: %d (%d block positions, %d biome cells)%n",
                    this.identical, this.differing.size(), this.blocks, this.biomes));
            this.differing.stream()
                    .sorted((x, y) -> Long.compare(y.diff().blocks, x.diff().blocks))
                    .limit(10)
                    .forEach(c -> sb.append(String.format(Locale.ROOT, "  chunk [%d, %d]: %d blocks, %d biomes; e.g. %s%n",
                            c.x(), c.z(), c.diff().blocks, c.diff().biomes, String.join("; ", c.diff().examples))));
            return sb.toString();
        }
    }
}
