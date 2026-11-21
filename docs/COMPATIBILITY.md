# Compatibility

## Statuses

| Status | Meaning | Evidence needed |
|---|---|---|
| VERIFIED | Passed the scenario suite below against a pinned version | Test log + versions recorded here |
| COMPATIBLE | Known to work or shares no code paths, not covered by our suite | A written rationale |
| UNKNOWN | Not tested — Chunk Express uses conservative behaviour | — |
| CONFLICTING | Replaces code Chunk Express hooks; affected Chunk Express features are switched off | Source review |
| INCOMPATIBLE | Must never run with Chunk Express. Declared `type="incompatible"` in `neoforge.mods.toml`, so NeoForge refuses to start and shows the reason | Source review |

A unit test (`CompatibilityManagerTest.noModIsClaimedVerifiedIn01`) fails if any entry claims
VERIFIED or COMPATIBLE, so raising a status is always a deliberate, reviewed change.

## Current table

| Mod | Mod id | Status | Effect today |
|---|---|---|---|
| Moonrise | `moonrise` | INCOMPATIBLE | NeoForge refuses to start with both installed (any version) |
| C2ME | `c2me` | INCOMPATIBLE | same — the id of the official NeoForge jar, which bundles all C2ME modules |
| C2ME modules | `c2me_base` | INCOMPATIBLE | same — every C2ME module depends on it, so this also catches modules installed one by one |
| Create | `create` | UNKNOWN | none — priority target; smoke-tested (below) |
| Lithium | `lithium` | CONFLICTING | chunk-wait probe off: Lithium replaces `ServerChunkCache.getChunk`, and Mixin cannot inject into a method another mixin replaced. O1/O2 unaffected |
| FerriteCore | `ferritecore` | UNKNOWN | none |
| Farmer's Delight | `farmersdelight` | UNKNOWN | none — smoke-tested (below) |
| Applied Energistics 2 | `ae2` | UNKNOWN | none |
| Immersive Engineering | `immersiveengineering` | UNKNOWN | none |
| Terralith | `terralith` | UNKNOWN | none: its surface rule uses only vanilla types, and its library Lithostitched 1.8.1's patches are reviewed (docs/OPTIMIZATIONS.md), so O1/O2 stay on |
| Tectonic | `tectonic` | UNKNOWN | none — worldgen; identity check pending |
| ModernFix | `modernfix` | UNKNOWN | none — its chunk patches not reviewed yet |
| ScalableLux | `scalablelux` | UNKNOWN | none — Chunk Express does not touch lighting |
| Noisium | `noisium` | UNKNOWN | none — optimises the noise stage, which Chunk Express does not move |
| Chunky | `chunky` | UNKNOWN | none |

The hard block is enforced by NeoForge before any mod code runs. The mod ids were taken from the
projects' own `neoforge.mods.toml` (Moonrise `mc/1.21.1`; C2ME's official NeoForge port,
[RelativityMC/C2ME-neoforge](https://github.com/RelativityMC/C2ME-neoforge) `backports/1.21.1`).
The block was checked by starting the dev server with stand-in jars declaring each id: NeoForge
refused all three with our reason. `ModsTomlTest` keeps the block list and this table in step.
Unofficial ports that use other mod ids are not covered. As a second line of defence, the
storage and load probes still switch off if one of these mods is ever present.

How the optimisations treat other mods: the stage offloads (O1 surface, O2 carvers) do not rely on
this table. They inspect what actually runs in the stage they move: the dimension's generator class,
mod-registered surface-rule and carver types, and other mods' mixins applied to the stage's classes
(read from Mixin's own records, naming the mod's mixin config). Any of those makes the stage stay
on vanilla's lane for that dimension. So an UNKNOWN mod never has its code moved to another thread.

Operators can also switch hooks off by hand: `-Dchunkexpress.disableProbes=all` or a
comma-separated list (`ChunkMapMixin,IOWorkerMixin,MinecraftServerMixin,RegionFileMixin,ChunkStepMixin,ServerChunkCacheMixin,StageOffloadMixin`),
and each optimisation with its own switch in `[optimizations]`.

## Smoke tests run so far

Not the scenario suite below — these only show that nothing breaks at the most basic level.

| Mods | Versions | Test | Result |
|---|---|---|---|
| Create + Farmer's Delight | Create 6.0.10 (bundles Flywheel 1.0.6, Ponder 1.0.82, Registrate), Farmer's Delight 1.3.4 | All Chunk Express game tests (`./gradlew runGameTestServerCompat`, 2026-11-20) | 11/11 pass; neither mod patches the surface or carver code, so O1 and O2 stay active |
| Create + Farmer's Delight | as above | Ice-road rides with Chunk Express (O1+O2), vanilla and C2ME (docs/BENCHMARKS.md) | see BENCHMARKS.md |
| Terralith + Lithostitched | Terralith 2.6.2, Lithostitched 1.8.1 | 4,000-block ice-road rides with stage hashes, O1/O2 off vs on (2026-11-20) | O1 and O2 run on worker threads (gate checks only the surface-rule types a dimension uses; Lithostitched's 6 patches on the surface code reviewed for 1.8.1). Stage hashes at surface and carvers in/out: **0 of 4,387 shared chunks differ**. Before 2026-11-20 both stages stayed vanilla here |
| Terralith + Lithostitched + Create + Farmer's Delight | as above | 1,000-block ride (2026-11-21) | ride completes; both stages on worker threads, each reviewed patch named in the log |
| ModernFix | 5.27.27 | 4,000-block rides with stage hashes, O1/O2 off vs on; game tests with the popular set (2026-11-21) | its 7 patches on the surface code (`optimize_surface_rules`, `worldgen_allocation`, `chunk_deadlock`) reviewed for 5.27.27+mc1.21.1, so O1 and O2 stay on. Stage hashes at surface and carvers in/out: **0 of 4,874 shared chunks differ**. 12/12 game tests |
| Create: Infinite Railways + Railways Untold + Steam 'n' Rails (+ Create) | 0.1.2, 1.2.2, 0.3.0-beta.2 | 5,000-block ride; game tests with every compat mod; hard-kill test at 3,020 blocks (2026-11-21) | ride completes, O1/O2 on (none of them patch the moved code); 12/12 game tests; kill test PASS (40 region files, 6,702 chunks, no problems; reopened, 0 chunk errors). Their station scan does not go through the generation stages, so Chunk Express does not speed it up |
| Popular set: Create, Farmer's Delight, Terralith, Lithostitched, Lithium 0.15.4, FerriteCore 7.0.3, ModernFix 5.27.27, JEI 19.64.0.455, Jade 15.10.6 | as listed | Ice-road ride; all game tests; hard-kill test at 3,020 blocks (2026-11-20) | ride completes; 11/11 game tests; kill test PASS (41 region files, 6,241 chunks, no problems; reopened, 0 chunk errors). First run crashed at start-up on Lithium; fixed by marking Lithium CONFLICTING for the chunk-wait probe |

Moonrise is not tested: it is declared incompatible with Chunk Express, and it is incompatible with
Create.

Jars for these runs live in `compat-mods/` (git-ignored); `./gradlew runGameTestServerCompat` copies
every jar from there (except C2ME and Moonrise) into `run/compat/mods`.

## Not tested yet

Status UNKNOWN: we have not run them with Chunk Express, which says nothing either way about whether
they work. Tectonic, Biomes O' Plenty and other TerraBlender-based mods, Distant Horizons, Chunky,
Applied Energistics 2, Immersive Engineering, Noisium, ScalableLux, and every mod not named on this
page. For world generation the gates still apply: a mod-added type or an unreviewed patch on the
moved code keeps that stage vanilla, logged with the reason.

## Scenario suite (to be automated)

Each scenario runs on a dedicated server and an integrated server, with Chunk Express off and on,
and compares the world afterwards (region-level NBT diff with known-volatile fields excluded).

**Create**
1. Large kinetic factory spanning chunk borders, running at high speed for 30 min.
2. Moving contraptions (trains, elevators, deployers) crossing chunk and region borders.
3. Contraption in flight during chunk unload/reload (player walks away and back).
4. Create-driven chunk loading (trains keep chunks loaded).
5. Server stop with machinery running; restart; compare block entities and contraption state.
6. Repeated kill -9 during autosave, then restart and integrity report.

**Combinations** — not just single mods: Create + Lithium + FerriteCore; Create + AE2 + IE;
Terralith + Tectonic for generation; the full priority list together.

Results are recorded here with exact versions, seeds, and the Chunk Express commit.
