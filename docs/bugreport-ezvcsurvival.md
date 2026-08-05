# `SculkVibrationHelper.findNearbySculkSensors` force-loads chunks synchronously on the server thread → server hang / watchdog kill

## Environment

| | |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.243 |
| Java | 21.0.11 (Linux, dedicated server) |
| Voiceless Survival (`ezvcsurvival`) | 2.0.1 |
| Also installed | C2ME 0.4.0-alpha.0.116 (async chunk system) |

## Summary

`SculkVibrationHelper.findNearbySculkSensors` (line 76) calls `Level.getBlockEntity` over
an area around the speaking player. `Level.getBlockEntity` → `getChunkAt` → `getChunk`
**loads the chunk synchronously if it isn't already loaded**, blocking the calling
thread until it is available.

This runs on the server thread inside a `TickTask`. On a server using an asynchronous
chunk system the blocking wait can fail to be satisfied, and the server thread parks
indefinitely. `ServerHangWatchdog` then force-kills the server after 60 seconds.

Even without a hard hang, synchronously loading chunks from the tick thread stalls the
whole server for the duration of the load.

## Stack trace

Server thread, `TIMED_WAITING`, when the watchdog fired. Trimmed of mixin decorations.

```
java.lang.Error: ServerHangWatchdog detected that a single server tick took 60 seconds

"Server thread" prio=8 Id=136 TIMED_WAITING on java.lang.String@515864d6
    at jdk.internal.misc.Unsafe.park(Native Method)
    at java.util.concurrent.locks.LockSupport.parkNanos(LockSupport.java:269)
    at net.minecraft.util.thread.BlockableEventLoop.waitForTasks(BlockableEventLoop.java:143)
    at net.minecraft.util.thread.BlockableEventLoop.managedBlock(BlockableEventLoop.java:133)
    at net.minecraft.server.level.ServerChunkCache$MainThreadExecutor.managedBlock(ServerChunkCache.java:533)
    ...
    at net.minecraft.server.level.ServerChunkCache.getChunk(ServerChunkCache.java)
    at net.minecraft.world.level.Level.getChunk(Level.java:202)
    at net.minecraft.world.level.LevelReader.getChunk(LevelReader.java:130)
    at net.minecraft.world.level.Level.getChunk(Level.java:196)
    at net.minecraft.world.level.Level.getChunkAt(Level.java:192)
    at net.minecraft.world.level.Level.getBlockEntity(Level.java:777)
    at com.armilp.ezvcsurvival.sculk.SculkVibrationHelper.findNearbySculkSensors(SculkVibrationHelper.java:76)
    at com.armilp.ezvcsurvival.sculk.SculkVibrationHelper.generateVibrationSync(SculkVibrationHelper.java:50)
    at com.armilp.ezvcsurvival.sculk.SculkVibrationHelper.lambda$generateVibration$0(SculkVibrationHelper.java:28)
    at net.minecraft.server.TickTask.run(TickTask.java:18)
    at net.minecraft.util.thread.BlockableEventLoop.doRunTask(BlockableEventLoop.java:148)
    ...
```

## Supporting evidence

At the moment of the hang, the entire chunk pipeline was **idle, not busy**:

- all 6 `c2me-worker-*` threads — `WAITING` on the same semaphore
- `c2me-sched` — `WAITING`
- every `C2ME Storage #n` thread — `WAITING`

So the server thread was not waiting on slow chunk generation. It was waiting on a
request that was never going to be satisfied, while the workers that would satisfy it
sat parked. That is a deadlock signature, not a throughput problem.

For scale: this world has **201 `minecraft:sculk_sensor`** block entities in the
overworld, so the scan has plenty of opportunity to reach across a chunk boundary.

## Reproduction

Not minimised — from a production server. Conditions:

1. NeoForge 1.21.1 dedicated server, Voiceless Survival 2.0.1, C2ME installed.
2. A player speaks near the edge of loaded chunks, such that the sensor scan radius
   extends into an unloaded chunk.
3. Server thread blocks in `getChunk`; watchdog kills the server at 60s.

I would expect any async chunk mod to trigger this, and the underlying "sync chunk load
during tick" stall to be reproducible even on vanilla chunk loading.

## Suggested fix

Don't force-load. Query only chunks that are already loaded, and skip the rest:

```java
// instead of level.getBlockEntity(pos) directly:
ChunkAccess chunk = level.getChunk(
        SectionPos.blockToSectionCoord(pos.getX()),
        SectionPos.blockToSectionCoord(pos.getZ()),
        ChunkStatus.FULL,
        false);              // <-- do not create/load
if (chunk == null) continue; // chunk not loaded: no sensors we care about
BlockEntity be = chunk.getBlockEntity(pos);
```

A sculk sensor in an unloaded chunk has no listener ticking and no player nearby to
observe it, so skipping is behaviourally correct as well as safer.

Two smaller suggestions:

- Iterate loaded chunks in range and read their `blockEntities` maps, rather than probing
  every block position. With a large radius that is dramatically less work than a
  per-position lookup.
- If a scan must cover unloaded area, do it off-thread and post the result back with
  `server.execute(...)` rather than blocking the tick.

## Note

The config (`voices.toml`, `sounds.toml`) exposes range multipliers but nothing that
disables the sculk scan, so there is no server-side workaround short of removing the mod
— which is why I'm filing rather than just tuning it down.
