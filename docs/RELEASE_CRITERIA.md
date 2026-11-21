# Release criteria

What has to be true before a build is published on a given Modrinth channel. Every item is a
check that passes or fails, with its evidence recorded here. Status as of 2026-11-20.

The channel describes **how much testing this build has had**, not how many features it has. The
long-term roadmap (scheduler, storage, generation milestones up to 1.0 in the README) is separate:
the current feature set (O1, O2, diagnostics) can reach Release long before that.

Legend: ✅ done · 🔧 I can do it here · 👤 needs you (a decision, an approval, another machine, players)

## Beta — safe to run on real worlds, with backups

The features are complete for this version, verified beyond the one setup they were built on, and
nothing known can damage a world.

### Correctness
| # | Check | Status |
|---|---|---|
| B1 | Stage hashes identical (optimisations on vs off) for the overworld, one seed | ✅ surface and carvers, in and out, 0 of 3,165–4,507 chunks differ |
| B2 | Same for **3 seeds × all three vanilla dimensions** (overworld, Nether, End) | ✅ 2026-11-21, `-Ppregen` hash pairs (off vs on, surface and carvers in/out): seeds 20261117, 1, -4172144997902289642 × overworld, Nether, End; 0 differences in every pair (e.g. 3,579 and 3,612 shared chunks for the all-dimension runs, 1,451 for the End alone) |
| B3 | Same for other generator settings: **Large Biomes, Amplified**, and a single-biome world | ✅ 2026-11-21: Large Biomes (1,897 shared chunks), Amplified (1,930), single-biome surface (1,897): 0 differences |
| B4 | Same with data-pack terrain that uses vanilla rule types (**Terralith**, **Tectonic**): gates allow offloading, output must still match | 🔧 after 👤 approves the downloads |
| B5 | Fallback really happens in game, not only in unit tests: a test fixture mod adding (a) a custom surface rule type, (b) a custom carver type, (c) a mixin on `SurfaceSystem`, (d) a modded generator → each stage stays on the lane, with the reason logged | ✅ `scripts/fallback-test.sh` (2026-11-21): test-only rule type, carver type and generator (src/gametest `FallbackFixtures` + fixture dimensions) each keep both stages on the lane with the reason naming them; the Nether unaffected. (c) a foreign mixin: seen in game with ModernFix before its review (stages stayed on the lane naming its mixins), plus game test `mixinGateSeesMergedMixins` |
| B6 | A failure inside an offloaded stage ends in vanilla's fatal crash report (fault injected in a test), never a silent hang | ✅ `scripts/fault-test.sh` (2026-11-21): debug switch `chunkexpress.debug.failStage` throws in one chunk's offloaded surface stage; the game stops with vanilla's "Exception chunk generation/loading" crash report naming the fault, no hang |

### Worlds
| # | Check | Status |
|---|---|---|
| B7 | Existing world made without Chunk Express: add it, explore new terrain, remove it, reload — no errors, no changed chunks outside the new area | 🔧 |
| B8 | A world upgraded from an older Minecraft version (old/new chunk blending runs inside surface and carvers) | 🔧 needs an old world; I can create one only if 👤 provides one or approves downloading an older game version |
| B9 | Singleplayer: open several worlds one after another in one session; no state leaks between them | 🔧 |
| B10 | **Dedicated server** run (only integrated and game-test servers so far) | 👤 accept the Minecraft EULA for the dev server, then 🔧 |

### Performance (no regressions anywhere)
| # | Check | Status |
|---|---|---|
| B11 | Ice-road: Chunk Express beats vanilla, ≥ 3 alternating rides, with and without Create | ✅ |
| B12 | **Low core counts** (4 and 6 cores, simulated with `-XX:ActiveProcessorCount`): moved stages compete with noise for the same worker pool, so check that it is still no worse than vanilla | ✅ 2026-11-21 (docs/BENCHMARKS.md): 2, 4 and 6 cores, Chunk Express no worse than vanilla in every pair, frames unchanged; no core-count switch needed |
| B13 | Non-generation workloads unchanged: loading already-generated terrain, an idle server, saving | 🔧 |
| B14 | Soak: 2 hours of continuous generation (e.g. pre-generation) on one server; memory flat, no errors, clean shutdown check | 🔧 |

### Compatibility
| # | Check | Status |
|---|---|---|
| B15 | Game tests + one ride with Create + Farmer's Delight | ✅ |
| B16 | Game tests + one ride with each of: Lithium, FerriteCore, ModernFix, Chunky, Distant Horizons (generates terrain heavily), a TerraBlender mod (e.g. Biomes O' Plenty; gates should fall back) — and all of them together with Create | 🔧 after 👤 approves the downloads |

### Publishing
| # | Check | Status |
|---|---|---|
| B17 | Source public (GPL-3.0), issue tracker linked from the listing | 👤 |
| B18 | Version set (`0.1.0`, released on the Beta channel / as a GitHub pre-release), changelog and listing numbers match docs/BENCHMARKS.md | 🔧 |

## Release — recommended for everyone

Beta, plus time and breadth.

| # | Check | Status |
|---|---|---|
| R1 | Beta published for **at least 3–4 weeks** with real players, and no open bug that Chunk Express could damage a world, crash a game, or change terrain | 👤 |
| R2 | **Create scenario suite** (docs/COMPATIBILITY.md): large factories, contraptions crossing chunk borders, trains, chunk loading, restarts — Create raised to VERIFIED | 🔧 most of it can be scripted as game tests |
| R3 | Compatibility matrix for the whole priority list (Lithium, FerriteCore, Farmer's Delight, AE2, Immersive Engineering, Terralith, Tectonic), in combination | 🔧 + 👤 downloads |
| R4 | Kill tests: the game killed mid-generation (repeatedly), restarted; terrain hashes and region integrity checked | 🔧 started: `scripts/kill-test.sh`, 3 of 3 runs pass (docs/CRASH_TESTS.md); more kill points and an OS-level power-cut test (VM) to go |
| R5 | Performance numbers reproduced on a **second machine** (different CPU, ideally Linux) | 👤 |
| R6 | Windows and Linux dedicated servers; at least two Java 21 distributions | 👤 a Linux machine; 🔧 the rest |

## Recommendation

Skip Alpha, as you prefer — but publish as **Beta first**, not Release. B1–B18 can mostly be
done here in a few sessions; R1 needs real players over weeks and cannot be shortened. Going
straight to Release would mean claiming broad safety we have not observed.
