# Changelog

## 0.2.1 - unreleased

- Fast fluid settling now also works with ScalableLux 0.3.0-alpha.0.8 (its chunk patch was reviewed).
- Tested with ScalableLux: game tests, rides and the hard-kill test pass.

## 0.2.0 - 2026-11-21

### Faster generation

- **`offloadStructureStarts`** (default on): the *structure starts* stage - where villages,
  strongholds and other structures are planned - runs on Minecraft's worker threads too, with
  stricter checks than surface and carvers. Checked identical to vanilla across seeds, dimensions,
  Large Biomes, Amplified and Terralith.
- Works with Terralith/Lithostitched 1.8.1 for all three stages: its patches to the structure code
  were reviewed.
- **`fastFluidSettling`** (default on): when a new chunk becomes a full chunk, vanilla settles its
  water and lava on the server thread. Those checks now read blocks of that chunk directly instead of
  looking the chunk up for every block: identical results (checked read by read), about 10% less
  settling time. Works with Create (its fluid patch was reviewed).

### Fixes

- A vanilla race found while testing: the table vanilla uses to rotate jigsaw blocks is filled
  lazily without thread safety (it could crash the server with a NullPointerException when two
  structures generated at once). Chunk Express now fills it before any chunk is generated in
  parallel.
- The structure-starts checks look only at types a dimension's structures and template pools
  actually use, so libraries that register unused types don't switch it off.

## 0.1.0 - 2026-11-21 (first public release)

Minecraft 1.21.1, NeoForge 21.1.265 or newer within 21.1, Java 21. Server-side; players joining a
server do not need it.

### Faster generation

- **`offloadSurface`** (default on): the *surface* generation stage runs on Minecraft's worker
  threads, the way vanilla already runs the *noise* stage, instead of each dimension's single
  generation lane.
- **`offloadCarvers`** (default on): the same for the *carvers* stage (caves and canyons).
- Both check their safety conditions per dimension (vanilla noise generator, no mod-added surface
  rule or carver types, no other mod patching the code they move) and otherwise keep vanilla
  behaviour, with the reason in the log and in `/chunkexpress status`.
- Output verified bit-identical to vanilla at the input and output of both stages.
- Ice-road benchmark with Create and Farmer's Delight: the server's waiting for chunks fell from a
  median 26 s per ride to under 0.5 s (docs/BENCHMARKS.md).

### Diagnostics

- `/chunkexpress status`: tick work and tick interval (including server-thread work between ticks),
  server-thread waits for chunks, time per generation stage, chunk load and save latency, region
  read/write latency and backlog, storage problems, active optimisations and why.
- `/chunkexpress compat`, `/chunkexpress reset`.
- Chunk lifecycle tracking with anomaly detection; a shutdown check for writes that never finished;
  a session summary in the log.

### Data-loss warnings

- Clear errors, naming the region file to back up, when vanilla is about to regenerate a chunk and
  overwrite its stored data: read failures, damaged region entries, chunk data without a status,
  region header entries discarded when a file is opened, and save failures that vanilla will not
  retry.

### Compatibility

- Declared incompatible with Moonrise and C2ME: NeoForge refuses to start with both installed and
  says why.
- All game tests pass with Create 6.0.10 and Farmer's Delight 1.3.4.
- Works with Lithium: the chunk-wait measurement switches off (Lithium replaces the method it
  hooks); the optimisations stay on. Game tests and the hard-kill test pass with Create, Farmer's
  Delight, Terralith, Lithium, FerriteCore, ModernFix, JEI and Jade together.
- Works with Terralith, speedups included: the safety check looks only at the surface-rule types a
  dimension actually uses, and Lithostitched 1.8.1's patches to the surface code were reviewed.
  Terrain checked identical to vanilla with Terralith (0 of 4,387 chunks differ).
- Works with ModernFix 5.27.27, speedups included: its surface-code patches were reviewed;
  terrain checked identical (0 of 4,874 chunks differ).
- The patch check now also covers classes nested in the watched ones (SurfaceRules' rules and
  conditions).
- Fixed: the check for other mods patching the moved code could not see any mixin (it read a Mixin
  record that is kept on the mixin, not on the patched class). It now reads `@MixinMerged`, and a
  game test proves it detects Chunk Express's own mixin.

### For developers

- Ice-road benchmark mod (`./gradlew runIceRoad`, `runIceRoadChunkExpress`), stage-boundary identity
  checks (`-Dchunkexpress.debug.stageHashes`, `./gradlew stageHashDiff`), `./gradlew worldDiff`,
  compatibility game tests (`./gradlew runGameTestServerCompat`).
- 57 unit tests, 11 game tests.
- Licensed GPL-3.0-only.
