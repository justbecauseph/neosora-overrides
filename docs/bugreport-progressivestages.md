# `StageRegressionData.grantTimes` is an unsynchronized `HashMap` — concurrent write corrupts it and the server thread spins forever in `HashMap.getNode`

## Environment

| | |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.243 |
| Java | 21.0.11 (Linux, dedicated server) |
| ProgressiveStages | 3.0.2 |
| Players online | 4 |

## Summary

`StageRegressionData` stores `grantTimes` in a plain `java.util.HashMap`. That map is
read from `EntityPresenceEnforcer` on the entity-tracking path — i.e. potentially once
per tracked entity per player movement packet — and written by the stage grant/clear
paths.

If any write happens off the server thread, a concurrent resize can splice a bucket's
linked list into a cycle. The next read then walks that cycle forever. `HashMap.getNode`
never returns, the server thread spins at 100% CPU, and `ServerHangWatchdog` force-kills
the server after 60 seconds.

That is what we observed. I've tried to rule out the boring explanations below rather
than just assert this, because I realise "your map got corrupted" is a strong claim.

## Stack trace

Server thread, `RUNNABLE`, at the moment the watchdog fired. Trimmed of mixin
decorations.

```
java.lang.Error: ServerHangWatchdog detected that a single server tick took 60 seconds

"Server thread" prio=8 Id=133 RUNNABLE
    at java.util.HashMap.getNode(HashMap.java:578)
    at java.util.HashMap.get(HashMap.java:564)
    at com.enviouse.progressivestages.server.triggers.StageRegressionData.getGrantTime(StageRegressionData.java:42)
    at com.enviouse.progressivestages.server.rehaul.MinecraftConditionContextFactory.create(MinecraftConditionContextFactory.java:40)
    at com.enviouse.progressivestages.server.rehaul.CompiledRuleEngine.resolve(CompiledRuleEngine.java:85)
    at com.enviouse.progressivestages.server.rehaul.CompiledRuleEngine.resolveUntracked(CompiledRuleEngine.java:76)
    at com.enviouse.progressivestages.server.enforcement.EntityPresenceEnforcer.decision(EntityPresenceEnforcer.java:187)
    at com.enviouse.progressivestages.server.enforcement.EntityPresenceEnforcer.isPresenceDenied(EntityPresenceEnforcer.java:56)
    at com.enviouse.progressivestages.server.enforcement.EntityPresenceEnforcer.shouldConcealTracking(EntityPresenceEnforcer.java:131)
    at net.minecraft.server.level.ChunkMap$TrackedEntity.handler$dgc000$progressivestages$filterTracking(ChunkMap.java:2418)
    at net.minecraft.server.level.ChunkMap$TrackedEntity.updatePlayer(ChunkMap.java)
    at net.minecraft.server.level.ChunkMap.move(ChunkMap.java:1002)
    at net.minecraft.server.level.ServerChunkCache.move(ServerChunkCache.java:463)
    at net.minecraft.server.network.ServerGamePacketListenerImpl.handleMovePlayer(ServerGamePacketListenerImpl.java:955)
    ...
```

## Why it isn't simply "slow"

`HashMap.getNode` walks one bucket's chain. On a healthy map that is nanoseconds. For it
to occupy 60 seconds, something has to be non-terminating. I checked the alternatives:

**Not a large map.** The persisted `progressivestages_regression.dat` is **991 bytes** —
a handful of entries.

**Not degenerate hashing.** Keys are Strings built by `key(UUID, StageId)`. `String`
has a well-distributed `hashCode`, so no pathological bucket.

**Not a hot path that merely got sampled.** This is the important one. I profiled the
server with spark: across **68,864 nodes**, ProgressiveStages appears **zero times** —
no class match, no `handler$…` frame match. The profiler definitely captures this area:
`ChunkMap$TrackedEntity` has 40 nodes, and mixin handler frames from other mods
(including a `stopTracking` handler) are present throughout.

So in normal operation this code costs nothing measurable. For a cold path to be caught
by the sample taken during a 60-second tick, it must have been occupying a large share
of those 60 seconds. Combined with `RUNNABLE` (spinning, not blocked), that points at a
non-terminating loop rather than slowness.

**The only non-terminating failure mode of `getNode` is a cycle in the bucket chain**,
which is what unsynchronized concurrent modification during a resize produces.

## What I could not determine

I have not identified the specific off-thread writer, and I want to be clear that this
part is inference rather than observation. What I can say from the jar:

- `StageRegressionData`'s constructor is `new java.util.HashMap()` — confirmed in bytecode.
- Writers are `markGranted` and `clear`; reachable from `StageManager`,
  `StageTriggerEvaluator`, `StageRegressionHandler`.
- `ServerEventHandler` correctly hops via `MinecraftServer.execute` — that path looks fine.
- `NetworkHandler` and `StageCommand` both reference `StageManager` and use
  `CompletableFuture`. Those seemed the most likely candidates, but I could not confirm
  the call path with `javap` alone.

You'll know your own async paths far better than I can reconstruct them from bytecode.

One detail that may be relevant: the mod uses `ConcurrentHashMap` in **35** other
classes, so the concurrency risk is clearly on your radar generally — this specific map
looks like it was just missed.

## Reproduction

Not reproducible on demand. It is a data race, so it depends on a write landing during a
read's resize. Observed once on a production server with 4 players online.

Note that the corruption is in-memory only — the `.dat` on disk stays valid, so a restart
clears it. That matches what we see: random hangs with no persistent damage.

## Suggested fix

Change `grantTimes` to a `ConcurrentHashMap`:

```java
private final Map<String, Long> grantTimes = new ConcurrentHashMap<>();
```

That removes the corruption risk regardless of which caller is off-thread, and given the
map is tiny the overhead is irrelevant. `SavedData` mutation should also go through
`setDirty()` on the server thread, so if you'd rather fix the callers, asserting
`server.isSameThread()` in `markGranted`/`clear` during development would surface the
offending path quickly.

Worth considering separately: `EntityPresenceEnforcer.shouldConcealTracking` runs inside
`ChunkMap$TrackedEntity.updatePlayer`, which is one of the hottest paths on a busy
server. It happens to be cheap today, but any map lookup there is worth having a cached
per-player result for.
