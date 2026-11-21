package com.chunkexpress.integration.generation;

import com.chunkexpress.bootstrap.ChunkExpressRuntime;
import com.chunkexpress.generation.ReviewedPatches;
import com.chunkexpress.generation.StageHashes;
import com.chunkexpress.generation.StageOffloadPolicy;
import com.chunkexpress.integration.ProbeHooks;
import com.chunkexpress.util.Logs;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * O4: fast fluid settling ({@code fastFluidSettling}). When a newly generated chunk becomes a full
 * chunk, vanilla settles the fluids generation marked ({@code LevelChunk.postProcessGeneration} ->
 * {@code FluidState.tick} -> {@code FlowingFluid.spread/getSpread/getNewLiquid}). Profiling showed
 * that most of that time is {@code Level.getBlockState/getFluidState} looking up the chunk for every
 * block read, while nearly all reads are of blocks in the chunk being settled.
 *
 * <p>While a chunk is settled, on the thread settling it, {@code FlowingFluid}'s reads of positions
 * inside that chunk go straight to the chunk. This is exactly what {@code Level.getBlockState(pos)}
 * does - outside the build height void air (empty fluid), otherwise the chunk at that position's
 * {@code getBlockState(pos)} - minus the lookup, so the result is identical. Reads of other positions,
 * other threads and all other code are unchanged.
 *
 * <p>Gate (per server): the config switch; no unreviewed mixins from other mods on
 * {@code FlowingFluid}, {@code WaterFluid}, {@code LavaFluid}, {@code FluidState} or {@code LevelChunk};
 * and no mod overriding {@code getBlockState/getFluidState/getChunk/getChunkAt} on {@code Level} or
 * {@code ServerLevel} (such a mod could make the level answer differently from the chunk).
 */
public final class FluidSettling {
    static final List<String> CLASSES = List.of(
            "net.minecraft.world.level.material.FlowingFluid",
            "net.minecraft.world.level.material.WaterFluid",
            "net.minecraft.world.level.material.LavaFluid",
            "net.minecraft.world.level.material.FluidState",
            "net.minecraft.world.level.chunk.LevelChunk");
    private static final List<String> LEVEL_CLASSES = List.of(
            "net.minecraft.world.level.Level",
            "net.minecraft.server.level.ServerLevel");
    private static final List<String> LEVEL_METHODS = List.of("getBlockState", "getFluidState", "getChunk", "getChunkAt");
    private static final String HASH_KEY = "minecraft:postprocess_out";

    // The chunk being settled, and by which thread. Written and read only by that (server) thread.
    private static LevelChunk chunk;
    private static Level level;
    private static Thread thread;
    private static int chunkX;
    private static int chunkZ;

    private static volatile ChunkExpressRuntime decidedFor;
    private static volatile StageOffloadPolicy.Decision decision;
    /**
     * Debug only: {@code -Dchunkexpress.debug.verifyFluidReads=true} answers every shortcut read the
     * vanilla way too, compares the two, logs mismatches and keeps vanilla's answer.
     */
    private static final boolean VERIFY = Boolean.getBoolean("chunkexpress.debug.verifyFluidReads");
    private static final LongAdder checked = new LongAdder();
    private static final LongAdder mismatches = new LongAdder();
    private static final LongAdder fast = new LongAdder();
    private static final LongAdder fastNanos = new LongAdder();
    private static final LongAdder vanillaNanos = new LongAdder();
    private static final LongAdder vanilla = new LongAdder();

    private FluidSettling() {
    }

    /** Wraps {@code LevelChunk.postProcessGeneration}. */
    public static void settle(LevelChunk settling, Operation<Void> original) {
        if (!decision().offload() || chunk != null) {
            vanilla.increment();
            long start = System.nanoTime();
            original.call();
            vanillaNanos.add(System.nanoTime() - start);
            hash(settling);
            return;
        }
        fast.increment();
        chunk = settling;
        level = settling.getLevel();
        thread = Thread.currentThread();
        chunkX = settling.getPos().x;
        chunkZ = settling.getPos().z;
        long start = System.nanoTime();
        try {
            original.call();
        } finally {
            fastNanos.add(System.nanoTime() - start);
            chunk = null;
            level = null;
            thread = null;
        }
        hash(settling);
    }

    /** Replaces {@code getBlockState(pos)} calls in {@code FlowingFluid}. */
    public static BlockState blockState(Object reader, BlockPos pos, Operation<BlockState> original) {
        LevelChunk settling = chunk;
        if (settling != null && reader == level && Thread.currentThread() == thread
                && (pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ && !level.isOutsideBuildHeight(pos)) {
            BlockState fast = settling.getBlockState(pos);
            if (VERIFY) {
                BlockState expected = original.call(reader, pos);
                checked.increment();
                if (expected != fast) {
                    mismatch(pos, expected, fast);
                }
                return expected;
            }
            return fast;
        }
        return original.call(reader, pos);
    }

    /** Replaces {@code getFluidState(pos)} calls in {@code FlowingFluid}. */
    public static FluidState fluidState(Object reader, BlockPos pos, Operation<FluidState> original) {
        LevelChunk settling = chunk;
        if (settling != null && reader == level && Thread.currentThread() == thread
                && (pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ && !level.isOutsideBuildHeight(pos)) {
            FluidState fast = settling.getFluidState(pos);
            if (VERIFY) {
                FluidState expected = original.call(reader, pos);
                checked.increment();
                if (expected != fast) {
                    mismatch(pos, expected, fast);
                }
                return expected;
            }
            return fast;
        }
        return original.call(reader, pos);
    }

    private static void mismatch(BlockPos pos, Object expected, Object fast) {
        mismatches.increment();
        if (mismatches.sum() <= 20) {
            Logs.LOG.error("Chunk Express fluid-settling verify: MISMATCH at {}: level says {}, chunk says {}", pos, expected, fast);
        }
    }

    private static void hash(LevelChunk settled) {
        if (StageHashes.ENABLED && StageHashes.wanted(HASH_KEY)) {
            ProbeHooks.hashChunk(HASH_KEY, settled);
        }
    }

    private static StageOffloadPolicy.Decision decision() {
        ChunkExpressRuntime rt = ChunkExpressRuntime.current();
        StageOffloadPolicy.Decision d = decision;
        if (rt == decidedFor && d != null) {
            return d;
        }
        synchronized (FluidSettling.class) {
            if (rt != decidedFor || decision == null) {
                fast.reset();
                vanilla.reset();
                fastNanos.reset();
                vanillaNanos.reset();
                decision = decide(rt);
                decidedFor = rt;
                Logs.LOG.info("Chunk Express: fluid settling {}: {}", decision.offload() ? "fast" : "vanilla", decision.reason());
            }
            return decision;
        }
    }

    private static StageOffloadPolicy.Decision decide(ChunkExpressRuntime rt) {
        if (rt == null) {
            return new StageOffloadPolicy.Decision(false, "Chunk Express runtime not started");
        }
        if (!rt.settings().fastFluidSettling()) {
            return new StageOffloadPolicy.Decision(false, "disabled in config (optimizations.fastFluidSettling = false)");
        }
        Map<String, String> versions = new HashMap<>();
        net.neoforged.fml.ModList.get().getMods().forEach(m -> versions.put(m.getModId(), m.getVersion().toString()));
        ReviewedPatches.Split mixins = ReviewedPatches.split(StageOffload.foreignMixins(CLASSES), versions);
        if (!mixins.unreviewed().isEmpty()) {
            return new StageOffloadPolicy.Decision(false, "other mods patch the fluid code: " + String.join(", ", mixins.unreviewed()));
        }
        List<String> overrides = levelOverrides();
        if (!overrides.isEmpty()) {
            return new StageOffloadPolicy.Decision(false, "other mods change how the level reads blocks: " + String.join(", ", overrides));
        }
        return new StageOffloadPolicy.Decision(true, mixins.reviewed().isEmpty()
                ? "no other mod patches the fluid code or the level's block reads"
                : "other mods' patches to the fluid code were reviewed as safe: " + String.join(", ", mixins.reviewed()));
    }

    /** Other mods' mixins that merged a block/fluid/chunk read method into Level or ServerLevel. */
    private static List<String> levelOverrides() {
        List<String> found = new ArrayList<>();
        for (String className : LEVEL_CLASSES) {
            try {
                Class<?> target = Class.forName(className, false, FluidSettling.class.getClassLoader());
                for (java.lang.reflect.Method method : target.getDeclaredMethods()) {
                    MixinMerged merged = method.getAnnotation(MixinMerged.class);
                    if (merged != null && !merged.mixin().startsWith("com.chunkexpress.")
                            && LEVEL_METHODS.contains(method.getName())) {
                        found.add(merged.mixin() + " (" + className.substring(className.lastIndexOf('.') + 1) + "." + method.getName() + ")");
                    }
                }
            } catch (RuntimeException | LinkageError | ClassNotFoundException e) {
                found.add("unknown (" + e.getClass().getSimpleName() + " while inspecting " + className + ")");
            }
        }
        return found;
    }

    /** For {@code /chunkexpress status}. */
    public static List<String> statusLines() {
        StageOffloadPolicy.Decision d = decision;
        if (d == null || decidedFor != ChunkExpressRuntime.current()) {
            return List.of("fluid settling: no chunks settled yet on this server");
        }
        String verify = VERIFY ? " | verify: " + checked.sum() + " reads compared, " + mismatches.sum() + " mismatches" : "";
        long n = fast.sum() + vanilla.sum();
        String time = n == 0 ? "" : String.format(java.util.Locale.ROOT, " (%.1f ms settling in all, %.3f ms per chunk)",
                (fastNanos.sum() + vanillaNanos.sum()) / 1e6, (fastNanos.sum() + vanillaNanos.sum()) / 1e6 / n);
        return List.of("fluid settling: " + fast.sum() + " chunks fast, " + vanilla.sum() + " vanilla" + time + " - "
                + (d.offload() ? "fast" : "vanilla") + ": " + d.reason() + verify);
    }
}
