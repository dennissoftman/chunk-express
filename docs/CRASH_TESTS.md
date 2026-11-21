# Crash tests

Does a world survive the game dying in the middle of generating and saving chunks, with Chunk Express
installed?

## Hard-kill test (`scripts/kill-test.sh`)

```bash
scripts/kill-test.sh runIceRoadChunkExpress 2000
```

```bash
scripts/kill-test.sh runIceRoad 2000
```

1. **Ride.** An ice-road ride starts in a new world. By the kill point thousands of chunks have been
   generated, and chunks behind the boat are being unloaded and saved continuously, so region writes
   are in flight when the game dies.
2. **Kill.** Once the ride passes the given number of blocks, plus a random 0–3 s, the game process is
   killed outright: `taskkill /F` on Windows (`TerminateProcess`), `kill -9` elsewhere. No shutdown
   hooks run and Minecraft gets no chance to save — the same as a JVM crash.
3. **Offline check.** `./gradlew regionCheck` reads every region file of every dimension (chunks,
   entities, POI) with its own reader, independent of the game's code: header entries inside the
   file and not overlapping, every entry with a sane length and known compression, decompressing and
   parsing as NBT, stored positions matching their slots, a chunk status present, block and biome
   data decodable.
4. **Reopen.** The same world is opened again and the ride runs the full road, through the area that
   was being written when the game died and on into new terrain. The run's log is searched for every
   vanilla and Chunk Express message about chunks or region data that could not be read.

It passes only if the offline check finds nothing, the reopened ride completes, and no such message
appears.

**What "survive" means here.** Chunks that were generated but not yet saved when the game died are
lost and generated again on the next visit: that is vanilla behaviour, not damage. The test looks
for damage — data that is present but unreadable, wrong or inconsistent.

**What it does not cover.** An operating-system crash or a power cut: after a process kill the OS
still writes out whatever the game had handed to it. Testing that needs a virtual machine whose power
can be cut mid-write; it is listed in docs/RELEASE_CRITERIA.md (R4).

## Results

2026-11-20, Windows 11, 16 cores, render distance 12, seed 20261117, Chunk Express with both
optimisations on. Kill point plus a random 0–3 s.

| Run | Killed at | Region files | Entries checked (chunks) | Offline check | Reopened ride | Chunk/region errors on reopen | Result |
|---|---|---|---|---|---|---|---|
| Chunk Express | 2,012 blocks | 29 | 5,626 (5,452) | no problems | completed | 0 | PASS |
| Chunk Express | 3,020 blocks | 41 | 8,602 (8,372) | no problems | completed | 0 | PASS |
| Vanilla (control) | 2,012 blocks | 29 | 3,800 (3,683) | no problems | completed | 0 | PASS |

In these runs neither vanilla nor Chunk Express left any damage: vanilla's write path (new sectors
first, then the header; docs/PERSISTENCE.md) held up under a process kill, and Chunk Express' changes
did not weaken it. Chunk Express had more entries on disk at the same kill point because it generates
more terrain in the same time.

Three runs show the behaviour, not a statistical guarantee; more runs at more kill points, and the OS
crash / power-cut case, are in docs/RELEASE_CRITERIA.md (R4).
