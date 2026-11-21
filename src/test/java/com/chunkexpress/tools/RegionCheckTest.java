package com.chunkexpress.tools;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionCheckTest {
    private static final int SECTOR = 4096;

    private static byte[] chunk(int x, int z, boolean withStatus) throws IOException {
        CompoundTag tag = new CompoundTag();
        tag.putInt("xPos", x);
        tag.putInt("zPos", z);
        if (withStatus) {
            tag.putString("Status", "minecraft:full");
        }
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(raw))) {
            NbtIo.write(tag, out);
        }
        byte[] compressed = raw.toByteArray();
        ByteBuffer entry = ByteBuffer.allocate(5 + compressed.length);
        entry.putInt(compressed.length + 1).put((byte) 2).put(compressed);
        return entry.array();
    }

    /** A region r.0.0.mca with the given payloads, one per slot starting at sector 2, plus extra header entries. */
    private static Path region(Path dir, byte[][] payloads, int[][] extraEntries) throws IOException {
        Path folder = Files.createDirectories(dir.resolve("region"));
        int sector = 2;
        ByteBuffer header = ByteBuffer.allocate(2 * SECTOR);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int slot = 0; slot < payloads.length; slot++) {
            byte[] p = payloads[slot];
            int count = (p.length + SECTOR - 1) / SECTOR;
            header.putInt(slot * 4, (sector << 8) | count);
            byte[] padded = new byte[count * SECTOR];
            System.arraycopy(p, 0, padded, 0, p.length);
            body.write(padded);
            sector += count;
        }
        for (int[] e : extraEntries) {
            header.putInt(e[0] * 4, (e[1] << 8) | e[2]);
        }
        Path file = folder.resolve("r.0.0.mca");
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(header.array());
        all.write(body.toByteArray());
        Files.write(file, all.toByteArray());
        return dir;
    }

    @Test
    void aHealthyRegionHasNoProblems(@TempDir Path dir) throws IOException {
        region(dir, new byte[][]{chunk(0, 0, true), chunk(1, 0, true), chunk(2, 0, true)}, new int[0][]);
        RegionCheck.Report report = RegionCheck.checkWorld(dir);
        assertTrue(report.problems().isEmpty(), report.describe());
        assertEquals(3, report.chunks);
    }

    @Test
    void findsEachKindOfDamage(@TempDir Path dir) throws IOException {
        byte[] empty = new byte[SECTOR]; // length 0: "allocated, but stream is missing"
        region(dir, new byte[][]{
                chunk(0, 0, true),      // slot 0: fine
                empty,                  // slot 1: empty stream
                chunk(5, 9, true),      // slot 2: stored position is not the slot's (2, 0)
                chunk(3, 0, false),     // slot 3: no Status
        }, new int[][]{
                {4, 2, 1},              // slot 4: overlaps slot 0
                {5, 1, 1},              // slot 5: points into the header
                {6, 900, 1},            // slot 6: past the end of the file
        });
        RegionCheck.Report report = RegionCheck.checkWorld(dir);
        String all = String.join("\n", report.problems());
        assertTrue(all.contains("slot 1: allocated but its stream is empty"), all);
        assertTrue(all.contains("slot 2: stored position [5, 9] is not the slot's [2, 0]"), all);
        assertTrue(all.contains("slot 3: no Status"), all);
        assertTrue(all.contains("slot 4: overlaps another entry"), all);
        assertTrue(all.contains("slot 5: points into the header"), all);
        assertTrue(all.contains("slot 6: ends past the end of the file"), all);
    }
}
