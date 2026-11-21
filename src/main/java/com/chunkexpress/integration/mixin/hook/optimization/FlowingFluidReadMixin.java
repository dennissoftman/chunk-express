package com.chunkexpress.integration.mixin.hook.optimization;

import com.chunkexpress.integration.generation.FluidSettling;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * O4: {@code FlowingFluid}'s block and fluid reads, by whichever type the call goes through; each read
 * of a position inside the chunk being settled is answered from that chunk ({@link FluidSettling}).
 * Every other read is the original call.
 */
@Mixin(FlowingFluid.class)
abstract class FlowingFluidReadMixin {
    private static final String BLOCK = "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String FLUID = "getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;";

    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;" + BLOCK))
    private BlockState chunkexpress$levelBlock(Level reader, BlockPos pos, Operation<BlockState> original) {
        return FluidSettling.blockState(reader, pos, original);
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelReader;" + BLOCK))
    private BlockState chunkexpress$readerBlock(LevelReader reader, BlockPos pos, Operation<BlockState> original) {
        return FluidSettling.blockState(reader, pos, original);
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;" + BLOCK))
    private BlockState chunkexpress$accessorBlock(LevelAccessor reader, BlockPos pos, Operation<BlockState> original) {
        return FluidSettling.blockState(reader, pos, original);
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/BlockGetter;" + BLOCK))
    private BlockState chunkexpress$getterBlock(BlockGetter reader, BlockPos pos, Operation<BlockState> original) {
        return FluidSettling.blockState(reader, pos, original);
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;" + FLUID))
    private FluidState chunkexpress$levelFluid(Level reader, BlockPos pos, Operation<FluidState> original) {
        return FluidSettling.fluidState(reader, pos, original);
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelReader;" + FLUID))
    private FluidState chunkexpress$readerFluid(LevelReader reader, BlockPos pos, Operation<FluidState> original) {
        return FluidSettling.fluidState(reader, pos, original);
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;" + FLUID))
    private FluidState chunkexpress$accessorFluid(LevelAccessor reader, BlockPos pos, Operation<FluidState> original) {
        return FluidSettling.fluidState(reader, pos, original);
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/BlockGetter;" + FLUID))
    private FluidState chunkexpress$getterFluid(BlockGetter reader, BlockPos pos, Operation<FluidState> original) {
        return FluidSettling.fluidState(reader, pos, original);
    }
}
