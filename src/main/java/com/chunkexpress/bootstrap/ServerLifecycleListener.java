package com.chunkexpress.bootstrap;

import com.chunkexpress.ChunkExpress;
import com.chunkexpress.config.ChunkExpressConfig;
import com.chunkexpress.config.Settings;
import com.chunkexpress.integration.ProbeRegistry;
import com.chunkexpress.integration.compatibility.CompatibilityManager;
import com.chunkexpress.integration.compatibility.CompatibilityReport;
import com.chunkexpress.integration.neoforge.InstalledMods;
import com.chunkexpress.metrics.StatusReport;
import com.chunkexpress.storage.StorageMonitor;
import com.chunkexpress.util.Logs;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Creates and retires the {@link ChunkExpressRuntime} for each server, and times server ticks.
 *
 * <p>Works the same for dedicated and integrated (singleplayer/LAN) servers: both fire these
 * events on the server thread. All handlers run on the server thread.
 */
public final class ServerLifecycleListener {

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onAboutToStart(ServerAboutToStartEvent event) {
        MinecraftServer server = event.getServer();
        Settings settings = ChunkExpressConfig.snapshot();
        CompatibilityReport compat = ProbeRegistry.earlyReport();
        if (compat == null) {
            // The mixin plugin's record is not visible; evaluate again so the report is still right.
            compat = CompatibilityManager.evaluate(InstalledMods.fromLoadingModList(),
                    System.getProperty(CompatibilityManager.DISABLE_PROBES_PROPERTY));
        }
        ChunkExpressRuntime runtime = new ChunkExpressRuntime(settings,
                server.isDedicatedServer() ? "dedicated" : server.isSingleplayer() ? "integrated" : "embedded",
                server.forceSynchronousWrites(),
                Thread.currentThread(),
                compat);
        ChunkExpressRuntime.install(runtime);
        Logs.LOG.info("Chunk Express {} starting (server: {}; optimisations: surface {}, carvers {}; instrumentation {}; "
                        + "region writes {}).",
                ChunkExpress.version(), runtime.serverKind(), settings.offloadSurface() ? "on" : "off",
                settings.offloadCarvers() ? "on" : "off", settings.instrumentation() ? "on" : "off",
                runtime.synchronousWrites() ? "synchronous (DSYNC)" : "OS-buffered");
        for (String line : compat.describe()) {
            Logs.LOG.info("Chunk Express compatibility: {}", line);
        }
    }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent event) {
        ChunkExpressRuntime rt = ChunkExpressRuntime.current();
        if (rt != null) {
            rt.moveTo(RuntimePhase.RUNNING);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onStopping(ServerStoppingEvent event) {
        ChunkExpressRuntime rt = ChunkExpressRuntime.current();
        if (rt != null) {
            rt.moveTo(RuntimePhase.STOPPING);
        }
    }

    /**
     * Runs after vanilla's {@code stopServer()} has saved every level and closed every I/O worker,
     * so any write still pending now was never completed by vanilla.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onStopped(ServerStoppedEvent event) {
        ChunkExpressRuntime rt = ChunkExpressRuntime.current();
        if (rt == null) {
            return;
        }
        try {
            RuntimePhase previous = rt.moveTo(RuntimePhase.STOPPED);
            if (previous != RuntimePhase.STOPPING) {
                Logs.LOG.warn("Chunk Express: the server stopped without a normal shutdown (last phase {}). "
                        + "It probably crashed; check the crash report.", previous);
            }
            StorageMonitor.Snapshot storage = rt.storage().snapshot();
            long pending = storage.pendingWrites();
            if (pending > 0) {
                Logs.LOG.warn("Chunk Express: {} region writes were submitted to vanilla's I/O workers but never finished "
                        + "before shutdown completed. Vanilla drops tasks submitted after a worker closes; the chunks "
                        + "involved may hold older data on the next start.", pending);
            } else if (rt.settings().instrumentation()) {
                Logs.LOG.info("Chunk Express: shutdown check passed - every observed region write finished before the "
                        + "server stopped, and vanilla flushed region files to disk when it closed them.");
            }
            if (rt.settings().logSessionSummary()) {
                for (String line : StatusReport.render(rt, ChunkExpress.version(), ProbeRegistry.states(),
                        com.chunkexpress.integration.generation.StageOffload.statusLines())) {
                    Logs.LOG.info("[summary] {}", line);
                }
            }
        } finally {
            ChunkExpressRuntime.uninstall(rt);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onTickStart(ServerTickEvent.Pre event) {
        ChunkExpressRuntime rt = ChunkExpressRuntime.active();
        if (rt != null) {
            rt.ticks().tickStarted(System.nanoTime());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onTickEnd(ServerTickEvent.Post event) {
        ChunkExpressRuntime rt = ChunkExpressRuntime.active();
        if (rt != null) {
            rt.ticks().tickEnded(System.nanoTime());
        }
    }
}
