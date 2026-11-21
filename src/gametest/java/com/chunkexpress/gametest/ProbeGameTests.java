package com.chunkexpress.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.chunkexpress.ChunkExpress;
import com.chunkexpress.bootstrap.ChunkExpressRuntime;
import com.chunkexpress.integration.ProbeRegistry;
import com.chunkexpress.integration.compatibility.CompatibilityReport;
import com.chunkexpress.integration.compatibility.KnownMods;
import com.chunkexpress.storage.StorageMonitor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * End-to-end checks that Chunk Express' hooks are attached to the real game and see real events.
 * These are what prove the mixin injection points still match vanilla (the injectors use
 * {@code defaultRequire = 0}, so a mismatch would otherwise be silent).
 *
 * <p>Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(ChunkExpress.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProbeGameTests {
    private static final String ARENA = "empty_9x6x9";

    private static ChunkExpressRuntime runtime(GameTestHelper helper) {
        ChunkExpressRuntime rt = ChunkExpressRuntime.current();
        helper.assertTrue(rt != null, "Chunk Express runtime was not created at server start");
        helper.assertTrue(rt.settings().instrumentation(), "instrumentation must be on for the game tests");
        return rt;
    }

    @GameTest(template = ARENA)
    public static void everyProbeMixinIsApplied(GameTestHelper helper) {
        runtime(helper);
        // Applied, except where an installed mod made the compatibility check switch it off
        // (Lithium: the chunk-wait probe). Run with only Chunk Express, that is every mixin.
        CompatibilityReport report = ProbeRegistry.earlyReport();
        helper.assertTrue(report != null, "the mixin plugin's compatibility report is missing");
        for (String probe : KnownMods.ALL_MIXINS) {
            ProbeRegistry.State state = ProbeRegistry.states().get(probe);
            ProbeRegistry.State expected = report.probeAllowed(probe) ? ProbeRegistry.State.APPLIED : ProbeRegistry.State.DISABLED;
            helper.assertTrue(state == expected, probe + " is " + state + ", expected " + expected);
        }
        helper.succeed();
    }

    /** The arena's chunk is loaded, so the lifecycle tracker must have seen its load event. */
    @GameTest(template = ARENA)
    public static void loadedChunkIsTracked(GameTestHelper helper) {
        ChunkExpressRuntime rt = runtime(helper);
        ServerLevel level = helper.getLevel();
        ChunkPos chunk = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        String dimension = level.dimension().location().toString();
        helper.assertTrue(rt.chunks().isLoaded(dimension, chunk.toLong()),
                "chunk " + chunk + " in " + dimension + " is loaded but not tracked");
        helper.succeed();
    }

    /**
     * Changing a block marks its chunk unsaved; a flushing save must then go through
     * ChunkMap.save (save-call probe) and IOWorker.store (write probe), and every write must finish.
     */
    @GameTest(template = ARENA, timeoutTicks = 200)
    public static void flushingSaveIsObservedEndToEnd(GameTestHelper helper) {
        ChunkExpressRuntime rt = runtime(helper);
        StorageMonitor.Snapshot before = rt.storage().snapshot();

        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        helper.getLevel().getChunkSource().save(true);

        helper.succeedWhen(() -> {
            StorageMonitor.Snapshot after = rt.storage().snapshot();
            helper.assertTrue(after.savesSerialized() > before.savesSerialized(), "no chunk serialisation observed");
            helper.assertTrue(after.type("chunk").writesCompleted() > before.type("chunk").writesCompleted(),
                    "no completed chunk write observed");
            helper.assertTrue(after.type("chunk").writesFailed() == before.type("chunk").writesFailed(),
                    "a chunk write failed");
            helper.assertTrue(after.type("chunk").pendingWrites() == 0, "chunk writes still pending after flush");
        });
    }

    /** The test world was generated at startup, so every vanilla stage must have been timed. */
    @GameTest(template = ARENA)
    public static void generationStagesAreTimed(GameTestHelper helper) {
        ChunkExpressRuntime rt = runtime(helper);
        java.util.Set<String> seen = new java.util.HashSet<>();
        rt.generation().snapshot().forEach(stage -> seen.add(stage.name()));
        for (String name : new String[]{"structure_starts", "biomes", "noise", "surface", "features", "light", "full"}) {
            String stage = "minecraft:" + name;
            helper.assertTrue(seen.contains(stage), "no timing for generation stage " + stage + " (saw " + seen + ")");
        }
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void ticksAreMeasured(GameTestHelper helper) {
        ChunkExpressRuntime rt = runtime(helper);
        helper.succeedWhen(() -> helper.assertTrue(rt.ticks().snapshot().totalTicks() > 0, "no ticks measured"));
    }

    /** The command is registered on the real dispatcher and every subcommand succeeds. */
    @GameTest(template = ARENA)
    public static void commandsRun(GameTestHelper helper) {
        runtime(helper);
        MinecraftServer server = helper.getLevel().getServer();
        CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
        for (String command : new String[]{"chunkexpress status", "chunkexpress compat"}) {
            try {
                int result = server.getCommands().getDispatcher().execute(command, source);
                helper.assertTrue(result == 1, "/" + command + " returned " + result);
            } catch (CommandSyntaxException e) {
                helper.fail("/" + command + " failed: " + e.getMessage());
            }
        }
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void noLifecycleAnomalies(GameTestHelper helper) {
        ChunkExpressRuntime rt = runtime(helper);
        long anomalies = rt.chunks().dimensions().stream().mapToLong(d -> d.anomalies()).sum();
        helper.assertTrue(anomalies == 0, anomalies + " impossible chunk transitions recorded");
        helper.assertTrue(rt.chunks().offThreadEvents() == 0, "chunk events seen off the server thread");
        helper.succeed();
    }
}
