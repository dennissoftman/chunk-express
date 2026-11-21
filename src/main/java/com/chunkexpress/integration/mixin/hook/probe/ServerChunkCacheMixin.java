package com.chunkexpress.integration.mixin.hook.probe;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.llamalad7.mixinextras.sugar.ref.LocalLongRef;
import com.chunkexpress.integration.ProbeHooks;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Observe-only probe on {@code ServerChunkCache.getChunk}: times the {@code managedBlock} call
 * where the server thread waits for a chunk that is not ready, and counts calls made from other
 * threads. Never changes an argument or a result.
 */
@Mixin(ServerChunkCache.class)
abstract class ServerChunkCacheMixin {
    private static final String GET_CHUNK =
            "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;";
    private static final String MANAGED_BLOCK =
            "Lnet/minecraft/server/level/ServerChunkCache$MainThreadExecutor;managedBlock(Ljava/util/function/BooleanSupplier;)V";
    private static final String GET_FUTURE =
            "Lnet/minecraft/server/level/ServerChunkCache;getChunkFutureMainThread(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;";

    @Shadow
    @Final
    Thread mainThread;

    @Inject(method = GET_CHUNK, at = @At("HEAD"))
    private void chunkexpress$getChunkCalled(int x, int z, ChunkStatus status, boolean require,
                                           CallbackInfoReturnable<ChunkAccess> cir) {
        if (Thread.currentThread() != this.mainThread) {
            ProbeHooks.offThreadGetChunk();
        }
    }

    /**
     * Notes whether the future the server thread is about to wait on is already done: only a
     * future that is not done makes it wait. Reads the future as the return value of
     * {@code getChunkFutureMainThread} rather than as a captured local, so a mod that replaces
     * {@code getChunk} (Lithium does) leaves this hook with nothing to match instead of failing
     * to load; the wait timing then simply records nothing.
     */
    @ModifyExpressionValue(method = GET_CHUNK, at = @At(value = "INVOKE", target = GET_FUTURE))
    private CompletableFuture<ChunkResult<ChunkAccess>> chunkexpress$waitStart(
            CompletableFuture<ChunkResult<ChunkAccess>> future,
            @Share("chunkexpress$waiting") LocalBooleanRef waiting,
            @Share("chunkexpress$waitStart") LocalLongRef start) {
        waiting.set(!future.isDone());
        start.set(System.nanoTime());
        return future;
    }

    @Inject(method = GET_CHUNK, at = @At(value = "INVOKE", target = MANAGED_BLOCK, shift = At.Shift.AFTER))
    private void chunkexpress$waitEnd(int x, int z, ChunkStatus status, boolean require,
                                    CallbackInfoReturnable<ChunkAccess> cir,
                                    @Share("chunkexpress$waiting") LocalBooleanRef waiting,
                                    @Share("chunkexpress$waitStart") LocalLongRef start) {
        // Unset (false) when a replaced getChunk calls managedBlock without getChunkFutureMainThread.
        if (waiting.get()) {
            ProbeHooks.serverThreadChunkWait(System.nanoTime() - start.get());
        }
    }
}
