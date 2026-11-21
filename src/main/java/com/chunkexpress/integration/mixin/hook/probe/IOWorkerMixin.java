package com.chunkexpress.integration.mixin.hook.probe;

import com.chunkexpress.integration.ProbeHooks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Observe-only probe on vanilla's per-storage I/O worker (one each for chunks, entities and POI,
 * per dimension). Attaches completion observers to the futures {@code store} and
 * {@code loadAsync} return; never completes or replaces them.
 */
@Mixin(IOWorker.class)
abstract class IOWorkerMixin {

    @Inject(method = "store", at = @At("RETURN"))
    private void chunkexpress$storeSubmitted(ChunkPos pos, @Nullable CompoundTag data,
                                           CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        ProbeHooks.writeSubmitted(((IOWorker) (Object) this).storageInfo(), pos, data, cir.getReturnValue());
    }

    @Inject(method = "loadAsync", at = @At("RETURN"))
    private void chunkexpress$readSubmitted(ChunkPos pos,
                                          CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> cir) {
        ProbeHooks.readSubmitted(((IOWorker) (Object) this).storageInfo(), cir.getReturnValue());
    }
}
