# Benchmarks

No performance claim without a reproducible number from here.

## The ice-road ride

A boat crosses 5,000 blocks of fresh terrain at full blue-ice speed (72 blocks/s), in a new
world with a fixed seed, and the benchmark records what a player feels: ride time, server tick
times, chunk-generation stalls, frame rate, and how much of the view distance is actually loaded.

Implemented in a **separate dev-only mod**, `chunkexpress_bench` (`src/bench`). It uses no Chunk Express
class, so the same ride runs unchanged on vanilla, Chunk Express, Moonrise or C2ME. (Chunk Express is
declared incompatible with Moonrise and C2ME, so those comparisons have to run without it anyway.)
It is based on an earlier boat-ride benchmark by the same author and keeps its seed (20261117),
height (200), speed (3.6 blocks/tick) and look-ahead (48 blocks), so results stay comparable.

### How it works

1. On the title screen the bench creates a new world (normal preset, fixed seed, creative,
   peaceful, no daylight/weather cycle, no mob spawning) and sets render distance (default 12).
2. 100 ticks after joining, it spawns a boat at height 200 above spawn, seats an invisible armour
   stand as the driver and the player **on the armour stand**, facing east.
3. Every server tick it lays the road 48 blocks ahead of the boat — blue ice five wide, cobblestone
   kerbs, three blocks of air above — then moves the boat 3.6 blocks east.
4. When the road enters a new chunk, the bench checks whether normal loading has already made it
   ready. If not, it fetches it synchronously and the server thread waits: **that wait is a stall**.
5. At 5,000 blocks it writes the report and quits the game.

### Two traps the design avoids

Both were found while building this, and both silently distort results:

- **The player must not be the boat's driver.** Vanilla puts a boarding player *first* in a
  vehicle's passenger list, which makes the player the driver. The client then steers the boat
  too, fighting the server's push: the server logs "moved wrongly!" every tick (940,000 log lines
  in one ride), the logging itself eats the server thread, and the flood of output crashed the
  Gradle daemon at shutdown. Seating the player on the armour stand keeps the stand the driver.
- **The road must not use neighbour updates.** A block placed with neighbour updates at a chunk
  edge pokes the next chunk and forces it to load on the server thread. That hides the real stalls
  (the chunk is "ready" by the time the bench checks) and disturbs the generation it is measuring.
  The road uses `UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE` only; the kerbs are solid cobblestone so no
  shapes need updating.

The report also counts these failure modes indirectly: total road-building time per tick should
equal the stall time (if it is much larger, something else in the road builder is waiting).

### Running it

```bash
./gradlew runIceRoad
```

```bash
./gradlew runIceRoadChunkExpress
```

Options: `-Plength=5000 -Pseed=20261117 -PrenderDistance=12 -Pjvm="-Xmx6G ..."`.
To compare another engine, pass its jars: `./gradlew runIceRoad -Pwith=path/to/moonrise.jar`
(they are copied into `run/bench/mods`, which is reset on every ride). A standalone jar for any
other NeoForge 1.21.1 instance: `./gradlew benchJar`, then start that game with
`-Dchunkexpress.bench.iceroad=5000`.

Both runs use `run/bench` as the game folder, delete the ride world before starting, and append to
`run/bench/chunkexpress-bench/iceroad.txt` (readable) and `iceroad.csv` (one row per ride). With
Chunk Express present, the bench resets its counters at the start and appends `/chunkexpress status`
to the report.

**Leave the window alone during a ride** (about 2 minutes in all): pausing or quitting aborts it,
and an aborted ride records nothing (it logs a warning).

### What each number means

| Report line | Meaning |
|---|---|
| ride | Wall time for 5,000 blocks. The boat moves per tick, so slow ticks slow the ride — but the server runs ticks back to back to catch up after a stall, so ride time hides most stalls. Read it last. |
| server ticks | Tick *work* (`ServerTickEvent.Pre` → `Post`, excluding the bench's own road building). What vanilla calls MSPT. |
| tick interval | Tick start to next tick start. Includes server-thread work *between* ticks and stalls. The number that matches what players feel. |
| chunk stalls | Road chunks not ready 48 blocks ahead of a 72 blocks/s boat, and how long the server thread waited for each. The main chunk-engine number. |
| road building | All server-thread time spent laying the road. Should ≈ stall time. |
| new chunks | Chunks generated during the ride. |
| frames | Client frame rate and frame times. |
| world loaded ahead | Chunks the client has loaded ahead along the road, of the render distance. |

## Results

Machine: 16 cores, NVIDIA RTX 4080 SUPER, Windows 11, Oracle Java 21.0.8, heap up to 8 GB
(default), render distance 12, NeoForge 21.1.265, 5,000 blocks, seed 20261117. All rides on
2026-11-20, in this order (rides alternate so that slow drift in the machine affects all engines).

| Time | Engine | Ride | Stalled road chunks (of 624) | Waited | Worst stall | Tick interval p95 / worst | New chunks | Loaded ahead (avg, % under 4) |
|---|---|---|---|---|---|---|---|---|
| 12:43 | vanilla | 71.7 s | 487 | 41.5 s | 1062 ms | 181 / 1184 ms | 2,144 | 7.5, 15% |
| 12:45 | Chunk Express observe-only | 71.5 s | 540 | 45.4 s | 610 ms | 179 / 673 ms | 1,849 | 6.9, 16% |
| 12:51 | vanilla | 72.9 s | 485 | 41.7 s | 1758 ms | 181 / 2005 ms | 2,192 | 7.0, 14% |
| 12:53 | Chunk Express observe-only | 73.8 s | 527 | 48.6 s | 2117 ms | 203 / 2730 ms | 2,169 | 6.8, 22% |
| 12:54 | vanilla | 71.5 s | 154 | 12.2 s | 1231 ms | 70 / 1239 ms | 3,551 | 4.1, 21% |
| 12:58 | Chunk Express + offloadSurface | 69.4 s | 30 | 2.5 s | 438 ms | 61 / 444 ms | 4,456 | 5.5, 4% |
| 13:00 | Chunk Express observe-only | 71.5 s | 582 | 48.6 s | 569 ms | 201 / 829 ms | 1,834 | 7.4, 21% |
| 13:02 | Chunk Express + offloadSurface | 69.4 s | 77 | 8.3 s | 902 ms | 63 / 924 ms | 4,521 | 5.6, 10% |
| 13:04 | vanilla | 73.6 s | 545 | 44.4 s | 579 ms | 185 / 812 ms | 1,854 | 7.6, 13% |
| 13:05 | Chunk Express + offloadSurface | 69.4 s | 62 | 7.5 s | 1467 ms | 65 / 1474 ms | 4,395 | 5.6, 7% |

**Observe-only Chunk Express vs vanilla:** no measurable difference (3 vs 4 rides, overlapping ranges).
**With `offloadSurface`:** every ride beat every ride without it; details in docs/OPTIMIZATIONS.md (O1).
Run-to-run variance is large: one vanilla ride (12:54) had a third of the usual stalls. Compare
engines on several alternating rides, never on one.

## Engine comparison: vanilla, Chunk Express, C2ME (with Create + Farmer's Delight)

2026-11-20, same machine and settings as above, every ride with **Create 6.0.10** (bundling
Flywheel 1.0.6, Ponder 1.0.82, Registrate) and **Farmer's Delight 1.3.4** loaded. Three rides per
engine, alternating (vanilla, Chunk Express, C2ME, repeat). Chunk Express with both optimisations
(`offloadSurface`, `offloadCarvers`). C2ME is the official NeoForge build
`c2me-neoforge-mc1.21.1-0.4.0-alpha.0.124.jar` with its default configuration, loaded without
Chunk Express (the two refuse to load together). Moonrise is not compared: it is declared incompatible
with Chunk Express, and it is incompatible with Create, so it cannot run in this mod set.

Medians, with the range of the three rides in brackets:

| | Vanilla | Chunk Express (O1+O2) | C2ME |
|---|---|---|---|
| Ride (5,000 blocks, full speed = 69.4 s) | 71.7 s [69.4–71.9] | 69.4 s [69.4–69.4] | 69.4 s [69.4–69.4] |
| Road chunks not ready in time (of 624) | 272 [109–433] | 4 [0–9] | 0 [0–0] |
| Server thread waiting for chunks | 26.2 s [11.1–33.1] | 0.45 s [0–0.67] | 0 s |
| Tick interval p95 | 128 ms [66–179] | 58 ms [58–59] | 55 ms [55–56] |
| Worst tick interval | 676 ms [642–1009] | 245 ms [139–269] | 75 ms [68–77] |
| Tick work average | 4.7 ms [4.5–5.5] | 7.4 ms [7.2–7.4] | 6.2 ms [5.8–6.3] |
| Chunks loaded ahead on screen (of 12) | 4.4 [3.7–8.0] | 6.9 [6.8–7.7] | 11.3 [10.3–11.5] |
| Time with under 4 chunks ahead | 13.5% [5.7–37.4] | 0.7% [0–1.0] | 0% |
| New chunks generated during the ride | 3,422 [2,306–3,728] | 5,388 [5,240–5,780] | 8,538 [7,578–8,660] |
| Frames: average / 1% low | 117 / 113 fps | 117 / 112 fps | 116 / 111 fps |
| Frame time p99 | 13.8 ms [13.4–16.4] | 16.9 ms [16.7–19.1] | 22.0 ms [21.6–22.7] |
| GC time during the ride | 0.97 s [0.85–1.07] | 1.40 s [1.31–1.41] | 1.65 s [1.50–1.66] |

What this says:

- **Both engines remove almost all of vanilla's chunk stalls** in this setup. Chunk Express cut the
  server's waiting from a median 26 s per ride to under half a second; C2ME to zero.
- **C2ME is ahead on throughput and smoothness here.** It generated about 60% more chunks than
  Chunk Express, kept nearly the whole view distance loaded ahead (11.3 of 12 vs 6.9), and its worst
  tick gap was 75 ms against Chunk Express' 245 ms. Chunk Express moves two stages off the serial lane;
  C2ME parallelises much more of generation and rewrites chunk I/O and scheduling.
- **The cost of more generation shows on the client a little**: frame-time p99 and GC time rise with
  the amount of terrain generated (vanilla < Chunk Express < C2ME). Average frame rates are equal.
- **What this benchmark does not measure:** whether the generated terrain is the same as vanilla's.
  Chunk Express' output is verified bit-identical at every stage it changes (docs/OPTIMIZATIONS.md); the
  same check cannot run with C2ME installed, because it needs Chunk Express' hooks. Nor does it measure
  long-term world safety, behaviour inside Create machinery, or loading of existing worlds.
- With Create and Farmer's Delight loaded, vanilla stalled less than in the vanilla-only rides
  above (median 272 vs ~500 road chunks): mod sets change timing, so engines are only compared within
  the same mod set.

## Vanilla terrain is not reproducible chunk by chunk

`./gradlew worldDiff` compared the kept worlds of two rides without any optimisation (vanilla and
Chunk Express observe-only, same seed): 812 of the 1,998 chunks fully generated in both differ, in
48,177 block positions, and every difference is a feature block (ores and stone blobs, clay, moss,
azalea, cave vines). Biomes never differ. Vanilla 1.21.1's feature stage lets a chunk's features
write into its neighbours, so the result depends on the order in which chunks happen to be generated,
which varies with timing.

Consequence for every generation change: "the same world as vanilla" has to be checked **at stage
boundaries before features**, where output depends only on seed and position. Chunk Express does this
with `-Dchunkexpress.debug.stageHashes=surface,carvers` and `./gradlew stageHashDiff` (docs/OPTIMIZATIONS.md).

## What the first rides show

- **Vanilla 1.21.1 cannot keep up with a 72 blocks/s player.** About 80% of the road's chunks were
  not ready three chunks ahead, and the server thread spent ~40 s of a 72 s ride waiting for them.
  This is the gap a chunk engine has to close.
- **MSPT hides it.** Tick work averaged ~5 ms while tick intervals reached ~180 ms at p95 and over
  a second at worst. Chunk Express now reports both.
- **Chunk Express' probes agree with the benchmark.** Its chunk-wait probe counted 541 waits / 45.45 s;
  the bench counted 540 stalls / 45.4 s in the same ride.
- **Where generation time goes (Chunk Express ride):** of the work done on each dimension's single
  "worldgen" lane, `surface` took 19.3 s, `features` 8.2 s, `structure_starts` 4.1 s and
  `carvers` 2.5 s — about 34 s of serial work in a 72 s ride. `noise` and `biomes` run on the
  background pool (noise p50 27 ms per chunk). Moving `surface` off that lane is optimisation O1.

## Low core counts (docs/RELEASE_CRITERIA.md B12), 2026-11-21

The JVM was told it has fewer cores with `-XX:ActiveProcessorCount=N` (vanilla sizes its worker pool
`N - 1`; Chunk Express adds no threads, it lets surface and carvers share that pool with noise).
No mods besides Chunk Express, seed 20261117, render distance 12, vanilla and Chunk Express
alternating. Not a clean-machine measurement (other work may have been running), but each pair ran
back to back under the same conditions.

| Cores | Engine | Waiting for chunks | Average TPS | Worst tick interval | Chunks ahead (of 12) | Frames |
|---|---|---|---|---|---|---|
| 2 (3,000 blocks) | vanilla | 130.7 s | 6.2 | 3,068 ms | 1.1 | 117 fps |
| 2 (3,000 blocks) | Chunk Express | 113.7 s | 7.1 | 1,449 ms | 1.3 | 117 fps |
| 4 | vanilla | 74.8 s / 62.5 s | 16.2 / 17.3 | 963 / 969 ms | 2.5 / 2.8 | 117 fps |
| 4 | Chunk Express | 29.5 s / 53.6 s | 19.4 / 18.2 | 1,017 / 963 ms | 3.7 / 3.0 | 116 fps |
| 6 | vanilla | 36.2 s / 12.6 s | 20.0 / 19.4 | 890 / 963 ms | 3.6 / 4.1 | 117 fps |
| 6 | Chunk Express | 2.3 s / 0.4 s | 20.0 / 20.0 | 433 / 242 ms | 6.0 / 6.6 | 117 fps |

Chunk Express is no worse than vanilla at any of these core counts, and frame rate is unchanged, so
there is no core-count switch: the gates stay the only reason a stage keeps vanilla's lane. Average
server tick work is slightly higher with Chunk Express (more chunks arrive per tick to be loaded).

