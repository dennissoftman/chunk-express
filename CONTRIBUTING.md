# Contributing to Chunk Express

Thanks for helping. Chunk Express has one rule above all others: **world integrity first** — a change
that makes chunks faster but can lose or alter world data is not accepted, however fast it is.

## Ground rules

- **Clean-room.** Read C2ME, Lithium, Moonrise or Paper to understand ideas, but never paste or
  closely transliterate their code. If reuse is ever proposed, it is a deliberate decision, and the
  commit must name the source file and commit.
- **Evidence over claims.** A performance change comes with benchmark numbers (docs/BENCHMARKS.md);
  a "this is thread-safe" claim comes with the reasoning from the 1.21.1 sources, written down where
  the code is.
- **Vanilla behaviour unless proven otherwise.** Anything that cannot be shown safe — another mod's
  code, a modded generator — keeps vanilla behaviour, decided at run time and logged with the reason.
- **Probes only observe.** Code in `integration/mixin/hook/probe` must never change arguments, return
  values, control flow or futures. Behaviour changes go in `hook/optimization`, behind a config switch.

## Building and testing

Java 21 is required (the Gradle wrapper downloads everything else).

```bash
./gradlew build
```

```bash
./gradlew runGameTestServer
```

Both must pass. `build` treats compiler warnings as errors.

## What a new optimisation needs

See the checklist at the top of [docs/OPTIMIZATIONS.md](docs/OPTIMIZATIONS.md):

1. A measurement showing the cost it attacks.
2. A safety argument from the 1.21.1 sources, including when it does *not* hold.
3. Gates that fall back to vanilla on their own, with a logged reason.
4. A config switch where `false` is exactly vanilla.
5. Game tests, and for generation changes stage-boundary hashes showing identical output
   (`-Dchunkexpress.debug.stageHashes`, `./gradlew stageHashDiff`).
6. Ice-road rides with it on and off, at least three alternating rides each.

## Bugs that may involve world data

Please back up the world first and keep the backup: do not let the game save over a damaged region
file. Include the output of `/chunkexpress status` and the log lines starting with `Chunk Express:`.

## Licence

Chunk Express is GPL-3.0-only. By contributing, you agree that your contribution is licensed under the
same terms.

## Releasing

1. Write the player-facing notes under `## <version> - unreleased` in CHANGELOG.md, and set
   `mod_version` in gradle.properties to the same version.
2. Run `scripts/release.sh <version>`: it dates the changelog section, commits, tags `v<version>` and
   pushes.
3. The tag starts `.github/workflows/release.yml`: build, unit and game tests, then a GitHub Release
   with the jar attached and that changelog section as the notes (0.x versions are marked
   pre-release).
