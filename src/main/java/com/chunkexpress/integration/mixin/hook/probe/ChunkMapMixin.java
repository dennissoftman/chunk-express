package com.chunkexpress.integration.mixin.hook.probe;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalLongRef;
import com.chunkexpress.integration.ProbeHooks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Observe-only probe on {@link ChunkMap}: times the server-thread part of saving a chunk, and the
 * full path from load request to a usable {@code ChunkAccess}.
 *
 * <p>Neither injector cancels, reads mutable state, or changes a return value. See
 * {@link ProbeHooks} for the contract.
 */
@Mixin(ChunkMap.class)
abstract class ChunkMapMixin {

    @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At("HEAD"))
    private void chunkexpress$saveStart(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir,
                                      @Share("chunkexpress$saveStart") LocalLongRef start) {
        start.set(System.nanoTime());
    }

    @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At("RETURN"))
    private void chunkexpress$saveEnd(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir,
                                    @Share("chunkexpress$saveStart") LocalLongRef start) {
        ProbeHooks.chunkSaveCall(System.nanoTime() - start.get(), cir.getReturnValueZ());
    }

    @Inject(method = "scheduleChunkLoad", at = @At("RETURN"))
    private void chunkexpress$loadScheduled(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        ProbeHooks.chunkLoadScheduled(cir.getReturnValue());
    }

    /** Stored chunk data without a "Status" tag: vanilla logs one line and regenerates the chunk. */
    @Inject(method = "isChunkDataValid", at = @At("RETURN"))
    private static void chunkexpress$dataValidated(CompoundTag tag, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            ProbeHooks.chunkDataWithoutStatus(tag);
        }
    }
}
