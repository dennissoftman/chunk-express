package com.chunkexpress.tools;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldDiffTest {

    private static CompoundTag state(String name, String... props) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        if (props.length > 0) {
            CompoundTag p = new CompoundTag();
            for (int i = 0; i < props.length; i += 2) {
                p.putString(props[i], props[i + 1]);
            }
            tag.put("Properties", p);
        }
        return tag;
    }

    /** Packs values the way vanilla does: entries never span two longs. */
    private static long[] pack(int[] values, int bits) {
        int perLong = 64 / bits;
        long[] data = new long[(values.length + perLong - 1) / perLong];
        for (int i = 0; i < values.length; i++) {
            data[i / perLong] |= (long) values[i] << ((i % perLong) * bits);
        }
        return data;
    }

    private static CompoundTag container(ListTag palette, int[] indices, int bits) {
        CompoundTag c = new CompoundTag();
        c.put("palette", palette);
        if (indices != null) {
            c.put("data", new LongArrayTag(pack(indices, bits)));
        }
        return c;
    }

    @Test
    void singleEntryPaletteNeedsNoData() {
        ListTag palette = new ListTag();
        palette.add(state("minecraft:stone"));
        String[] out = WorldDiff.decode(container(palette, null, 0), 4096, 4);
        assertEquals(4096, out.length);
        assertEquals("minecraft:stone", out[4095]);
    }

    @Test
    void decodesPackedBlockStatesAtSeveralWidths() {
        for (int paletteSize : new int[]{2, 17, 33, 300}) {
            ListTag palette = new ListTag();
            for (int i = 0; i < paletteSize; i++) {
                palette.add(state("minecraft:b" + i));
            }
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
            int[] indices = new int[4096];
            String[] expected = new String[4096];
            for (int i = 0; i < 4096; i++) {
                indices[i] = (i * 7) % paletteSize;
                expected[i] = "minecraft:b" + indices[i];
            }
            assertArrayEquals(expected, WorldDiff.decode(container(palette, indices, bits), 4096, 4), "palette " + paletteSize);
        }
    }

    @Test
    void decodesBiomesWithOneBitMinimum() {
        ListTag palette = new ListTag();
        palette.add(StringTag.valueOf("minecraft:plains"));
        palette.add(StringTag.valueOf("minecraft:forest"));
        int[] indices = new int[64];
        indices[63] = 1;
        String[] out = WorldDiff.decode(container(palette, indices, 1), 64, 1);
        assertEquals("minecraft:plains", out[0]);
        assertEquals("minecraft:forest", out[63]);
    }

    @Test
    void unexpectedLayoutIsReportedNotGuessed() {
        ListTag palette = new ListTag();
        palette.add(state("minecraft:a"));
        palette.add(state("minecraft:b"));
        CompoundTag c = new CompoundTag();
        c.put("palette", palette);
        c.put("data", new LongArrayTag(new long[3]));
        assertTrue(WorldDiff.decode(c, 4096, 4)[0].startsWith("undecodable"));
    }

    @Test
    void equalStatesCompareEqualWhateverThePropertyOrderOrPaletteOrder() {
        assertEquals(WorldDiff.describe(state("minecraft:oak_log", "axis", "y", "x", "1")),
                WorldDiff.describe(state("minecraft:oak_log", "x", "1", "axis", "y")));

        ListTag p1 = new ListTag();
        p1.add(state("minecraft:a"));
        p1.add(state("minecraft:b"));
        ListTag p2 = new ListTag();
        p2.add(state("minecraft:b"));
        p2.add(state("minecraft:a"));
        int[] i1 = new int[4096];
        int[] i2 = new int[4096];
        for (int i = 0; i < 4096; i++) {
            i1[i] = i % 2;
            i2[i] = 1 - i % 2;
        }
        CompoundTag sectionA = new CompoundTag();
        sectionA.putByte("Y", (byte) 0);
        sectionA.put("block_states", container(p1, i1, 4));
        CompoundTag sectionB = new CompoundTag();
        sectionB.putByte("Y", (byte) 0);
        sectionB.put("block_states", container(p2, i2, 4));
        CompoundTag chunkA = new CompoundTag();
        ListTag sa = new ListTag();
        sa.add(sectionA);
        chunkA.put("sections", sa);
        CompoundTag chunkB = new CompoundTag();
        ListTag sb = new ListTag();
        sb.add(sectionB);
        chunkB.put("sections", sb);
        assertEquals(0, WorldDiff.compareChunk(chunkA, chunkB).blocks, "same blocks under different palette order");

        i2[100] = 1 - i2[100];
        sectionB.put("block_states", container(p2, i2, 4));
        assertEquals(1, WorldDiff.compareChunk(chunkA, chunkB).blocks);
    }
}
