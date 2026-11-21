package com.chunkexpress.integration.mixin.hook.optimization;

import com.chunkexpress.integration.generation.FluidSettling;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;

/** O4: marks the chunk being settled (see {@link FluidSettling}); the settling itself is vanilla's. */
@Mixin(LevelChunk.class)
abstract class FluidSettlingMixin {
    @WrapMethod(method = "postProcessGeneration")
    private void chunkexpress$settle(Operation<Void> original) {
        FluidSettling.settle((LevelChunk) (Object) this, original);
    }
}
