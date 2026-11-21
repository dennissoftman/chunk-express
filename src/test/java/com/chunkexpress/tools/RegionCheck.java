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
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Offline integrity check of a world's region files, used after hard-kill tests
 * (scripts/kill-test.sh). Read-only, and independent of the game's own region code so that a bug
 * there cannot hide a problem here.
 *
 * <p>For every {@code .mca} file under {@code region}, {@code entities} and {@code poi} of every
 * dimension it checks:
 * <ul>
 *   <li>header entries point past the header, have a size, end inside the file, and do not overlap;</li>
 *   <li>each entry has a sane length and a known compression, decompresses and parses as NBT;</li>
 *   <li>external {@code .mcc} files exist for entries that say so;</li>
 *   <li>chunk data: the stored position matches the slot, a {@code Status} is present, and every
 *       section's block states and biomes decode;</li>
 *   <li>entity data: the stored position matches the slot.</li>
 * </ul>
 * Chunks that are simply missing (never saved) are not problems: after a crash, unsaved progress
 * is lost by design. Damage is what this looks for.
 *
 * <p>Usage: {@code RegionCheck <world folder>}. Exit code 1 if any problem is found.
 */
public final class RegionCheck {
    private static final int SECTOR = 4096;

    private RegionCheck() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args[0].isEmpty()) {
            System.err.println("usage: RegionCheck <world folder>");
            System.exit(2);
        }
        Report report = checkWorld(Path.of(args[0]));
        System.out.print(report.describe());
        if (!report.problems.isEmpty()) {
            System.exit(1);
        }
    }

    public static Report checkWorld(Path world) throws IOException {
        Report report = new Report();
        List<Path> dimensions = new ArrayList<>(List.of(world, world.resolve("DIM-1"), world.resolve("DIM1")));
        Path modded = world.resolve("dimensions");
        if (Files.isDirectory(modded)) {
            try (Stream<Path> namespaces = Files.list(modded)) {
                for (Path ns : namespaces.toList()) {
                    try (Stream<Path> dims = Files.list(ns)) {
                        dimensions.addAll(dims.filter(Files::isDirectory).toList());
                    }
                }
            }
        }
        for (Path dimension : dimensions) {
            for (String kind : new String[]{"region", "entities", "poi"}) {
                Path folder = dimension.resolve(kind);
                if (!Files.isDirectory(folder)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(folder)) {
                    for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".mca")).sorted().toList()) {
                        checkRegionFile(file, kind, report);
                    }
                }
            }
        }
        return report;
    }

    static void checkRegionFile(Path file, String kind, Report report) throws IOException {
        report.files++;
        byte[] bytes = Files.readAllBytes(file);
        String name = world(file);
        if (bytes.length == 0) {
            return; // created but never written: vanilla treats it as empty
        }
        if (bytes.length < 2 * SECTOR) {
            report.problem(name, "header truncated: " + bytes.length + " bytes");
            return;
        }
        int[] regionXZ = regionCoordinates(file);
        int sectors = (bytes.length + SECTOR - 1) / SECTOR;
        BitSet used = new BitSet(sectors);
        used.set(0, 2);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        for (int index = 0; index < 1024; index++) {
            int entry = buffer.getInt(index * 4);
            if (entry == 0) {
                continue;
            }
            String where = name + " slot " + index;
            int sector = entry >>> 8;
            int count = entry & 0xFF;
            if (sector < 2) {
                report.problem(where, "points into the header (sector " + sector + ")");
                continue;
            }
            if (count == 0) {
                report.problem(where, "has zero size");
                continue;
            }
            if ((long) (sector + count) * SECTOR > (long) sectors * SECTOR) {
                report.problem(where, "ends past the end of the file");
                continue;
            }
            if (used.get(sector, sector + count).cardinality() > 0) {
                report.problem(where, "overlaps another entry");
            }
            used.set(sector, sector + count);

            int offset = sector * SECTOR;
            int length = buffer.getInt(offset);
            if (length <= 0) {
                report.problem(where, "allocated but its stream is empty (length " + length + ")");
                continue;
            }
            if (length + 4L > (long) count * SECTOR) {
                report.problem(where, "stream longer than its sectors (" + length + " bytes in " + count + " sectors)");
                continue;
            }
            int compression = bytes[offset + 4] & 0xFF;
            CompoundTag tag;
            try {
                tag = read(file, index, regionXZ, compression, bytes, offset + 5, length - 1);
            } catch (IOException | RuntimeException e) {
                report.problem(where, "unreadable: " + e);
                continue;
            }
            if (tag == null) {
                report.unchecked++;
                continue;
            }
            int expectedX = regionXZ[0] * 32 + (index & 31);
            int expectedZ = regionXZ[1] * 32 + (index >> 5);
            report.entries++;
            switch (kind) {
                case "region" -> checkChunk(tag, expectedX, expectedZ, where, report);
                case "entities" -> checkEntities(tag, expectedX, expectedZ, where, report);
                default -> { } // poi: parsing is the check
            }
        }
    }

    private static CompoundTag read(Path file, int index, int[] regionXZ, int compression, byte[] bytes, int start, int length)
            throws IOException {
        if ((compression & 0x80) != 0) {
            int x = regionXZ[0] * 32 + (index & 31);
            int z = regionXZ[1] * 32 + (index >> 5);
            Path external = file.resolveSibling("c." + x + "." + z + ".mcc");
            if (!Files.exists(external)) {
                throw new IOException("external file " + external.getFileName() + " is missing");
            }
            byte[] data = Files.readAllBytes(external);
            return parse(compression & 0x7F, data, 0, data.length);
        }
        return parse(compression, bytes, start, length);
    }

    /** Returns null for compressions this tool cannot decode (LZ4 or custom): counted, not judged. */
    private static CompoundTag parse(int compression, byte[] bytes, int start, int length) throws IOException {
        InputStream raw = new ByteArrayInputStream(bytes, start, length);
        InputStream in = switch (compression) {
            case 1 -> new GZIPInputStream(raw);
            case 2 -> new InflaterInputStream(raw);
            case 3 -> raw;
            case 4, 127 -> null;
            default -> throw new IOException("unknown compression " + compression);
        };
        if (in == null) {
            return null;
        }
        try (DataInputStream data = new DataInputStream(in)) {
            return NbtIo.read(data, NbtAccounter.unlimitedHeap());
        }
    }

    private static void checkChunk(CompoundTag tag, int x, int z, String where, Report report) {
        if (!tag.contains("xPos", Tag.TAG_ANY_NUMERIC) || tag.getInt("xPos") != x || tag.getInt("zPos") != z) {
            report.problem(where, "stored position [" + tag.getInt("xPos") + ", " + tag.getInt("zPos")
                    + "] is not the slot's [" + x + ", " + z + "]");
        }
        if (!tag.contains("Status", Tag.TAG_STRING)) {
            report.problem(where, "no Status (vanilla would regenerate this chunk)");
        }
        ListTag sections = tag.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompound(i);
            for (String[] container : new String[][]{{"block_states", "4096", "4"}, {"biomes", "64", "1"}}) {
                if (!section.contains(container[0], Tag.TAG_COMPOUND)) {
                    continue;
                }
                String[] values = WorldDiff.decode(section.getCompound(container[0]),
                        Integer.parseInt(container[1]), Integer.parseInt(container[2]));
                if (values != null && values.length > 0
                        && (values[0].startsWith("undecodable") || values[0].startsWith("invalid-index"))) {
                    report.problem(where, container[0] + " of section " + section.getByte("Y") + ": " + values[0]);
                }
            }
        }
        report.chunks++;
    }

    private static void checkEntities(CompoundTag tag, int x, int z, String where, Report report) {
        int[] position = tag.getIntArray("Position");
        if (position.length != 2 || position[0] != x || position[1] != z) {
            report.problem(where, "entity data for the wrong chunk");
        }
    }

    static int[] regionCoordinates(Path file) {
        String[] parts = file.getFileName().toString().split("\\.");
        return new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
    }

    private static String world(Path file) {
        Path parent = file.getParent();
        return (parent.getParent() == null ? "" : parent.getParent().getFileName() + "/") + parent.getFileName() + "/"
                + file.getFileName();
    }

    public static final class Report {
        int files;
        int entries;
        int chunks;
        int unchecked;
        final List<String> problems = new ArrayList<>();

        void problem(String where, String what) {
            this.problems.add(where + ": " + what);
        }

        public List<String> problems() {
            return this.problems;
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "region files: %d, entries read: %d (chunks: %d), not decodable here (LZ4/custom): %d%n",
                    this.files, this.entries, this.chunks, this.unchecked));
            sb.append(this.problems.isEmpty() ? "no problems found" : this.problems.size() + " problems:").append('\n');
            this.problems.stream().limit(50).forEach(p -> sb.append("  ").append(p).append('\n'));
            return sb.toString();
        }
    }
}
