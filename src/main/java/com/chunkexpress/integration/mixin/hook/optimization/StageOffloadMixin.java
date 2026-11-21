package com.chunkexpress.integration.mixin.hook.optimization;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.chunkexpress.integration.generation.StageOffload;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;

import java.util.concurrent.CompletableFuture;

/**
 * Behaviour-changing mixin (an optimisation, not a probe). Wraps vanilla's surface and carvers stages so that
 * {@link StageOffload} can choose the thread they run on. The stages' code itself is vanilla's and
 * always runs exactly once, via {@code original.call}.
 *
 * <p>{@code @WrapMethod} composes with other mods' wrappers of the same method instead of replacing
 * them; if any other mod patches these code paths at all, {@link StageOffload} keeps the stage on
 * vanilla's lane.
 */
@Mixin(ChunkStatusTasks.class)
abstract class StageOffloadMixin {

    @WrapMethod(method = "generateSurface")
    private static CompletableFuture<ChunkAccess> chunkexpress$generateSurface(
            WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
            Operation<CompletableFuture<ChunkAccess>> original) {
        return StageOffload.generate(StageOffload.Stage.SURFACE, context, step, cache, chunk, original);
    }

    @WrapMethod(method = "generateCarvers")
    private static CompletableFuture<ChunkAccess> chunkexpress$generateCarvers(
            WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
            Operation<CompletableFuture<ChunkAccess>> original) {
        return StageOffload.generate(StageOffload.Stage.CARVERS, context, step, cache, chunk, original);
    }
}
