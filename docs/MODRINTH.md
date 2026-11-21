# Modrinth listing (draft)

Fields to enter when creating the project. Keep the numbers in the description in step with
docs/BENCHMARKS.md; never publish a number that is not there.

| Field | Value |
|---|---|
| Name | **Chunk Express** |
| Icon | `art/icon.png` (512×512, 32×32 pixel art scaled 16×; regenerate with `python art/make_icons.py`) |
| URL slug / project ID | `chunk-express` (free as of 2026-11-20; `chunkexpress`, the mod id, is free too) |
| Summary (≤ 256 chars) | Faster chunk generation with terrain identical to vanilla: runs the safe parts of world generation on your spare CPU cores. |
| Categories | Optimization (primary), Utility, Worldgen |
| Environment | Server-side, works in singleplayer (install it in the game for singleplayer; a server needs it, joining players don't) |
| Loaders / versions | NeoForge, Minecraft 1.21.1 (NeoForge 21.1.265 or newer in 21.1) |
| License | GPL-3.0-only |
| Source / issues | https://github.com/dennissoftman/chunk-express (issues: /issues) |
| Release channel | Beta once docs/RELEASE_CRITERIA.md B1–B18 pass; Release after R1–R6 |

Summary length: 123 characters (Modrinth's card shows about two lines; keep it short, plain text, without the project name).

---

## Description

<!-- Everything below this line is the Modrinth description (Markdown). -->

**Generates new terrain faster, without changing a single block of it.**

Exploring fast - by elytra, ice boat or in the Nether - makes the server wait for new chunks. Chunk
Express runs the parts of vanilla world generation that are safe to run in parallel on your spare CPU
cores, and checks that the result is identical to vanilla.

![Vanilla vs Chunk Express 0.2.0, same seed, real time](https://cdn.modrinth.com/data/ZgIKW9zV/images/23bdedc5e2f16274b89d46664b19038409d40045.gif)

*Same seed, real time, full speed over new terrain. Amber: vanilla slowed down. Red: vanilla stopped,
waiting for chunks.*

## Why install it

- **No more waiting for chunks.** Crossing 5,000 blocks of new terrain at ice-boat speed, the server
  waited 11 s for chunks in vanilla and not once with Chunk Express.
- **Your world stays vanilla.** Terrain is checked block for block against vanilla across seeds,
  dimensions and world types. Nothing is added to your save, and you can remove the mod at any time.
- **Safe by design.** It steps aside on its own where another mod changes the same world generation,
  and says why in the log. It has been crash-tested by killing the game mid-save.
- **Server-side.** Players joining a server don't need it.

## Compared with C2ME

| | Vanilla | Chunk Express | C2ME |
|---|---|---|---|
| Waiting for chunks per ride | 11 s | 0 s | 0 s |
| Worst server hiccup | 680 ms | 240 ms | 83 ms |

C2ME is faster; Chunk Express keeps terrain identical to vanilla and changes far less of the game.
Use one or the other: Chunk Express won't start alongside C2ME or Moonrise.

## Compatibility

**Tested:** Create, Farmer's Delight, Lithium, FerriteCore, ModernFix, Terralith, JEI, Jade,
Create: Infinite Railways, Railways Untold, Steam 'n' Rails, ScalableLux.

**Not tested yet:** other mods, for example Tectonic, Biomes O' Plenty, Distant Horizons and Chunky.
That doesn't mean they don't work - only that we haven't checked. Reports are welcome.

Settings are in `config/chunkexpress-common.toml`; `/chunkexpress status` shows what it is doing.
