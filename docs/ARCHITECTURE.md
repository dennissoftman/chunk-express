# Chunk Express architecture

Describes the current code (0.1.0, the first public release: diagnostics plus the O1/O2 generation optimisations) and the
decisions that shape what comes next.

## 1. Principles

1. **World integrity first.** Vanilla Anvil format, no migrations, no Chunk Express metadata in
   worlds. Removing the mod leaves nothing behind.
2. **Measure before changing.** Every optimisation starts as a measurement of the vanilla path
   it replaces, so "better than vanilla" is a number, not a claim.
3. **Probes only observe.** Probe hooks never alter arguments, return values, control flow or
   futures, and are wrapped so that a Chunk Express bug cannot break the game. Behaviour changes
   live in separate, switchable optimisation hooks.
4. **Conservative by default.** Anything we cannot show to be safe (another mod's code, a modded
   generator) gets vanilla behaviour, decided per dimension and logged with the reason.
5. **Move vanilla code, don't rewrite it.** An optimisation runs vanilla's own code on another
   thread where vanilla's declared dependencies prove that safe, and is verified to produce
   bit-identical output.
6. **No option without behaviour.** Config options appear with the code that reads them.

## 2. Package map

```
com.chunkexpress
├── Chunk Express                     @Mod entry: config, listeners, command
├── bootstrap/                     per-server runtime and its lifecycle
│   ├── ChunkExpressRuntime          everything held for one server
│   ├── RuntimePhase               STARTING → RUNNING → STOPPING → STOPPED
│   └── ServerLifecycleListener    server start/stop and tick timing
├── config/                        Settings (immutable record) + ModConfigSpec
├── integration/
│   ├── ProbeHooks                 bodies of all probe hooks (observe-only contract)
│   ├── ProbeRegistry              which mixins Mixin applied
│   ├── generation/                StageOffload (O1/O2: chooses the thread for surface and carvers)
│   ├── mixin/                     ChunkExpressMixinPlugin (decides what to apply)
│   │   └── hook/
│   │       ├── probe/             ChunkMapMixin, IOWorkerMixin, MinecraftServerMixin, RegionFileMixin,
│   │       │                      ChunkStepMixin, ServerChunkCacheMixin (observe only)
│   │       └── optimization/      StageOffloadMixin (changes behaviour; switchable, gated)
│   ├── neoforge/                  event listeners, /chunkexpress command, FML mod list
│   └── compatibility/             KnownMods table, CompatibilityManager, report
├── chunk/                         ChunkLifecycleTracker, ChunkWaitMonitor
├── generation/                    GenerationMonitor (time per stage), StageOffloadPolicy (O1/O2 gates),
│                                  StageHashes (dev check: identical output at stage boundaries)
├── storage/                       StorageMonitor, FailureLog
├── metrics/                       LatencyHistogram, TickMonitor, StatusReport, Format
└── util/                          ProbeGuard, RateLimitedLog, Logs

com.chunkexpress.bench               separate dev-only mod (src/bench), never shipped with Chunk Express:
                                   the ice-road benchmark (docs/BENCHMARKS.md)
```

Packages for later work (a scheduler, integrity tooling) are created with their first real code,
not as empty placeholders.

Test-only tools live in `src/test/java/com/chunkexpress/tools` (`WorldDiff`, `StageHashDiff`), run by
the Gradle tasks of the same names.

The benchmark is a separate mod on purpose: it must run unchanged on vanilla, Moonrise and C2ME,
which Chunk Express refuses to run alongside. Its source set does not even see Chunk Express' classes;
when Chunk Express is present it talks to it only through the `/chunkexpress` command.

Everything under `chunk/`, `storage/`, `metrics/`, `generation/`, `config/Settings`, `bootstrap/`
(except the listener) and `integration/compatibility/` is **free of Minecraft runtime state**, so it is
unit-tested with plain JUnit. Only `integration/` touches game classes.

## 3. Data flow

```
                 NeoForge events (always on)                Probe hooks (optional, per compat rules)
                 ---------------------------                ---------------------------------------
ChunkEvent.Load/Unload, ChunkDataEvent.*, LevelEvent   ChunkMap.save / scheduleChunkLoad
        │                                              IOWorker.store / loadAsync  (+ whenComplete)
        │                                              MinecraftServer.reportChunk*Failure
        │                                              RegionFile.<init> / getChunkDataInputStream
        │                                              ChunkStep.apply (+ whenComplete)
        ▼                                              ServerChunkCache.getChunk (managedBlock)
ChunkEventListener ──► ChunkLifecycleTracker                    │
ServerTickEvent ─────► TickMonitor                              ▼
                                                  ProbeHooks ──► StorageMonitor, FailureLog,
                                                                 GenerationMonitor, ChunkWaitMonitor
                         all owned by ChunkExpressRuntime (one per server)
                                           │
                                           ▼
                   StatusReport ──► /chunkexpress status, shutdown summary
```

Two layers on purpose: the event layer needs no mixins and survives any chunk-system mod; the
probe layer gives storage latencies that no event exposes. Moonrise and C2ME are declared
incompatible in `neoforge.mods.toml`, so NeoForge never starts with them; for any future mod
rated CONFLICTING, the affected probes switch off and the event layer keeps working.

## 4. Threading model

| Component | Threads that call it | Safety mechanism |
|---|---|---|
| `ChunkLifecycleTracker` | server thread (NeoForge fires chunk events there) | Confined; off-thread calls are counted and ignored |
| `TickMonitor` | server thread | Confined |
| `StorageMonitor`, `FailureLog` | server thread, vanilla I/O pool, background executor | `LongAdder`, atomics, concurrent map, synchronized bounded deque |
| `GenerationMonitor` | worldgen mailbox, background pool, light mailbox, server thread | fixed `AtomicReferenceArray` indexed by status, `LongAdder`, histograms |
| `ChunkWaitMonitor` | server thread (waits), any thread (off-thread `getChunk` count) | `LongAdder`, histogram |
| `LatencyHistogram` | any | Lock-free `AtomicLongArray`; snapshots are not atomic cuts (documented) |
| `ChunkExpressRuntime.current()` | any | volatile reference |
| `ProbeRegistry` | class-loading thread, then server thread | concurrent map, volatile |
| `StatusReport`, command | server thread | reads confined monitors on their owner thread |

Rules that hold everywhere:

- Chunk Express creates no threads or executors of its own. Probe completion observers run on
  whichever vanilla thread completes the future, do O(1) work and never block. The stage
  offloads submit vanilla's own stage code to vanilla's worker pool (`Util.backgroundExecutor()`),
  exactly as vanilla does for the noise stage.
- Callbacks capture the runtime **at submission**, so a write finishing after shutdown updates
  the old server's counters, not the next world's.
- Every hook is wrapped by `ProbeGuard`: first failure logged with a stack trace, tenth
  disables the hook; `VirtualMachineError`s are rethrown.

## 5. Lifecycle

One `ChunkExpressRuntime` per server: created at `ServerAboutToStartEvent` (highest priority),
moved to RUNNING at `ServerStartedEvent`, STOPPING at `ServerStoppingEvent`, and retired at
`ServerStoppedEvent` (lowest priority). Singleplayer creates a new server per world opened, so
nothing leaks between worlds. Reaching STOPPED without STOPPING means a crash or failed start,
and is logged. At STOPPED, any write still pending is reported: by then vanilla has drained and
closed every I/O worker.

The configuration is snapshotted into an immutable `Settings` record at server start.

## 6. Mixin strategy

- Mojang mappings at runtime on NeoForge 1.21.1: no refmap needed.
- Probes: only `@Inject` (HEAD, RETURN, or around one call), never `@Overwrite`, `@Redirect` or
  cancellation. MixinExtras `@Share` carries start times between injection points.
- Optimisations: MixinExtras `@WrapMethod`, which composes with other mods' wrappers and calls the
  original exactly once.
- `defaultRequire = 0`: if another mod removes an injection point, the probe is skipped instead
  of crashing the game. The game tests (`./gradlew runGameTestServer`) assert that every probe is
  applied and fires on vanilla, which is what catches a broken target after a version bump.
- `ChunkExpressMixinPlugin` decides per mixin, before any Minecraft class loads, from the installed
  mods and `-Dchunkexpress.disableProbes=all|Name,Name`.

## 7. Key decisions

| # | Decision | Why | Revisit when |
|---|---|---|---|
| D1 | The first milestone changed no chunk behaviour at all | Understanding first; a baseline needs an unmodified system | done: optimisations came after the baseline |
| D2 | Event layer + optional probe layer | Events survive chunk-system mods; probes give latencies events cannot | — |
| D3 | Per-server runtime, not static state | Singleplayer runs many servers per JVM | — |
| D4 | COMMON config, snapshotted per server | Same settings for every world; nothing written into worlds; no mid-run changes | — |
| D5 | Only options that do something | An inert `parallelGeneration=true` would mislead users | every milestone |
| D6 | Fixed-size log-linear histograms (≤12.5% error, never under-reports) | No allocation on hot paths; bounded memory | if finer percentiles are needed |
| D7 | Dirty-flag revisions designed *before* async serialisation | Vanilla's boolean flag is only safe because serialisation is synchronous (PERSISTENCE §1) | storage work |
| D8 | No custom region writer, no database | Requirements; vanilla's write path is already copy-on-write | separate design review |
| D9 | GPL-3.0-only, clean-room | Forks and code building on Chunk Express stay open; no code copied from reference projects (REFERENCES.md) | — |
| D10 | First optimisation moves vanilla code to another thread, never rewrites it | The surface stage's dependencies match the noise stage vanilla already runs off-lane; reusing vanilla's own pattern keeps output identical and keeps the safety argument checkable (OPTIMIZATIONS.md O1) | — |
| D11 | Optimisation gates inspect other mods' applied mixins | An unknown mod patching the code we move off-thread is "unknown compatibility": fall back, and name the mixin config | — |
| D12 | The benchmark is a separate mod | It must run on Moonrise/C2ME, which Chunk Express refuses to load with | — |

## 8. What comes next and where it plugs in

The README's roadmap lists the order. Where each piece plugs in:

- **Lane queue measurement:** the worldgen mailbox in `ChunkMap.runGenerationTask`, to time how long a
  chunk waits before a stage starts; feeds `GenerationMonitor`.
- **Features in parallel:** needs a scheduler that runs a chunk's features only while it holds its
  3×3 neighbourhood (write radius 1), plus gates for every mod's feature code. A new `scheduler/`
  package; verification cannot use stage hashes for `features` (vanilla's own output depends on
  order), so it needs a different correctness argument.
- **Storage:** revision-tracked dirty state (D7), retries on failed writes, then parallel compression
  behind the single-writer region update. Fault-injection tests extend `FaultInjectionGameTests`.
- **Integrity:** keep a copy of a damaged entry's raw bytes before vanilla overwrites it, from the
  points where `RegionFileMixin` and `MinecraftServerMixin` already detect the damage.
