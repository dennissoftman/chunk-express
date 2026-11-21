package com.chunkexpress.gametest;

import com.chunkexpress.ChunkExpress;
import com.chunkexpress.bootstrap.ChunkExpressRuntime;
import com.chunkexpress.storage.FailureLog;
import net.minecraft.SharedConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Damages region data on purpose and checks what vanilla and Chunk Express do with it. Every test
 * works on a region file in a fresh temporary directory, never on the test world.
 */
@GameTestHolder(ChunkExpress.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FaultInjectionGameTests {
    private static final String ARENA = "empty_9x6x9";
    private static final String TEST_STORAGE = "chunkexpress-fault-test";

    /**
     * An entry whose stored length is zero ("allocated, but stream is missing"): vanilla returns
     * null, exactly as for a missing chunk, and Chunk Express must record it as damage.
     */
    @GameTest(template = ARENA)
    public static void damagedRegionEntryIsReportedNotTreatedAsAbsent(GameTestHelper helper) {
        ChunkExpressRuntime rt = ChunkExpressRuntime.current();
        helper.assertTrue(rt != null, "Chunk Express runtime missing");
        RegionStorageInfo info = new RegionStorageInfo("chunkexpress-fault-test", Level.OVERWORLD, TEST_STORAGE);
        ChunkPos pos = new ChunkPos(3, 4);
        Path dir = null;
        try {
            dir = Files.createTempDirectory("chunkexpress-fault");
            Path file = dir.resolve("r.0.0.mca");

            try (RegionFile region = new RegionFile(info, file, dir, false)) {
                CompoundTag tag = new CompoundTag();
                tag.putString("Status", "minecraft:full");
                try (DataOutputStream out = region.getChunkDataOutputStream(pos)) {
                    NbtIo.write(tag, out);
                }
            }

            damageStoredLength(file, pos);

            try (RegionFile region = new RegionFile(info, file, dir, false)) {
                helper.assertTrue(region.hasChunk(pos), "header lost the entry; the damage was not what this test expects");
                helper.assertTrue(region.getChunkDataInputStream(pos) == null, "vanilla unexpectedly read the damaged entry");
            }

            boolean recorded = rt.storage().failures().recent().stream().anyMatch(f ->
                    f.kind() == FailureLog.Kind.LOAD && f.storageType().equals(TEST_STORAGE)
                            && f.chunkX() == pos.x && f.chunkZ() == pos.z);
            helper.assertTrue(recorded, "Chunk Express did not record the damaged entry");
            helper.succeed();
        } catch (IOException e) {
            helper.fail("I/O error while setting up the damaged region file: " + e);
        } finally {
            deleteQuietly(dir);
        }
    }

    /**
     * A header entry pointing into the header itself: vanilla zeroes it in memory when the file is
     * opened (the chunk is now "absent"), and Chunk Express must report the discarded entry.
     */
    @GameTest(template = ARENA)
    public static void discardedHeaderEntryIsReported(GameTestHelper helper) {
        ChunkExpressRuntime rt = ChunkExpressRuntime.current();
        helper.assertTrue(rt != null, "Chunk Express runtime missing");
        RegionStorageInfo info = new RegionStorageInfo("chunkexpress-fault-test", Level.OVERWORLD, TEST_STORAGE + "-header");
        ChunkPos pos = new ChunkPos(5, 6);
        Path dir = null;
        try {
            dir = Files.createTempDirectory("chunkexpress-fault");
            Path file = dir.resolve("r.0.0.mca");
            try (RegionFile region = new RegionFile(info, file, dir, false)) {
                try (DataOutputStream out = region.getChunkDataOutputStream(pos)) {
                    NbtIo.write(new CompoundTag(), out);
                }
            }

            // Header entry: sector 1, one sector long. Sectors 0 and 1 are the header itself.
            int index = pos.getRegionLocalX() + pos.getRegionLocalZ() * 32;
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.allocate(4).putInt(0, (1 << 8) | 1), index * 4L);
            }

            try (RegionFile region = new RegionFile(info, file, dir, false)) {
                helper.assertFalse(region.hasChunk(pos), "vanilla kept an entry that overlaps the header");
            }
            boolean recorded = rt.storage().failures().recent().stream().anyMatch(f ->
                    f.storageType().equals(TEST_STORAGE + "-header") && f.error().startsWith("1 invalid header entries"));
            helper.assertTrue(recorded, "Chunk Express did not record the discarded header entry");
            helper.succeed();
        } catch (IOException e) {
            helper.fail("I/O error while setting up the damaged region file: " + e);
        } finally {
            deleteQuietly(dir);
        }
    }

    /**
     * Stored chunk data without a "Status" tag, written into the (throwaway) test world far from
     * everything: vanilla regenerates the chunk, and Chunk Express must report it.
     */
    @GameTest(template = ARENA, timeoutTicks = 400)
    public static void chunkDataWithoutStatusIsReported(GameTestHelper helper) {
        ChunkExpressRuntime rt = ChunkExpressRuntime.current();
        helper.assertTrue(rt != null, "Chunk Express runtime missing");
        ServerLevel level = helper.getLevel();
        ChunkPos pos = new ChunkPos(31_000, 31_000);
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        tag.putInt("xPos", pos.x);
        tag.putInt("zPos", pos.z);
        level.getChunkSource().chunkMap.write(pos, tag).join();
        level.getChunkSource().getChunkFuture(pos.x, pos.z, ChunkStatus.EMPTY, true);

        helper.succeedWhen(() -> helper.assertTrue(rt.storage().failures().recent().stream().anyMatch(f ->
                        f.chunkX() == pos.x && f.chunkZ() == pos.z && f.error().contains("no Status tag")),
                "Chunk Express did not record chunk data without a Status tag"));
    }

    /** Sets the 4-byte length prefix of the chunk's stored stream to zero. */
    private static void damageStoredLength(Path file, ChunkPos pos) throws IOException {
        int index = pos.getRegionLocalX() + pos.getRegionLocalZ() * 32;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer entry = ByteBuffer.allocate(4);
            channel.read(entry, index * 4L);
            entry.flip();
            int sector = entry.getInt() >>> 8;
            channel.write(ByteBuffer.allocate(4), sector * 4096L);
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // temporary directory; the OS cleans it up eventually
        }
    }
}
