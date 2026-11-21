package com.chunkexpress;

import com.chunkexpress.bootstrap.ServerLifecycleListener;
import com.chunkexpress.config.ChunkExpressConfig;
import com.chunkexpress.integration.neoforge.ChunkEventListener;
import com.chunkexpress.integration.neoforge.ChunkExpressCommand;
import com.chunkexpress.util.Logs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Mod entry point. Registers the config, the game-bus listeners and the command; nothing else
 * happens until a server starts (see {@link ServerLifecycleListener}).
 *
 * <p>Chunk Express is server-side and runs the same code on dedicated and integrated servers. It has
 * no client code and registers no network payloads.
 */
@Mod(ChunkExpress.MOD_ID)
public final class ChunkExpress {
    public static final String MOD_ID = "chunkexpress";

    private static volatile String version = "unknown";

    public ChunkExpress(IEventBus modBus, ModContainer container) {
        version = container.getModInfo().getVersion().toString();
        container.registerConfig(ModConfig.Type.COMMON, ChunkExpressConfig.SPEC);

        NeoForge.EVENT_BUS.register(new ServerLifecycleListener());
        NeoForge.EVENT_BUS.register(new ChunkEventListener());
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> ChunkExpressCommand.register(event.getDispatcher()));

        Logs.LOG.info("Chunk Express {} loaded. Optimisations are listed in config/chunkexpress-common.toml under "
                + "[optimizations]; each falls back to vanilla on its own when unsafe, and /chunkexpress status says "
                + "which are active. Storage, loading and the world format are unchanged.", version);
    }

    public static String version() {
        return version;
    }
}
