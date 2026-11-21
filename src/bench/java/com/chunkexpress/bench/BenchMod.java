package com.chunkexpress.bench;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

/**
 * The benchmark mod. Client-only (the ride needs a player, a screen and frame times) and
 * deliberately independent of Chunk Express: it uses no Chunk Express class, so the same benchmark
 * runs on vanilla, Chunk Express, Moonrise or C2ME and the numbers are comparable.
 */
@Mod(value = BenchMod.MOD_ID, dist = Dist.CLIENT)
public final class BenchMod {
    public static final String MOD_ID = "chunkexpress_bench";

    public BenchMod() {
        IceRoadBench.register();
    }
}
