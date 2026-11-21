package com.chunkexpress.integration.mixin.hook.probe;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.llamalad7.mixinextras.sugar.ref.LocalLongRef;
import com.chunkexpress.generation.StageHashes;
import com.chunkexpress.integration.ProbeHooks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Observe-only probe on {@link ChunkStep#apply}, the single place every generation stage of
 * 1.21.1 goes through. Uses the same "does this step really generate?" test as vanilla
 * (persisted status before the target) so pass-through steps for stored chunks are not counted.
 */
@Mixin(ChunkStep.class)
abstract class ChunkStepMixin {

    @Inject(method = "apply", at = @At("HEAD"))
    private void chunkexpress$stepStart(WorldGenContext context, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                      CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir,
                                      @Share("chunkexpress$start") LocalLongRef start,
                                      @Share("chunkexpress$generates") LocalBooleanRef generates) {
        ChunkStatus target = ((ChunkStep) (Object) this).targetStatus();
        generates.set(chunk.getPersistedStatus().isBefore(target));
        if (StageHashes.ENABLED && generates.get()) {
            ProbeHooks.stageInput(target, chunk);
        }
        start.set(System.nanoTime());
    }

    @Inject(method = "apply", at = @At("RETURN"))
    private void chunkexpress$stepReturned(WorldGenContext context, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                         CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir,
                                         @Share("chunkexpress$start") LocalLongRef start,
                                         @Share("chunkexpress$generates") LocalBooleanRef generates) {
        if (generates.get()) {
            ProbeHooks.generationStep(((ChunkStep) (Object) this).targetStatus(), start.get(), cir.getReturnValue());
        }
    }
}
