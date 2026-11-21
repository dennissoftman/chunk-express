# Design: chunk saving partly off the server thread (O4, `offloadChunkEncoding`)

Status: **on hold** (2026-11-21). Profiling showed the part it moves is about 9% of the server
thread's work while exploring, against 74% for fluid settling; O4 (fast fluid settling) was done
instead. Revisit if a real server profile shows saving as a bottleneck.

## Why

Profile of a full-speed ice-road ride (O1-O3 on, Java Flight Recorder, 2026-11-21): chunk
serialization when chunks unload (`ChunkMap.processUnloads` -> `ChunkMap.save` ->
`ChunkSerializer.write`) is 217 of 2,090 server-thread samples, about 10% of the server thread's
work while exploring. The largest single part of it is packing each section's block-state palette
(`PalettedContainer.pack`, 65 samples on its own).

Expected gain: up to that 10%, minus the cost of the copies below. Modest but real, and it lands on
the server thread, where it costs tick time directly.

## What vanilla does today (1.21.1, NeoForge 21.1.265)

`ChunkMap.save(chunk)`, on the server thread:

1. `poiManager.flush(pos)`; returns if the chunk is not unsaved.
2. `chunk.setUnsaved(false)` - **before** anything is written.
3. `ChunkSerializer.write(level, chunk)` builds the whole NBT: per section the block-state and biome
   palettes (`BLOCK_STATE_CODEC.encodeStart(...)` = palette packing) and the block/sky light layers
   from the light engine; then heightmaps, block entities (`getBlockEntityNbtForSaving`, i.e. mod
   code for modded block entities), block and fluid ticks, structure starts and references, and
   NeoForge data attachments (`writeAttachmentsToNBT`, mod code).
4. NeoForge posts `ChunkDataEvent.Save(chunk, level, tag)` - mods may read and add to the tag.
5. `write(pos, tag)` hands the tag to the `IOWorker`, which writes it to the region file later
   (and coalesces writes to the same chunk: the latest tag wins).
6. Errors are reported with `reportChunkSaveFailure`; there is no retry.

The integrity properties that already hold and must keep holding: the region file only ever gets a
complete chunk entry (IOWorker + RegionFile, unchanged); reads of a chunk see the newest tag handed
to the IOWorker even before it is on disk (IOWorker checks its pending writes); `save-all flush`
and shutdown write everything handed over before they return.

## Proposal

Move only step 3's **section palette packing** off the server thread. Everything that runs mod code
or reads live, shared state stays where it is.

- On the server thread, exactly as now: steps 1, 2, 4 (after the merge, see below), 6, and from step
  3 everything except the two palette encodings: light layers, heightmaps, block entities, ticks,
  structures, attachments.
- Per section, the server thread takes a **copy** of the block-state and biome `PalettedContainer`s
  (`copy()`: an array copy of the packed storage, no re-packing) at the moment the save starts.
- A worker encodes the copies (`BLOCK_STATE_CODEC` / biome codec, vanilla's own code) and the
  result is merged into the tag at the same keys vanilla uses.
- Back on the server thread, `ChunkDataEvent.Save` is posted with the complete tag, then the tag is
  handed to the IOWorker (step 5).

The NBT must be **identical** to what vanilla writes for the same chunk state. That is the
verification target, like the stage hashes for O1-O3.

## Failure cases and how each is handled

| # | Case | Risk if ignored | Handling |
|---|---|---|---|
| 1 | The chunk is changed after the save starts | the saved state mixes old and new | the encoded data comes from copies taken at the start; `setUnsaved(false)` happens at the same point as in vanilla, so a later change marks it unsaved again and it is saved again - vanilla's own semantics |
| 2 | The chunk is unloaded and **read again** before its encoded tag reached the IOWorker | the read returns the older tag from disk; the next save overwrites newer data with older: **data loss** | every read of a chunk position waits for that position's pending save to be handed to the IOWorker first (reads are already asynchronous in vanilla) |
| 3 | Two saves of the same chunk finish encoding out of order | an older tag replaces a newer one | saves are chained per chunk position: a save is handed to the IOWorker only after the previous save of that position was |
| 4 | `save-all flush`, autosave, world close, server stop | they return while encoded data is still in flight; that data is never written | every path that flushes or closes the chunk storage first waits until no save is pending, then lets vanilla flush |
| 5 | Encoding fails on the worker (exception) | the chunk is marked saved but nothing is written | the same save is redone the vanilla way on the server thread from the copies; if that fails too, it is reported exactly like vanilla (`reportChunkSaveFailure`) |
| 6 | The game is killed (crash, `kill -9`, power loss) mid-encode | partial data on disk | nothing partial can reach the region file: the IOWorker and RegionFile are unchanged, and a tag reaches them only when complete. As in vanilla, changes not yet handed to the IOWorker are lost - the kill tests check that this loses nothing more than vanilla does |
| 7 | A mod listens to `ChunkDataEvent.Save`, patches `ChunkSerializer`/`ChunkMap.save`, or replaces chunk storage | it sees a different timing or bypasses the split | gate, like O1-O3: such a mod (event listeners other than NeoForge's own, unreviewed mixins on those classes) keeps saving fully vanilla, logged with the reason |
| 8 | Many saves at once (mass unload, teleport) | memory for the copies grows | a bound on saves in flight; beyond it, saves are done the vanilla way on the server thread |
| 9 | The worker pool is busy with generation | saves wait behind generation | encoding is small per chunk; a bound on how long a save may wait before the server thread does it itself |
| 10 | Light data | the light engine runs on its own thread | unchanged: read on the server thread at the same point as vanilla |
| 11 | Entities, POIs | separate storages | untouched |

## Verification before it ships

1. **Identical NBT**: a debug switch saves every chunk both ways (vanilla and split) and compares the
   tags byte for byte, across seeds, dimensions, Terralith, Create; zero differences required.
2. **Kill tests** at many points, also right after mass unloads and during `save-all flush`; region
   check and reopen must be clean, and a world compared with the state at the last completed
   `save-all flush` must have lost nothing.
3. **Read-after-unload stress**: ride out and immediately back over the same chunks many times; no
   chunk may ever come back older than it left.
4. Game tests, the compatibility mod set (Create machinery that saves block entities), dedicated
   server, add/remove on an existing world.
5. Ride A/B for the actual gain on the server thread (if it is not measurable, it does not ship).

## What it does not do

It does not move block-entity, tick, attachment or event code off the server thread (mod code,
not thread-safe), does not touch the IOWorker or region files, and does not delay when a chunk is
written relative to vanilla by more than the encoding time.
