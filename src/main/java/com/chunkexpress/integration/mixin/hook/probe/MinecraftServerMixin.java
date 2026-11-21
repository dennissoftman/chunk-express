package com.chunkexpress.integration.mixin.hook.probe;

import com.chunkexpress.integration.ProbeHooks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observe-only probe on vanilla's chunk I/O error reporting. Every chunk, entity and POI load or
 * save failure in 1.21.1 is routed through these two methods (the integrated server overrides
 * them but calls {@code super}). Vanilla's logging and debug-report behaviour is unchanged.
 */
@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {

    @Inject(method = "reportChunkLoadFailure", at = @At("HEAD"))
    private void chunkexpress$loadFailure(Throwable error, RegionStorageInfo info, ChunkPos pos, CallbackInfo ci) {
        ProbeHooks.ioFailureReported(false, info, pos, error);
    }

    @Inject(method = "reportChunkSaveFailure", at = @At("HEAD"))
    private void chunkexpress$saveFailure(Throwable error, RegionStorageInfo info, ChunkPos pos, CallbackInfo ci) {
        ProbeHooks.ioFailureReported(true, info, pos, error);
    }
}
