package com.chunkexpress.integration.generation;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.chunkexpress.bootstrap.ChunkExpressRuntime;
import com.chunkexpress.config.Settings;
import com.chunkexpress.generation.ReviewedPatches;
import com.chunkexpress.generation.RuleTypes;
import com.chunkexpress.generation.StageHashes;
import com.chunkexpress.generation.StageOffloadPolicy;
import com.chunkexpress.integration.ProbeHooks;
import com.chunkexpress.util.Logs;
import net.minecraft.Util;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.neoforged.fml.ModList;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;

/**
 * Runs chosen vanilla generation stages on the worker pool instead of the dimension's serial
 * worldgen lane, when {@link StageOffloadPolicy} says it is safe: O1 (surface) and O2 (carvers).
 * The stages' own code is vanilla's, unchanged: this class only chooses the thread, by calling the
 * original method inside {@code CompletableFuture.supplyAsync(..., Util.backgroundExecutor())} - the
 * same construction vanilla uses for the noise stage.
 *
 * <p>Decisions are made once per stage, dimension and server, and logged with their reason.
 *
 * <p>Warm-up: on each server, the first chunk of a stage that may be offloaded still runs on the
 * worldgen lane, and offloading starts only once it has finished. Code in a stage may fill lazy
 * caches on first use (Lithostitched's band arrays, see {@link ReviewedPatches}); after the warm-up
 * those caches are filled, and the volatile {@code warmed} flag publishes them to every worker task
 * submitted afterwards.
 *
 * <p>Also the measuring point for stage-output hashes ({@link StageHashes}, key {@code <stage>_out}):
 * right after the stage's code returns, on the thread that ran it, before anything else can touch
 * the chunk.
 *
 * <p>Thread safety: {@link #generate} is called on the worldgen lane of each dimension (several
 * dimensions may call it at once); state is in concurrent maps and adders.
 */
public final class StageOffload {
    private static final String OWN_MIXIN_PACKAGE = "com.chunkexpress.";

    private static final List<String> SURFACE_CLASSES = List.of(
            "net.minecraft.world.level.chunk.status.ChunkStatusTasks",
            "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator",
            "net.minecraft.world.level.levelgen.SurfaceSystem",
            "net.minecraft.world.level.levelgen.SurfaceRules",
            "net.minecraft.world.level.levelgen.SurfaceRules$Context",
            "net.minecraft.world.level.levelgen.WorldGenerationContext");

    /**
     * Carvers also run surface rules (to pick top materials), so they inherit the surface gates.
     * Deliberately not listed: {@code ChunkAccess} and {@code ChunkGenerator}, which many unrelated
     * mods patch. The carvers code that runs is {@code NoiseBasedChunkGenerator.applyCarvers} (listed)
     * plus block writes into the chunk, which vanilla's off-lane noise stage already performs.
     */
    private static final List<String> CARVER_CLASSES = concat(SURFACE_CLASSES, List.of(
            "net.minecraft.world.level.levelgen.carver.CarvingContext",
            "net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver",
            "net.minecraft.world.level.levelgen.carver.WorldCarver",
            "net.minecraft.world.level.levelgen.carver.CaveWorldCarver",
            "net.minecraft.world.level.levelgen.carver.CanyonWorldCarver",
            "net.minecraft.world.level.levelgen.carver.NetherWorldCarver"));

    public enum Stage {
        // Types are checked as used by each dimension: surface rule and condition types in its surface
        // rule (both stages run surface rules), and for carvers the carver types of its biomes. The
        // registries listed here (none at present) would be checked whole: any mod-added entry counts.
        SURFACE("surface", "offloadSurface", "wgen_surface", Settings::offloadSurface, SURFACE_CLASSES, List.of()),
        CARVERS("carvers", "offloadCarvers", "wgen_carvers", Settings::offloadCarvers, CARVER_CLASSES, List.of()),
        // O3. Types are checked as used: every type inside the structures the dimension can place
        // (structure, height providers, pool aliases, ...), its placement types, and the element types
        // of every template pool (jigsaw assembly can reach any pool). Piece types are created by code,
        // not data, so any mod-added one counts. Processor types are not counted: vanilla runs
        // processors only when a template is placed (features stage).
        STRUCTURE_STARTS("structure_starts", "offloadStructureStarts", "wgen_structure_starts",
                Settings::offloadStructureStarts, StructureClasses.ALL,
                List.of(BuiltInRegistries.STRUCTURE_PIECE));

        final String name;
        final String configKey;
        final String threadTaskName;
        final Predicate<Settings> enabled;
        final List<String> classes;
        final List<Registry<?>> registries;
        final String outputHashKey;
        final Map<ResourceKey<Level>, StageOffloadPolicy.Decision> decisions = new ConcurrentHashMap<>();
        final LongAdder offloaded = new LongAdder();
        final LongAdder inline = new LongAdder();
        /** Set once a chunk has run this stage inline while offloading was allowed: see the class comment. */
        volatile boolean warmed;

        Stage(String name, String configKey, String threadTaskName, Predicate<Settings> enabled, List<String> classes,
              List<Registry<?>> registries) {
            this.name = name;
            this.configKey = configKey;
            this.threadTaskName = threadTaskName;
            this.enabled = enabled;
            this.classes = classes;
            this.registries = registries;
            this.outputHashKey = "minecraft:" + name + "_out";
        }
    }

    private static volatile ChunkExpressRuntime decidedFor;

    /**
     * Debug only (docs/RELEASE_CRITERIA.md B6): "-Dchunkexpress.debug.failStage=surface@x,z" makes the
     * offloaded stage throw for that one chunk, to check that a failure on a worker thread ends in
     * vanilla's crash report rather than a hang. Unset in normal use.
     */
    private static final String FAIL_STAGE = System.getProperty("chunkexpress.debug.failStage", "");

    private StageOffload() {
    }

    public static CompletableFuture<ChunkAccess> generate(Stage stage, WorldGenContext context, ChunkStep step,
                                                          StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                                          Operation<CompletableFuture<ChunkAccess>> original) {
        boolean offload = decision(stage, context).offload();
        if (!offload || !stage.warmed) {
            stage.inline.increment();
            CompletableFuture<ChunkAccess> result = original.call(context, step, cache, chunk);
            hashOutput(stage, context, chunk);
            if (offload) {
                stage.warmed = true; // the stage's code has now run once, to completion, on this thread
            }
            return result;
        }
        stage.offloaded.increment();
        // The original returns an already-completed future after doing the work on the calling
        // thread; here that thread is a worker. Exceptions complete the future exceptionally and
        // reach vanilla's handler in GenerationChunkHolder.applyStep, as for the noise stage.
        return CompletableFuture.supplyAsync(Util.wrapThreadWithTaskName(stage.threadTaskName, () -> {
            if (!FAIL_STAGE.isEmpty() && FAIL_STAGE.equals(stage.name + "@" + chunk.getPos().x + "," + chunk.getPos().z)) {
                throw new IllegalStateException("Chunk Express fault injection (chunkexpress.debug.failStage=" + FAIL_STAGE + ")");
            }
            ChunkAccess done = original.call(context, step, cache, chunk).join();
            hashOutput(stage, context, chunk);
            return done;
        }), Util.backgroundExecutor());
    }

    private static void hashOutput(Stage stage, WorldGenContext context, ChunkAccess chunk) {
        if (StageHashes.ENABLED && StageHashes.wanted(stage.outputHashKey)) {
            if (stage == Stage.STRUCTURE_STARTS) {
                hashStructureStarts(stage.outputHashKey, context, chunk); // produces structure data, not blocks
            } else {
                ProbeHooks.hashChunk(stage.outputHashKey, chunk);
            }
        }
    }

    /**
     * Hash of a chunk's structure starts: each start's structure id and bounding box, and each piece's
     * type, bounding box, depth, orientation and (jigsaw pieces) pool element, in a fixed order.
     */
    private static void hashStructureStarts(String key, WorldGenContext context, ChunkAccess chunk) {
        var registry = context.level().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        // Pool elements are hashed by their codec encoding (registry ids), not toString(): some elements
        // print objects with identity hash codes, which differ from run to run.
        var ops = net.minecraft.resources.RegistryOps.create(JsonOps.INSTANCE, context.level().registryAccess());
        List<Map.Entry<String, net.minecraft.world.level.levelgen.structure.StructureStart>> starts = new ArrayList<>();
        chunk.getAllStarts().forEach((structure, start) -> starts.add(Map.entry(String.valueOf(registry.getKey(structure)), start)));
        starts.sort(Map.Entry.comparingByKey());
        long hash = StageHashes.SEED;
        for (var entry : starts) {
            hash = mixText(hash, entry.getKey());
            hash = mixBox(hash, entry.getValue().getBoundingBox());
            for (var piece : entry.getValue().getPieces()) {
                hash = mixText(hash, String.valueOf(BuiltInRegistries.STRUCTURE_PIECE.getKey(piece.getType())));
                hash = mixBox(hash, piece.getBoundingBox());
                hash = StageHashes.mix(hash, piece.getGenDepth());
                hash = StageHashes.mix(hash, piece.getOrientation() == null ? -1 : piece.getOrientation().ordinal());
                if (piece instanceof net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece pool) {
                    hash = mixText(hash, net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement.CODEC
                            .encodeStart(ops, pool.getElement()).result().map(Object::toString)
                            .orElse("unencodable " + pool.getElement().getClass().getName()));
                }
            }
        }
        StageHashes.record(key, chunk.getPos().x, chunk.getPos().z, hash);
    }

    private static long mixText(long hash, String text) {
        for (int i = 0; i < text.length(); i++) {
            hash = StageHashes.mix(hash, text.charAt(i));
        }
        return StageHashes.mix(hash, text.length());
    }

    private static long mixBox(long hash, net.minecraft.world.level.levelgen.structure.BoundingBox box) {
        for (int v : new int[]{box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()}) {
            hash = StageHashes.mix(hash, v);
        }
        return hash;
    }

    /** Per-dimension decisions for one stage on the current server, for tests and status output. */
    public static Map<ResourceKey<Level>, StageOffloadPolicy.Decision> decisions(Stage stage) {
        return Map.copyOf(stage.decisions);
    }

    public static long offloaded(Stage stage) {
        return stage.offloaded.sum();
    }

    /** For {@code /chunkexpress status}: per stage, counts and the decision for each dimension seen. */
    public static List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        boolean current = decidedFor == ChunkExpressRuntime.current();
        for (Stage stage : Stage.values()) {
            if (!current || stage.decisions.isEmpty()) {
                lines.add(stage.name + " stage: no chunks generated yet on this server");
                continue;
            }
            lines.add(stage.name + " stage: " + stage.offloaded.sum() + " chunks on worker threads, "
                    + stage.inline.sum() + " on the worldgen lane");
            stage.decisions.forEach((dimension, d) -> lines.add("  " + dimension.location() + ": "
                    + (d.offload() ? "worker threads" : "worldgen lane (vanilla)") + " - " + d.reason()));
        }
        return lines;
    }

    private static StageOffloadPolicy.Decision decision(Stage stage, WorldGenContext context) {
        ChunkExpressRuntime rt = ChunkExpressRuntime.current();
        if (rt != decidedFor) {
            // A new server (singleplayer opens one per world): forget the previous server's decisions.
            synchronized (StageOffload.class) {
                if (rt != decidedFor) {
                    for (Stage s : Stage.values()) {
                        s.decisions.clear();
                        s.offloaded.reset();
                        s.inline.reset();
                        s.warmed = false;
                    }
                    prepareVanillaCaches();
                    decidedFor = rt;
                }
            }
        }
        if (rt == null) {
            return new StageOffloadPolicy.Decision(false, "Chunk Express runtime not started");
        }
        return stage.decisions.computeIfAbsent(context.level().dimension(), dimension -> {
            ChunkGenerator generator = context.generator();
            boolean vanillaGenerator = generator.getClass() == NoiseBasedChunkGenerator.class;
            List<String> types = new ArrayList<>(customTypes(stage.registries));
            if (vanillaGenerator && stage == Stage.STRUCTURE_STARTS) {
                types.addAll(structureTypes(context));
            } else if (vanillaGenerator) {
                types.addAll(surfaceRuleTypes((NoiseBasedChunkGenerator) generator));
                if (stage == Stage.CARVERS) {
                    types.addAll(carverTypes(generator));
                }
            }
            Map<String, String> versions = installedModVersions();
            ReviewedPatches.Split typeSplit = ReviewedPatches.splitTypes(List.copyOf(new java.util.TreeSet<>(types)), versions);
            ReviewedPatches.Split mixins = ReviewedPatches.split(foreignMixins(stage.classes), versions);
            List<String> reviewed = new ArrayList<>(mixins.reviewed());
            typeSplit.reviewed().forEach(t -> reviewed.add("type " + t));
            StageOffloadPolicy.Decision d = StageOffloadPolicy.decide(stage.name, stage.configKey,
                    stage.enabled.test(rt.settings()),
                    generator.getClass().getName(),
                    vanillaGenerator,
                    typeSplit.unreviewed(),
                    mixins.unreviewed(),
                    reviewed);
            Logs.LOG.info("Chunk Express: {} stage for {} runs {}: {}", stage.name, dimension.location(),
                    d.offload() ? "on worker threads" : "on the worldgen lane (vanilla)", d.reason());
            return d;
        });
    }

    /**
     * Fills vanilla caches that are built lazily without any thread safety and that the moved stages
     * reach, before any chunk of this server is offloaded. Called once per server, before decisions
     * are published (the volatile {@code decidedFor} write), so every worker task submitted later sees
     * them complete.
     *
     * <p>{@code OctahedralGroup.rotate(Direction)} assigns its empty direction map to the field before
     * filling it: a second thread rotating a jigsaw block at that moment reads a missing entry and
     * crashes (NullPointerException in FrontAndTop, seen once in O3's verification). Filling every
     * group's map here leaves vanilla only ever reading it.
     */
    static void prepareVanillaCaches() {
        for (com.mojang.math.OctahedralGroup group : com.mojang.math.OctahedralGroup.values()) {
            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
                group.rotate(direction);
            }
        }
    }

    /**
     * Mod-added rule and condition types in the dimension's surface rule, read by encoding the rule
     * as JSON. A rule that cannot be encoded counts as unknown, so the stage stays on the lane.
     */
    static List<String> surfaceRuleTypes(NoiseBasedChunkGenerator generator) {
        try {
            SurfaceRules.RuleSource rule = generator.generatorSettings().value().surfaceRule();
            JsonElement json = SurfaceRules.RuleSource.CODEC.encodeStart(JsonOps.INSTANCE, rule).getOrThrow();
            return List.copyOf(RuleTypes.nonVanilla(RuleTypes.collect(json)));
        } catch (RuntimeException e) {
            return List.of("a surface rule that could not be read (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        }
    }

    /**
     * Mod-added carver types used by the biomes this generator can place (after NeoForge biome
     * modifiers, which getGenerationSettings already reflects).
     */
    static List<String> carverTypes(ChunkGenerator generator) {
        java.util.Set<String> custom = new java.util.TreeSet<>();
        try {
            for (net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome : generator.getBiomeSource().possibleBiomes()) {
                for (net.minecraft.world.level.levelgen.GenerationStep.Carving step : net.minecraft.world.level.levelgen.GenerationStep.Carving.values()) {
                    for (var carver : biome.value().getGenerationSettings().getCarvers(step)) {
                        var type = carver.value().worldCarver();
                        ResourceLocation id = BuiltInRegistries.CARVER.getKey(type);
                        if (id == null) {
                            custom.add("an unregistered carver type (" + type.getClass().getName() + ")");
                        } else if (!id.getNamespace().equals(ResourceLocation.DEFAULT_NAMESPACE)) {
                            custom.add(id.toString());
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            custom.add("carvers that could not be read (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        }
        return List.copyOf(custom);
    }

    /**
     * Mod-added types structure starts would run for this dimension: placement and structure types of
     * its structure sets, every "type" inside those structures (encoded as JSON), and the element
     * types of every template pool, read through {@code getShuffledTemplates} so that entries other
     * mods add to pools are included. Anything that cannot be read counts as unknown.
     */
    static List<String> structureTypes(WorldGenContext context) {
        java.util.Set<String> custom = new java.util.TreeSet<>();
        var access = context.level().registryAccess();
        var ops = net.minecraft.resources.RegistryOps.create(JsonOps.INSTANCE, access);
        try {
            for (var set : context.level().getChunkSource().getGeneratorState().possibleStructureSets()) {
                addIfModded(custom, BuiltInRegistries.STRUCTURE_PLACEMENT.getKey(set.value().placement().type()), "placement");
                for (var entry : set.value().structures()) {
                    var structure = entry.structure().value();
                    addIfModded(custom, BuiltInRegistries.STRUCTURE_TYPE.getKey(structure.type()), "structure");
                    JsonElement json = net.minecraft.world.level.levelgen.structure.Structure.DIRECT_CODEC.encodeStart(ops, structure).getOrThrow();
                    custom.addAll(RuleTypes.nonVanilla(RuleTypes.collect(json)));
                }
            }
            net.minecraft.util.RandomSource fixed = net.minecraft.util.RandomSource.create(0L);
            for (var pool : access.registryOrThrow(net.minecraft.core.registries.Registries.TEMPLATE_POOL)) {
                for (var element : pool.getShuffledTemplates(fixed)) {
                    addIfModded(custom, BuiltInRegistries.STRUCTURE_POOL_ELEMENT.getKey(element.getType()), "pool element");
                    JsonElement json = net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement.CODEC
                            .encodeStart(ops, element).getOrThrow();
                    custom.addAll(RuleTypes.nonVanilla(RuleTypes.collect(json, java.util.Set.of("element_type"))));
                }
            }
        } catch (RuntimeException e) {
            custom.add("structure sets that could not be read (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        }
        return List.copyOf(custom);
    }

    private static void addIfModded(java.util.Set<String> custom, ResourceLocation id, String what) {
        if (id == null) {
            custom.add("an unregistered " + what + " type");
        } else if (!id.getNamespace().equals(ResourceLocation.DEFAULT_NAMESPACE)) {
            custom.add(id.toString());
        }
    }

    private static Map<String, String> installedModVersions() {
        Map<String, String> versions = new HashMap<>();
        ModList.get().getMods().forEach(mod -> versions.put(mod.getModId(), mod.getVersion().toString()));
        return versions;
    }

    /** Registered types outside the minecraft namespace. */
    static List<String> customTypes(List<Registry<?>> registries) {
        List<String> custom = new ArrayList<>();
        for (Registry<?> registry : registries) {
            for (ResourceLocation id : registry.keySet()) {
                if (!id.getNamespace().equals(ResourceLocation.DEFAULT_NAMESPACE)) {
                    custom.add(id.toString());
                }
            }
        }
        return custom;
    }

    /** Other mods' mixins merged into the given classes (fully qualified mixin class names). */
    static List<String> foreignMixins(List<String> classes) {
        List<String> found = new ArrayList<>();
        for (String mixin : mergedMixins(classes)) {
            if (!mixin.startsWith(OWN_MIXIN_PACKAGE)) {
                found.add(mixin);
            }
        }
        return found;
    }

    /**
     * Every mixin (ours included) that merged a method into the given classes, read from the
     * {@code @MixinMerged} annotation Mixin puts on each merged method and injector handler (runtime
     * retention). By the time a chunk reaches these stages the classes are loaded and transformed.
     * Mixins that only add fields or interfaces are not seen; without a method they cannot change
     * what the stage's code does. (Until 2026-11-20 this read ClassInfo.getAppliedMixins(), which
     * Mixin 0.8.7 records on the mixin's own ClassInfo, not the target's: it never saw anything.)
     */
    public static java.util.Set<String> mergedMixins(List<String> classes) {
        java.util.Set<String> found = new java.util.TreeSet<>();
        for (String className : classes) {
            try {
                // The class and every class nested in it: SurfaceRules' rules and conditions are nested classes.
                java.util.ArrayDeque<Class<?>> pending = new java.util.ArrayDeque<>();
                pending.add(Class.forName(className, false, StageOffload.class.getClassLoader()));
                while (!pending.isEmpty()) {
                    Class<?> target = pending.poll();
                    for (java.lang.reflect.Method method : target.getDeclaredMethods()) {
                        MixinMerged merged = method.getAnnotation(MixinMerged.class);
                        if (merged != null) {
                            found.add(merged.mixin());
                        }
                    }
                    pending.addAll(List.of(target.getDeclaredClasses()));
                }
            } catch (RuntimeException | LinkageError | ClassNotFoundException e) {
                // Cannot tell: treat as patched, so the stage stays on the lane.
                found.add("unknown (" + e.getClass().getSimpleName() + " while inspecting " + className + ")");
            }
        }
        return found;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }
}
