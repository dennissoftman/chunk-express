package com.chunkexpress.gametest;

import com.chunkexpress.ChunkExpress;
import com.chunkexpress.bootstrap.ChunkExpressRuntime;
import com.chunkexpress.generation.StageOffloadPolicy;
import com.chunkexpress.integration.generation.StageOffload;
import com.chunkexpress.integration.generation.StageOffload.Stage;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** The stage offloads (O1 surface, O2 carvers) apply exactly where their policy allows, and chunks still generate. */
@GameTestHolder(ChunkExpress.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OptimizationGameTests {
    private static final String ARENA = "empty_9x6x9";

    @GameTest(template = ARENA, timeoutTicks = 600)
    public static void stagesAreOffloadedForVanillaNoiseGenerationOnly(GameTestHelper helper) {
        ChunkExpressRuntime rt = ChunkExpressRuntime.current();
        helper.assertTrue(rt != null && rt.settings().offloadSurface() && rt.settings().offloadCarvers(),
                "offloadSurface and offloadCarvers must be on (the defaults) here");

        // The Nether uses vanilla's noise generator: a new chunk there must have both stages run off the
        // lane - unless installed mods made the policy keep a stage vanilla (e.g. Lithostitched's surface
        // rules), in which case the stage must have stayed on the lane. Either way the chunk generates.
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "no nether on the test server");
        long surfaceBefore = StageOffload.offloaded(Stage.SURFACE);
        long carversBefore = StageOffload.offloaded(Stage.CARVERS);
        // The test world persists between runs: pick a position that has (almost certainly) never been generated.
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int x = 20_000 + random.nextInt(100_000);
        int z = -120_000 + random.nextInt(100_000);
        ChunkAccess chunk = nether.getChunk(x, z, ChunkStatus.FULL, true);
        helper.assertTrue(chunk != null && chunk.getPersistedStatus() == ChunkStatus.FULL, "nether chunk did not generate");
        Map<Stage, Long> before = Map.of(Stage.SURFACE, surfaceBefore, Stage.CARVERS, carversBefore);

        for (Stage stage : Stage.values()) {
            Map<ResourceKey<Level>, StageOffloadPolicy.Decision> decisions = StageOffload.decisions(stage);
            StageOffloadPolicy.Decision netherDecision = decisions.get(Level.NETHER);
            helper.assertTrue(netherDecision != null, stage + ": no nether decision");
            boolean ran = StageOffload.offloaded(stage) > before.get(stage);
            helper.assertTrue(ran == netherDecision.offload(),
                    stage + " nether: offloaded=" + ran + " but decision: " + netherDecision);
            if (!netherDecision.offload()) {
                // Only an installed mod may keep the Nether stage vanilla here, and it must say why.
                helper.assertTrue(netherDecision.reason().contains("mod-added types") || netherDecision.reason().contains("patch"),
                        stage + " nether kept vanilla without a mod reason: " + netherDecision);
            }
            // The test server's overworld is a flat world, whose generator is not vanilla's noise generator.
            StageOffloadPolicy.Decision overworld = decisions.get(Level.OVERWORLD);
            helper.assertTrue(overworld != null && !overworld.offload() && overworld.reason().contains("not vanilla"),
                    stage + " flat overworld decision: " + overworld);
        }
        helper.succeed();
    }

    /** The foreign-mixin gate must be able to see mixins at all: it has to find our own on ChunkStatusTasks. */
    @GameTest(template = ARENA)
    public static void mixinGateSeesMergedMixins(GameTestHelper helper) {
        java.util.Set<String> merged = StageOffload.mergedMixins(
                java.util.List.of("net.minecraft.world.level.chunk.status.ChunkStatusTasks"));
        helper.assertTrue(merged.contains("com.chunkexpress.integration.mixin.hook.optimization.StageOffloadMixin"),
                "mixin detection is blind: found " + merged);
        helper.succeed();
    }
}
