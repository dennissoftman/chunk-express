# Persistence: what vanilla 1.21.1 actually guarantees

This is the evidence base for every storage decision in Chunk Express. All facts below were read from
the decompiled Minecraft 1.21.1 + NeoForge 21.1.265 sources (Mojang mappings). Method names are
given so each claim can be checked again after a version bump.

**Chunk Express changes none of this.** It observes it, and reports the dangerous cases loudly.

## 1. The save path

```
server thread                                   IOWorker mailbox (one thread at a time, Util.ioPool)
-------------                                   ------------------------------------------------------
ChunkMap.save(ChunkAccess)
  poiManager.flush(pos)
  if !chunk.isUnsaved() -> return false
  chunk.setUnsaved(false)            <-- (A)
  [proto chunk checks; may block on readChunk().join()]
  tag = ChunkSerializer.write(level, chunk)        <-- serialisation, on the server thread
  post ChunkDataEvent.Save(tag)                    <-- mods may edit the tag here
  write(pos, tag) -> IOWorker.store(pos, tag) ----> pendingWrites.put(pos, tag)   (coalesced, latest wins)
     .exceptionally(reportChunkSaveFailure)          ... later, BACKGROUND priority:
  return true                                       RegionFileStorage.write -> NbtIo + compression
                                                    RegionFile.write(pos, bytes)
                                                    future.complete / completeExceptionally
```

Consequences:

1. **The unsaved flag is cleared before anything is written (A).** If the write later fails,
   vanilla logs it (`reportChunkSaveFailure`) and writes a debug report, but never marks the
   chunk dirty again. The chunk is not retried until something changes it. *This violates
   Chunk Express requirement 6 ("never acknowledge incomplete saves").*
2. The dirty state is a **boolean, not a revision.** This is safe today only because serialisation
   happens synchronously on the server thread, so nothing can change the chunk between "clear flag"
   and "take snapshot". The moment serialisation moves off the server thread, a per-chunk revision
   counter becomes mandatory (requirement 5, "never let an older snapshot mark newer data saved").
3. **Writes are coalesced per chunk.** `IOWorker.pendingWrites` is keyed by `ChunkPos`; a newer
   `store` replaces the data of a not-yet-written older one, and both callers' futures complete
   together. Reads check `pendingWrites` first and return a copy (read-your-writes). Ordering
   between two saves of the same chunk is therefore already "latest wins".
4. **Compression and disk writes are serial per storage per dimension.** One `IOWorker` exists
   for each of `chunk`, `entities` and `poi` in each dimension; its mailbox runs one task at a
   time. This is the main Tier-2 storage opportunity (parallel compression, single writer).
5. **Tasks submitted after `IOWorker.close()` are silently dropped** — their futures never
   complete. Chunk Express' shutdown check counts writes that never finished.

## 2. What a region write does

`RegionFile.write(ChunkPos, ByteBuffer)`:

1. allocates **new** sectors for the data (`usedSectors.allocate`);
2. writes the data there;
3. updates the in-memory offset and timestamp tables and writes the **whole 8 KiB header**
   (`writeHeader`, one positional write at offset 0);
4. runs the commit op (moves an external `.mcc` file into place, or deletes a stale one);
5. frees the old sectors **in memory**.

This is copy-on-write at the chunk level: an interruption during step 2 leaves the old header
pointing at the old, intact data.

### Synchronous vs OS-buffered writes

`RegionFile` opens its channel with `StandardOpenOption.DSYNC` only when
`MinecraftServer.forceSynchronousWrites()` is true:

| Server | Source of the flag | Default |
|---|---|---|
| Dedicated | `sync-chunk-writes` in `server.properties` | `true` |
| Integrated (singleplayer/LAN) | "Sync Chunk Writes" video option (`Options.syncWrites`) | `true` on Windows, `false` elsewhere |
| Base `MinecraftServer` (e.g. game-test server) | — | `true` |

`/chunkexpress status` shows which mode the running server uses.

### Crash analysis

| Failure during save | DSYNC on | DSYNC off |
|---|---|---|
| JVM killed mid-write | Data write incomplete → header still old → old version survives. Header write is one 8 KiB write; a torn header can mix old and new offsets, each pointing at a complete version. | Same as on, as long as the OS survives: the page cache still reaches disk. |
| OS crash / power loss | Each completed write was durable when it returned, in order. Only the write in progress is at risk (as above). Device write caches that ignore flushes are outside anyone's control. | **Writes can reach disk in any order.** Because freed sectors are reused within the session, an old on-disk header can point at sectors that now hold *another* chunk's newer data. Vanilla then sees a misplaced chunk or a decompression error and regenerates it (section 4). |
| Disk full | `write` throws → future fails → `reportChunkSaveFailure`; old version still referenced; chunk not retried (1.1). | Same. |
| Permission error | Same as disk full. | Same. |
| Oversized chunk (≥ 1 MiB) | Data goes to a temp file, then `Files.move(..., REPLACE_EXISTING)` to `c.X.Z.mcc`. Neither the temp file nor the directory is fsynced, and the stub plus header are written before the move. A crash in between can leave a stub pointing at a missing or old `.mcc`. | Same, worse. |

### Clean shutdown

`MinecraftServer.stopServer()` saves players, runs chunk-map ticks until `hasWork()` is false,
calls `saveAllChunks(flush = true)`, then closes every level. `IOWorker.close()` queues a
SHUTDOWN-priority task, which runs only after all pending writes; `RegionFile.close()` pads the
file and calls `force(true)`. **After a clean shutdown, all completed writes are on disk, with or
without DSYNC.** NeoForge's `ServerStoppedEvent` fires after all of this, which is where
Chunk Express checks for writes that never finished.

## 3. What "completed" means, precisely

| Signal | Meaning | Not a guarantee of |
|---|---|---|
| `ChunkMap.save` returned `true` | Serialised and queued; dirty flag already cleared. | Anything on disk. |
| `IOWorker.store` future completed normally | `RegionFile.write` returned without exception. With DSYNC: data and header durably written. Without: in the OS page cache. | Durability without DSYNC; survival of a torn header write. |
| `IOWorker.store` future failed | Write failed; previous version (if any) most likely still referenced. | That anything retries it. |
| `IOWorker.synchronize(true)` completed | All pending writes done, then `force(true)` on every open region file of that worker. | — (strongest point-in-time guarantee available). |
| Clean shutdown finished | As above, for every storage. | — |

A completed Java future is **not** proof of durable storage. Chunk Express reports these levels
separately and will never treat them as the same thing.

## 4. The load path and silent data loss

```
IOWorker.loadAsync -> RegionFileStorage.read -> RegionFile.getChunkDataInputStream -> NbtIo.read
  -> ChunkMap.readChunk: DataFixer upgrade on Util.backgroundExecutor
  -> isChunkDataValid (needs a "Status" tag), else logged "missing level data, skipping" -> treated as absent
  -> ChunkSerializer.read on the server thread (posts ChunkDataEvent.Load)
  -> on IOException / NbtException / any non-Error: reportChunkLoadFailure, then an EMPTY ProtoChunk
  -> on Error: crash report
```

Three paths make vanilla **regenerate a chunk whose stored data exists**, after which the next
save overwrites that data for good:

1. **Read or parse failure** (`handleChunkLoadFailure`): logged and reported, then replaced by a
   fresh empty chunk. *Chunk Express probe: `MinecraftServerMixin`, with an actionable ERROR.*
2. **Damaged region entry** (`RegionFile.getChunkDataInputStream` returns null for a truncated
   header, zero or negative length, truncated stream, unknown compression): vanilla logs one line
   and returns null — **the same value as "no chunk here"**. Nothing reaches the failure
   reporter. *Chunk Express probe: `RegionFileMixin`, which tells the two cases apart by checking
   that the header still has an offset. Covered by a fault-injection game test.*
3. **Missing `Status` tag**: logged, treated as absent. *Chunk Express probe: `ChunkMapMixin` on
   `isChunkDataValid` (the dimension is not known at that point; the position comes from the
   data's own `xPos`/`zPos`). Covered by a game test.*

Additionally, when a region file is opened, header entries that point into the header, have
zero size or point past the end of the file are **zeroed in memory** with a warning. The next
write to *any* chunk in that region rewrites the whole header, persisting the loss.
*Chunk Express probe: `RegionFileMixin` counts the zeroed entries per file and reports them as soon
as the file is open. Covered by a game test.*

These are the clearest conflicts with Chunk Express requirement 4 ("never silently discard
malformed data"). Chunk Express makes them loud. Making them non-destructive (copying the raw entry
aside before vanilla overwrites it) is planned for the integrity milestone and needs its own
design review, because it means reading region files outside vanilla's I/O worker.

## 5. Main-thread costs this reveals

Both `ChunkSerializer.write` (save) and `ChunkSerializer.read` (load) run on the server thread
in 1.21.1, and `ChunkMap.isExistingChunkFull` can block it on a disk read for proto chunks.
These are the largest storage costs a chunk engine can move off the main thread — and also
where mods hook in (`ChunkDataEvent.Save/Load`, data attachments, capability serialisation), so
they are the main compatibility risk for any change. Chunk Express measures them first
("Save calls (server thread)" and "Chunk loads" in `/chunkexpress status`).
