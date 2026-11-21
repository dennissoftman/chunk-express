package com.chunkexpress.integration.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.chunkexpress.ChunkExpress;
import com.chunkexpress.bootstrap.ChunkExpressRuntime;
import com.chunkexpress.integration.ProbeRegistry;
import com.chunkexpress.metrics.StatusReport;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * {@code /chunkexpress status | compat | reset}. Operator-only (permission level 2): the output
 * describes server performance and storage health.
 *
 * <p>Commands execute on the server thread, which is what the status renderer requires.
 */
public final class ChunkExpressCommand {
    static final int PERMISSION_LEVEL = 2;

    private ChunkExpressCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(ChunkExpress.MOD_ID)
                .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("compat").executes(ctx -> compat(ctx.getSource())))
                .then(Commands.literal("reset").executes(ctx -> reset(ctx.getSource()))));
    }

    private static int status(CommandSourceStack source) {
        ChunkExpressRuntime rt = ChunkExpressRuntime.current();
        if (rt == null) {
            source.sendFailure(Component.literal("Chunk Express has no runtime for this server."));
            return 0;
        }
        send(source, StatusReport.render(rt, ChunkExpress.version(), ProbeRegistry.states(),
                        java.util.stream.Stream.concat(com.chunkexpress.integration.generation.StageOffload.statusLines().stream(), com.chunkexpress.integration.generation.FluidSettling.statusLines().stream()).toList()));
        return 1;
    }

    private static int compat(CommandSourceStack source) {
        ChunkExpressRuntime rt = ChunkExpressRuntime.current();
        if (rt == null) {
            source.sendFailure(Component.literal("Chunk Express has no runtime for this server."));
            return 0;
        }
        send(source, rt.compatibility().describe());
        return 1;
    }

    private static int reset(CommandSourceStack source) {
        ChunkExpressRuntime rt = ChunkExpressRuntime.current();
        if (rt == null) {
            source.sendFailure(Component.literal("Chunk Express has no runtime for this server."));
            return 0;
        }
        rt.resetCounters();
        source.sendSuccess(() -> Component.literal("Chunk Express counters reset (loaded-chunk set and pending writes kept)."), true);
        return 1;
    }

    private static void send(CommandSourceStack source, List<String> lines) {
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
    }
}
