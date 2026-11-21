# Optimisations

Every optimisation in Chunk Express has, before it is switched on by default:

1. **Evidence it is needed** — a measurement showing the cost it attacks.
2. **A safety argument** from the 1.21.1 sources, with the conditions under which it does not hold.
3. **Gates** that fall back to vanilla behaviour on their own when those conditions are not met,
   logged with a reason and shown in `/chunkexpress status`.
4. **A config switch** (`[optimizations]` in `config/chunkexpress-common.toml`); `false` is exactly vanilla.
5. **Correctness tests** — game tests, plus a terrain comparison against vanilla (`./gradlew worldDiff`).
6. **A benchmark** — ice-road rides with it on and off (docs/BENCHMARKS.md).

## O1 — Surface stage off the worldgen lane (`offloadSurface`)

**Status:** implemented, default on, gated. Measured: see "Results" below.

### Evidence

In 1.21.1 every dimension has one "worldgen" mailbox that runs most generation stages one chunk at
a time (`ChunkMap.worldgenMailbox`, a `ProcessorMailbox` over the background pool). Only `noise`
and `biomes` leave it, by returning `CompletableFuture.supplyAsync(..., Util.backgroundExecutor())`.
In the observe-only Chunk Express ice-road ride, the work done on that lane was:

| Stage | On the lane |
|---|---|
| surface | 19.3 s |
| features | 8.2 s |
| structure_starts | 4.1 s |
| carvers | 2.5 s |
| everything else | < 1 s |

So `surface` was over half of all serial generation work, in a ride where the server thread spent
~45 s waiting for chunks that were not ready.

### Safety argument

- `ChunkPyramid.GENERATION_PYRAMID` declares `SURFACE` with the **same dependencies**
  (`STRUCTURE_STARTS` within 8, `BIOMES` within 1) and the **same block-state write radius (0)** as
  `NOISE`, which vanilla already runs on the worker pool.
- `NoiseBasedChunkGenerator.buildSurface` writes only the chunk being generated and reads neighbour
  biomes, which its dependencies guarantee are complete. Vanilla's async noise stage already reads
  neighbours concurrently with other chunks' stages.
- Shared state is immutable (`SurfaceSystem`: only final fields and immutable noises) or concurrent
  (`RandomState`'s noise and random-factory caches are `ConcurrentHashMap`s). `SurfaceRules` has no
  static mutable state.
- Randomness is seeded by position, so output does not depend on thread or order.
- Failures take vanilla's path for async stages: the failed future reaches
  `GenerationChunkHolder.applyStep`, which records a fatal crash report (`MinecraftServer.setFatalException`).
- The stage's code is vanilla's, unchanged. Chunk Express only chooses the thread
  (`StageOffloadMixin`, a MixinExtras `@WrapMethod` that calls the original exactly once).

**Not moved:** `carvers`. `applyCarvers` lazily caches a value inside neighbouring chunks
(`ChunkAccess.carverBiome`), a write outside its own chunk. `features` writes into neighbours by
design (write radius 1).

### Gates (each falls back to the vanilla lane, per dimension)

| Condition | Why |
|---|---|
| `offloadSurface = false` | operator choice |
| generator is not exactly `NoiseBasedChunkGenerator` | a modded generator's `buildSurface` was never run concurrently |
| the dimension's surface rule uses a rule or condition type outside `minecraft:` | that code would run on worker threads for the first time. Read from the rule itself (encoded with `SurfaceRules.RuleSource.CODEC`, every `type` collected); a rule that cannot be encoded counts as unknown. Types a mod registers but no dimension uses never run, so they do not count (until 2026-11-20 any registered type did, which kept every Lithostitched install vanilla) |
| another mod's mixins are applied to `ChunkStatusTasks`, `NoiseBasedChunkGenerator`, `SurfaceSystem`, `SurfaceRules`, `SurfaceRules$Context` or `WorldGenerationContext`, unless each was reviewed for the installed version (`ReviewedPatches`) | read from the `@MixinMerged` annotations on the loaded classes, naming each mixin in the log; game test `mixinGateSeesMergedMixins` checks the detection can see our own mixin |

Data packs that only use vanilla rule types pass the gates: their rules are executed by vanilla
code. Density functions are not gated: vanilla already evaluates them on worker threads in the noise
stage.

**Warm-up.** On each server, the first chunk of an offloadable stage runs on the lane; offloading
starts only after it finished. Code in the stage may fill a lazy cache on first use; the warm-up
fills it, and the volatile `warmed` flag publishes it to every worker task submitted afterwards.

### Reviewed patches: Lithostitched 1.8.1 (Terralith's library)

Terralith 2.6.2's own surface rule uses only vanilla types, and Terralith has no mixins. Its required
library Lithostitched registers 8 surface rule/condition types (unused by Terralith) and patches
the surface code. Each patch on a gated class, read from the 1.8.1 jar:

| Mixin | What it does | Finding |
|---|---|---|
| `SurfaceRulesContextMixin` | shadows `SurfaceRules.Context` fields, adds getters and a biome condition | reads only; the context belongs to one chunk |
| `bandlands.SurfaceSystemMixin` | getters for two final fields | reads only |
| `bandlands.NoiseBasedChunkGeneratorMixin` | at the start of `buildSurface`, calls `Bandlands.fillBands` on every `lithostitched:bandlands` registry entry | lazy init: returns if the array exists; otherwise builds a complete array locally from a fixed seed (`clay_bands`) and stores it. Concurrent callers store identical, complete arrays. The store is a plain field write, so publication to other threads relies on the warm-up above. (Vanilla with Lithostitched already runs this concurrently across dimensions.) |
| `NoiseBasedChunkGeneratorMixin` | wraps a biome lookup in `spawnOriginalMobs` | not in the surface or carvers stage |
| `NoiseBasedChunkGeneratorAccessor` | setter for the generator's `settings` field | called only by `RereferenceNoiseSettingsModifier.apply(RegistryAccess)`, when worldgen modifiers are applied at server start, before any chunk generates |
| `compat.terrablender.NoiseBasedChunkGeneratorMixin` | wraps biome resolution in `doCreateBiomes` | biomes stage, already on worker threads in vanilla |

### Reviewed patches: ModernFix 5.27.27+mc1.21.1

| Mixin | Finding |
|---|---|
| `bugfix.chunk_deadlock.ChunkMapLoadMixin` | patches `ChunkStatusTasks.full` (FULL stage, server thread), not the moved stages |
| `perf.optimize_surface_rules.NoiseBasedChunkGeneratorMixin` | reads nearby sections' biomes and passes the set to the inner `buildSurface` through a `ThreadLocal`, removed afterwards; one thread, one call |
| `perf.optimize_surface_rules.SurfaceRulesContextMixin` | copies that set into a field of the per-chunk `Context` |
| `perf.optimize_surface_rules.SurfaceSystemMixin` | faster biome lookup and block column, in `ThreadLocal`s disposed at the end of `buildSurface` |
| `perf.optimize_surface_rules.BiomeConditionSourceMixin` | constant condition when the possible biomes decide it; reads only |
| `perf.worldgen_allocation.SequenceRuleMixin` | `@Overwrite` of `SequenceRule.tryApply` with an index loop over the same immutable list; reads only |
| `perf.worldgen_allocation.SurfaceRulesContextMixin` | `updateY` reusing a position and supplier owned by the per-chunk `Context` |

Stage hashes with ModernFix, O1/O2 off vs on: 0 of 4,874 shared chunks differ (2026-11-21).

The gate watches the listed classes and every class nested in them (since 2026-11-21; before,
nested `SurfaceRules` rule and condition classes were not checked).

Its other mixins (`RandomState`, `NoiseChunk`, `Beardifier`, `ChunkMap`, density functions,
structures) are not on the classes the gates watch. The review is pinned to 1.8.1: any other version
keeps both stages on the lane, logged as "reviewed for lithostitched 1.8.1, installed: x".

### Verification

- Game test `surfaceIsOffloadedForVanillaNoiseGenerationOnly`: the game-test server's flat
  overworld stays on the lane ("FlatLevelSource is not vanilla's noise generator"); a new Nether
  chunk is offloaded and reaches `full`.
- Terrain comparison and rides: see Results.

### Results

**Same terrain.** Stage-boundary hashes (`-Dchunkexpress.debug.stageHashes=surface,carvers`, see
`generation.StageHashes`) of every chunk at the start of `surface` (= noise output) and of `carvers`
(= surface output), in three rides: offload off, on, off.

| Compared | Chunks in both | Differ at `surface` start | Differ at `carvers` start |
|---|---|---|---|
| off vs off | 3,106 | 0 | 0 |
| off #1 vs on | 3,429 | 0 | 0 |
| off #2 vs on | 4,406 | 0 | 0 |

The hashes are sensitive: 6,300 distinct values over 6,304 chunks, and `surface` changed every
chunk's hash. **The offloaded surface stage produces bit-identical output to vanilla's.**

A whole-world comparison (`./gradlew worldDiff`) cannot answer this question: two vanilla worlds
of the same seed already differ in 41% of their chunks (812 of 1,998), all in feature blocks (ores,
clay, moss, cave vines), because vanilla's feature stage depends on the order chunks are generated
in. See docs/BENCHMARKS.md.

**Faster.** Ice-road rides, same machine and settings (docs/BENCHMARKS.md has every ride):

| | Rides | Road chunks not ready (of 624) | Server thread waited | Tick interval p95 | New chunks in the ride |
|---|---|---|---|---|---|
| offload on | 3 | 30 / 77 / 62 | 2.5 / 8.3 / 7.5 s | 61 / 63 / 65 ms | 4,395–4,521 |
| off (vanilla + observe-only) | 7 | 154–582, median 527 | 12.2–48.6 s, median 44.4 s | 70–203 ms, median 181 ms | 1,834–3,551 |

Every ride with the offload beat every ride without it on stalls, waiting and tick interval. The
median wait for chunks fell from 44.4 s to 7.5 s per ride (about 6×), tick-interval p95 from 181 ms to
63 ms, and the server generated roughly twice as many chunks in the same ride.

**Open question.** The client's average loaded distance ahead along the road dropped (5.6 chunks
vs ~7.0) while the share of time below 4 chunks also dropped (7% vs ~17%): fewer bad moments, but a
shorter typical horizon. A likely cause is that generation now keeps up across the whole view area
instead of mostly ahead. To investigate with chunk-send instrumentation before the next generation
optimisation.

**Decision:** on by default (with its gates). Three rides with it on is a small sample, but the
separation from all seven rides without it is complete, and correctness was shown independently.

## O2 — Carvers stage off the worldgen lane (`offloadCarvers`)

**Status:** implemented, default on, gated. Same mechanism as O1 (`StageOffload`,
`StageOffloadMixin`), separate switch.

### Evidence

With O1 on, the remaining work on the serial lane in an ice-road ride was `features` 15.8 s,
`structure_starts` 6.7 s and `carvers` 4.6 s. Carvers is the largest stage left that writes only
its own chunk.

### Safety argument

- Block-state write radius 0 in `ChunkPyramid` (own chunk only); depends on structure starts within 8.
- `NoiseBasedChunkGenerator.applyCarvers` writes only the chunk being carved (`chunk.setBlockState`,
  its carving mask, post-processing marks) and reads its own `NoiseChunk`/aquifer. Biomes for
  carving come from the biome source (pure), not from neighbours' stored data.
- Its one write outside its chunk is `ChunkAccess.carverBiome`, an unsynchronised lazy cache on the
  up to 17×17 neighbouring chunks. The value is the generation settings of the biome at that
  position: a registry object built when the server loaded, so every racing writer stores the
  *same, already fully published* reference. The race is idempotent; no thread can see a partly
  built object. (`BiomeGenerationSettings` has only final fields; its lazy parts are Guava
  memoizing suppliers, which are thread-safe.)
- Carvers pick top materials through surface rules, so they inherit O1's argument and gates.

Earlier I had ruled carvers out because of that neighbour write; reading what is written showed it is
benign.

### Gates

O1's gates (vanilla noise generator, no mod-added surface rule/condition types in the dimension's
surface rule, no unreviewed foreign mixins on the surface classes) and its warm-up, plus: no mod-added carver types (`BuiltInRegistries.CARVER` outside
`minecraft:`) and no foreign mixins on `CarvingContext`, `ConfiguredWorldCarver`, `WorldCarver`,
`CaveWorldCarver`, `CanyonWorldCarver`, `NetherWorldCarver`. `ChunkAccess` and `ChunkGenerator` are
deliberately not gated: many unrelated mods patch them, and the code that runs is
`NoiseBasedChunkGenerator.applyCarvers` (gated) plus block writes that vanilla's off-lane noise stage
already performs.

### Verification

- Game test `stagesAreOffloadedForVanillaNoiseGenerationOnly`: in the Nether both stages run off
  the lane and the chunk reaches `full`; the flat overworld stays on the lane for both.
- Stage-output hashes: the input to `features` cannot be compared between runs (neighbours'
  features may already have written into the chunk), so `StageOffload` hashes a stage's output
  right after its code returns, on the thread that ran it (`-Dchunkexpress.debug.stageHashes=carvers_out`).

### Results

**Same terrain.** Three rides (both optimisations off, both on, both off), hashing every chunk at the
input and output of `surface` and `carvers`:

| Compared | Chunks in both | Differ: surface in / out | Differ: carvers in / out |
|---|---|---|---|
| off vs off | 3,165 | 0 / 0 | 0 / 0 |
| off #1 vs on | 3,338 | 0 / 0 | 0 / 0 |
| off #2 vs on | 4,507 | 0 / 0 | 0 / 0 |

Checks on the checks: carvers changed 90% of chunks (6,006 of 6,655 in the "on" ride; the rest have
no caves); surface's output hash equals carvers' input hash for every chunk, so the output hash is
taken at the right moment; every chunk's carvers-output hash is distinct. **The offloaded carvers
stage produces bit-identical output to vanilla's.**

**Compatibility smoke test.** All 11 game tests pass with Create 6.0.10 and Farmer's Delight 1.3.4
loaded (`./gradlew runGameTestServerCompat`); neither patches the surface or carver code, so both
stages stay off the lane for vanilla-generated dimensions.

**Performance:** see the engine comparison in docs/BENCHMARKS.md.

## O3 - Structure starts off the worldgen lane (`offloadStructureStarts`)

**Status:** implemented for 0.2.0 (default on, gated); verification below.

### Evidence

With O1 on, `structure_starts` was the second-largest stage left on the serial lane (6.7 s per
ride, after `features`): it runs for every chunk within 8 of each chunk being generated, about
11,000 times per ride.

### Safety argument (1.21.1 sources)

- `ChunkStatusTasks.generateStructureStarts` → `ChunkGenerator.createStructures`: every read and
  write of structure data goes through `StructureManager.get/setStartForStructure`, which forward to
  the chunk being generated. No neighbour is read or written; the step has no dependencies and writes
  no blocks.
- Placement (`StructurePlacement.isStructureChunk`, exclusion zones) is pure arithmetic on the seed and
  position. The stronghold rings are computed once when the dimension is created
  (`ServerLevel` calls `ChunkGeneratorStructureState.ensureStructuresGenerated()` in its constructor)
  and only read afterwards.
- Jigsaw structures read templates through `StructureTemplateManager`, a concurrent map, and the
  templates' palette caches are concurrent as well: NeoForge already made them so for MC-271899,
  because vanilla runs structure starts of different dimensions on different lanes at the same time.
- `ServerLevel.onStructureStartsAvailable` only queues a task for the server thread.
- Output depends only on seed and position, so it is identical in any order.

### Gates

Structures are where mods most often add their own code, so the gates are stricter than O1/O2: vanilla
noise generator only; no mod-registered structure, structure piece, pool element, placement,
processor or pool-alias types; no other mod's mixins on structure-generation classes (including
`ChunkGenerator`, where `createStructures` lives).

### Verification

Structure starts produce structure data, not blocks, so the stage hash covers each start's structure
id, bounding box and pieces (`structure_starts_out`).

### Implementation

`StageOffload.Stage.STRUCTURE_STARTS`, wrapped like O1/O2 (`@WrapMethod` on
`ChunkStatusTasks.generateStructureStarts`), with the same warm-up and fault handling.

- Watched classes (`StructureClasses`): all 125 top-level classes of 1.21.1's
  `net.minecraft.world.level.levelgen.structure` packages (nested classes included by the gate),
  plus `ChunkStatusTasks`, `ChunkGenerator`, `ChunkGeneratorStructureState`,
  `NoiseBasedChunkGenerator` and `level.StructureManager`.
- Types, as used: placement and structure types of the dimension's structure sets, every `type`
  inside those structures (encoded as JSON: height providers, pool aliases, ...), and the
  `element_type` of every element of every template pool, read through `getShuffledTemplates` so
  that entries other mods add to pools are included. Piece types are created by code, so any
  mod-added one counts. Anything that cannot be read counts as unknown. Processor types are not counted: vanilla runs processors
  only in `StructureTemplate.processBlockInfos`, i.e. when a template is placed into the world
  (features stage), never during structure starts.
- Output hash `structure_starts_out`: per chunk, every start's structure id and bounding box, and per
  piece its type, bounding box, depth, orientation and, for jigsaw pieces, the pool element.

### Reviewed patches: Lithostitched 1.8.1 on the structure code

27 of its mixins target watched classes. None writes a static field. By what runs during structure
starts:

| Mixin(s) | Runs during structure starts? | Finding |
|---|---|---|
| `JigsawStructureMixin` (`findGenerationPoint`) | yes | routes to `AlternateJigsawStructure.generate` when its alternate config is used; only static constants (codec, type) |
| `StructureTemplatePoolMixin` | yes (template choice) | gives each pool a `LithostitchedTemplates`, filled by `compileRawTemplates` from a world-generation modifier at server start. `shuffle(random)` maps the entries through `WeightedEntry::copy`, then randomises and sorts only the copies: the shared list is never modified |
| `PoolAliasLookupMixin` (`create`) | yes | no field writes, no helper calls |
| `template.NetherFossil/RuinedPortal/ShipwreckStructureMixin` (`findGenerationPoint`, `generatePieces`) | yes | pick a template id via `TemplateLists.getRandom(registryAccess, key, random)`: registry read, random from the caller; `TemplateLists` holds only static constants |
| `template.mansion.*` (`createMansion`, room pickers, `makeLocation`) | yes | write `registries` into the per-mansion `MansionPiecePlacer` and its room collections, which vanilla creates afresh for each mansion |
| `*PieceMixin.makeSettings` (End city, igloo, nether fossil, ocean ruin, ruined portal, shipwreck, mansion) | yes (piece constructors) | build the piece's own `StructurePlaceSettings` with `UnboundReferenceProcessor.of(...)` (a new object) |
| Accessors (`ChunkGenerator`, `JigsawStructure`, `ListPoolElement`, `SinglePoolElement`, `StructureProcessorList`, `StructureSet`, `StructureTemplatePool`) | getters yes; setters no | setters are called only by Lithostitched's world-generation modifiers and `BiomeInjectorManager`, at server start; `AlternateJigsawStructure.setPoolAliases` is one of those (via `SetPoolAliasesModifier`) |
| type `lithostitched:internal/random_entries` (pool-alias binding, used by the overworld) | yes | immutable record; picks pools with the caller's random source (reviewed as a type, `ReviewedPatches.TYPES`) |
| `StructurePieceMixin`, `StructureStartMixin`, `processor.*` (`postProcess`, `placeInChunk`, `placeBlock`, `createChest`, `afterPlace`, ...) | no: placement (features stage) | not moved by O3; `StructurePieceMixin` writes only the piece's own reference position |

### A vanilla race found by the verification

The first O3 hash matrix (2026-11-21) crashed once in seven runs, with Large Biomes: a
`NullPointerException` in `FrontAndTop.fromFrontAndTop` ("top is null") while rotating a jigsaw
block during structure starts. Cause: vanilla's `OctahedralGroup.rotate(Direction)` builds its
direction table lazily and assigns the empty `EnumMap` to the field *before* filling it, so a second
thread can read a missing entry. Vanilla can hit this too (structure starts of different dimensions
run at the same time), but only on first use; O3 runs many structure starts in parallel right at
world start, which made it likely.

Fix (`StageOffload.prepareVanillaCaches`): before the first chunk of each server is offloaded, every
`OctahedralGroup`'s table is filled on one thread, then published with the stage decisions. Vanilla
code is unchanged and from then on only reads the tables.

A search of the code the moved stages reach for the same pattern (a field checked against null or a
sentinel, then assigned) found no other case: the structure packages have none; the template
palette cache and `StructureTemplateManager`'s repository are concurrent maps (NeoForge, MC-271899);
`StructureCheck`'s maps are written only on the server thread (`onStructureStartsAvailable` queues
the update with `server.execute`); `Transformation`'s caches are rendering-only.

### O3 results (2026-11-21)

Stage-hash pairs, all optimisations off vs on (`-Ppregen`, `structure_starts_out`, `surface_out`,
`carvers_out`):

| World | Structure starts (shared chunks) | Surface / carvers |
|---|---|---|
| seed 20261117, overworld + Nether + End | 0 of 9,127 differ | 0 of 3,547 |
| seed 1, overworld + Nether + End | 0 of 9,228 | 0 of 3,612 |
| seed -4172144997902289642, overworld + Nether + End | 0 of 9,177 | 0 of 3,579 |
| Amplified | 0 of 4,709 | 0 of 1,865 |
| Large Biomes (after the `OctahedralGroup` fix) | identical | 0 of 1,930 |
| Terralith + Lithostitched 1.8.1, overworld + Nether | 0 of 6,622-6,672 (three runs: off, off, on) | 0 of 2,554 |

Stress: 8 runs with O3 on (Large Biomes and normal worlds, eight seeds), 0 crash reports. Game tests
12/12 alone and with every compatibility mod; fallback and fault scripts pass.

One apparent difference with Terralith (chunk 0,22) also differed between two runs with O3 *off*:
the hash used the pool element's `toString()`, which for some elements contains identity hash
codes. Pool elements are now hashed by their codec encoding; with that, all three runs agree.

## Considered and not done: caching height and biome sampling (2026-11-21)

Profiled with Java Flight Recorder on a 5,000-block ice-road ride with O1, O2 and O3 on (no other
mods; 0 chunk stalls on that ride). Of 19,586 worker-thread samples:

| Work | Samples | Share |
|---|---|---|
| Column height sampling (`NoiseBasedChunkGenerator.iterateNoiseColumn`: structure placement checks, jigsaw `getFirstFreeHeight`, ruined portals, buried treasure, ...) | 99 | 0.5% |
| Biome sampling for structures (ocean monument `getBiomesWithin`, `isValidBiome`) | ~33 | 0.2% |
| Biome sampling in the biomes stage itself (real generation, not repeated) | 890 | 4.5% |
| Stronghold ring search (`generateRingPositions`, once per world) | 223 | 1.1% |

Even a perfect cache of the repeated part would save under 1% of worker time, against a real risk of
changing results. Not done. For reference, on the server thread (2,090 samples) chunk
serialization on unload (`ChunkSerializer.write` via `ChunkMap.processUnloads`) is 217 samples,
about 10%: the candidate for the next optimisation (chunk saving off the server thread).

## O4 - Fast fluid settling (`fastFluidSettling`)

**Status:** implemented for 0.2.0, default on, gated.

### Evidence

Profile of a full-speed ride (O1-O3 on): 74% of the server thread's work while exploring is
`LevelChunk.postProcessGeneration`, where vanilla settles the fluids generation marked (aquifers,
cave and ocean edges) as a chunk becomes full. Inside it, `FlowingFluid.getNewLiquid`'s neighbour
reads dominate, and a large part of those is `ServerChunkCache.getChunk`: every `Level.getBlockState`
looks the chunk up again, although nearly every read is of the chunk being settled. (Vanilla 1.21.1
already caches states and holes inside `getSpread`; its recursion is negligible in the profile.)

### What it does

While a chunk is settled, on the thread settling it, `FlowingFluid`'s `getBlockState` /
`getFluidState` reads of positions inside that chunk are answered by the chunk itself, after the same
build-height check `Level` does. That is `Level.getBlockState`/`getFluidState` minus the chunk lookup:
the identical object is returned. Other positions, threads and code are unchanged.

### Gates

Config switch; no unreviewed mixins on `FlowingFluid`, `WaterFluid`, `LavaFluid`, `FluidState`,
`LevelChunk`; no other mod's merged `getBlockState`/`getFluidState`/`getChunk`/`getChunkAt` on
`Level` or `ServerLevel`. Reviewed: Create 6.0.10 `WaterWheelFluidSpreadMixin` (reads through the
level, writes nothing) and `FlowingFluidAccessor` (invoker), Flywheel 1.0.6 `LevelChunkMixin`
(block-entity visuals, no reads).

### Verification

Debug verify mode (`-Dchunkexpress.debug.verifyFluidReads=true`): every shortcut read is also made
the vanilla way and compared; the game keeps vanilla's answer. 2026-11-21: 3 seeds x overworld,
Nether, End; Large Biomes; Amplified; Terralith; a 3,000-block ride with Create and Farmer's
Delight: about 260 million reads compared, **0 mismatches**, no crashes. (Chunk hashes are not used
here: settling can flow across chunk edges, so its result depends on the order neighbouring chunks
become full, even in vanilla.)

### Results

Time inside `postProcessGeneration`, measured by Chunk Express itself, pre-generating the same 729
overworld chunks (seed 1), two runs each:

| | Run 1 | Run 2 |
|---|---|---|
| Vanilla | 7.13 ms per chunk | 6.85 ms per chunk |
| O4 | 6.45 ms per chunk | 6.07 ms per chunk |

About 10% less settling time, i.e. roughly 5% less server-thread work while exploring. Smaller than
the profile suggested: the chunk lookups disappear (263 -> 58 samples), but wrapping every fluid read
costs part of the saving. A lower-overhead hook could gain more; not done yet.

### O3 with the full compatibility set (2026-11-21)

Three more patches on the structure code, reviewed for O3:

| Mod | Mixin | Finding |
|---|---|---|
| Steam 'n' Rails 0.3.0-beta.2 | `StructureMixin` | adds data versions in `StructureTemplate.save` (template to NBT); not part of structure starts |
| ModernFix 5.27.27 | `perf.cache_strongholds.ChunkGeneratorMixin` | caches stronghold ring positions in `generateRingPositions`, which runs once when the dimension is created |
| ModernFix 5.27.27 | `perf.dynamic_structure_manager.StructureManagerMixin` | template repository as a soft-valued Guava cache (concurrent map); loads outside the lock and keeps the first of racing loads (`putIfAbsent`; losers return the winner's template). Templates in use cannot be collected; a reload reads the same files |

With every compatibility mod (Create, Farmer's Delight, Steam 'n' Rails, Railways Untold, Infinite
Railways, Terralith, Lithostitched, Lithium, ModernFix, FerriteCore, ScalableLux, JEI, Jade):
O1-O3 on; stage hashes off vs on, overworld and Nether: structure starts 0 of 5,624 shared chunks
differ, surface and carvers 0 of 1,970. Game tests 12/12. O4 stays vanilla there because Lithium
patches the fluid code (`fluid.flow.FlowingFluidMixin`, `flatten_states.FluidStateMixin`): Lithium
has its own fluid-flow optimisation for the same code.

### O4 and Lithium (2026-11-21)

With Lithium 0.15.4, O4 steps aside (Lithium patches `FlowingFluid` and `FluidState` with its own
fluid-flow optimisation). Same 729-chunk measurement as above: Lithium 6.76 / 6.06 ms settling per
chunk, against vanilla 7.13 / 6.85 and O4 6.45 / 6.07. Lithium's optimisation gains about as much as
O4, so nothing is lost by stepping aside, and no compatibility layer is planned: stacking the two
would mean running O4's shortcut inside methods Lithium replaces, for little expected gain.

