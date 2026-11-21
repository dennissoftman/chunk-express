package com.chunkexpress.integration.neoforge;

import com.chunkexpress.bootstrap.ChunkExpressRuntime;
import com.chunkexpress.util.Logs;
import com.chunkexpress.util.ProbeGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Feeds NeoForge's chunk events into the {@link com.chunkexpress.chunk.ChunkLifecycleTracker}.
 * This is the always-on baseline: it needs no mixins and keeps working when probes are disabled
 * for compatibility.
 *
 * <p>In singleplayer the client and the integrated server share one event bus, so every handler
 * first checks for a {@link ServerLevel} and ignores client-side events.
 *
 * <p>Where NeoForge 21.1 fires these (all on the server thread):
 * <ul>
 *   <li>{@code ChunkEvent.Load}: {@code ChunkStatusTasks.full}, when a chunk becomes a
 *       {@code LevelChunk}; {@code isNewChunk()} is true when it was not stored as a full chunk.</li>
 *   <li>{@code ChunkEvent.Unload}: {@code ChunkMap.scheduleUnload}, just before the final save.</li>
 *   <li>{@code ChunkDataEvent.Load}: {@code ChunkSerializer.read}.</li>
 *   <li>{@code ChunkDataEvent.Save}: {@code ChunkMap.save}, after serialisation.</li>
 * </ul>
 * Lowest priority, so we see each event after every other mod has handled it.
 */
public final class ChunkEventListener {
    private final ProbeGuard guard = new ProbeGuard("chunk-events");

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onChunkLoad(ChunkEvent.Load event) {
        ChunkExpressRuntime rt = ChunkExpressRuntime.active();
        if (rt == null || !this.guard.enabled()) {
            return;
        }
        try {
            if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
                rt.chunks().onLoad(dimensionId(level), chunk.getPos().toLong(), event.isNewChunk(), System.nanoTime());
            }
        } catch (Throwable t) {
            this.guard.failed(t);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onChunkUnload(ChunkEvent.Unload event) {
        ChunkExpressRuntime rt = ChunkExpressRuntime.active();
        if (rt == null || !this.guard.enabled()) {
            return;
        }
        try {
            if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
                rt.chunks().onUnload(dimensionId(level), chunk.getPos().toLong(), System.nanoTime());
            }
        } catch (Throwable t) {
            this.guard.failed(t);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onChunkDataLoad(ChunkDataEvent.Load event) {
        ChunkExpressRuntime rt = ChunkExpressRuntime.active();
        if (rt == null || !this.guard.enabled() || isClient(event.getLevel())) {
            return;
        }
        try {
            rt.chunks().onDataLoaded(dimensionIdOrUnknown(event.getLevel()));
        } catch (Throwable t) {
            this.guard.failed(t);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onChunkDataSave(ChunkDataEvent.Save event) {
        ChunkExpressRuntime rt = ChunkExpressRuntime.active();
        if (rt == null || !this.guard.enabled() || isClient(event.getLevel())) {
            return;
        }
        try {
            rt.chunks().onDataSaved(dimensionIdOrUnknown(event.getLevel()));
        } catch (Throwable t) {
            this.guard.failed(t);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onLevelUnload(LevelEvent.Unload event) {
        ChunkExpressRuntime rt = ChunkExpressRuntime.active();
        if (rt == null || !this.guard.enabled()) {
            return;
        }
        try {
            if (event.getLevel() instanceof ServerLevel level) {
                String dimension = dimensionId(level);
                int remaining = rt.chunks().onDimensionUnloaded(dimension);
                if (remaining > 0) {
                    Logs.LOG.info("Chunk Express: {} chunks in {} were still recorded as loaded when the level unloaded "
                            + "(no unload event seen for them).", remaining, dimension);
                }
            }
        } catch (Throwable t) {
            this.guard.failed(t);
        }
    }

    private static String dimensionId(ServerLevel level) {
        return level.dimension().location().toString();
    }

    /** Data events for proto chunks may carry no level; count them rather than drop them. */
    private static String dimensionIdOrUnknown(LevelAccessor level) {
        return level instanceof ServerLevel server ? dimensionId(server) : "(no level)";
    }

    private static boolean isClient(LevelAccessor level) {
        return level != null && level.isClientSide();
    }
}
