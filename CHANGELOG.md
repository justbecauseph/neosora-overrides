# Changelog

All notable changes to this project are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
This project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.1] — 2026-08-06

### Fixed

**Startup report gave false `[FAILED ]` verdicts for correct patches.**

Mixin transforms classes lazily, on first load. `postApply` therefore does not fire until
something actually touches the target class, so a patch that is perfectly fine reads as
unapplied at server start if nothing has reached it yet.

Observed on the first deploy: `UltimateWeaponHandler` is an event handler registered during
mod construction, so it was loaded and correctly reported `[ACTIVE ]`. `StageRegressionData`
(not touched until a stage is queried) and `SculkVibrationHelper` (not touched until a player
speaks) both reported `[FAILED ]` despite being applied correctly — confirmed via
`jcmd VM.class_hierarchy`, which showed 572 loaded `com.enviouse` classes but not
`StageRegressionData`.

The report now force-loads each target class with `Class.forName(name, false, loader)` before
building its output. That triggers transformation without running the target's static
initialiser, so the verdict is deterministic instead of a race against gameplay.

No change to the three patches themselves.

---

## [1.0.0] — 2026-08-06

Initial release. Patches three independent third-party mod bugs that together produced five
server kills on 2026-08-05. None of the three are load-related, so none were addressable by
TPS, spawn, or JVM tuning.

### Fixed

**`StackOverflowError` when closing a Lootr container** — tensura_minesura 2.0.0 ↔ Lootr

`UltimateWeaponHandler.onContainerClose` calls `Slot.getItem()` on every slot of a closing
container. On a Lootr container that forces `unpackLootTable()`, which Lootr hooks to call
`LootrAPI.closeContainers()`, which fires the close event again and re-enters the handler.
Roughly 92 laps later the 1024-frame JVM stack is exhausted and the server dies with
"Exception in server tick loop".

Un-unpacked loot slots now report as empty instead of forcing the unpack. This is
semantically correct, not just a workaround: a container whose loot has never been generated
cannot hold a weapon a player stashed there.

*Caused kills at 17:10, 17:51, and 17:52.*

**Server thread hangs forever in `HashMap.getNode`** — ProgressiveStages 3.0.2

`StageRegressionData.grantTimes` is a plain `HashMap`, read on the entity-tracking path and
written by the stage grant/clear paths. An off-thread write during a resize can splice a
bucket's linked list into a cycle; the next read then spins at 100% CPU until the watchdog
kills the server at 60 seconds.

The map is now a `ConcurrentHashMap`.

*Caused the kill at 18:33.*

**Chunk-load deadlock during voice chat** — Voiceless Survival 2.0.1 vs C2ME

`SculkVibrationHelper.findNearbySculkSensors` probes every block position in a cube around
the speaker using `getBlockEntity`, which force-loads chunks synchronously on the server
thread. With an async chunk system that wait can go unserved — at the time of the crash all
six `c2me-worker` threads plus `c2me-sched` and every `C2ME Storage` thread were idle while
the server thread waited on them.

Positions in unloaded chunks are now skipped. A sculk sensor in an unloaded chunk has no
listener ticking and nobody nearby to hear it.

*Caused the kill at 16:11.*

### Known limitations

- The Voiceless Survival scan remains **cubic** — `(2r+1)³` block lookups per voice packet,
  roughly 36,000 at radius 16. This release stops it deadlocking, but not the cost. Fixing
  that properly needs an upstream change: iterate the loaded chunks in range and read their
  block-entity maps instead of probing every position.
- The Lootr side of the recursion is untouched. Breaking one side of a mutual recursion is
  sufficient, and the tensura_minesura side is cheaper and safer to intercept — but the
  underlying reentrancy in `unpackLootTable` remains.
- These are workarounds, not upstream fixes. Bug reports for all four mods are in `docs/`.

### Notes

Injectors use `require = 0`, so a patched mod updating in a way that moves an injection
point will **not** crash a live server. The trade-off is silence, so the mod logs an
explicit report at server start:

```
=== NeoSora Overrides: active patches ===
  [ACTIVE ] progressivestages - ...
  [skipped] ezvcsurvival not installed - ...
  [FAILED ] tensura_minesura IS installed but the patch did NOT apply - ...
```

`[FAILED ]` means the bug is live again. Check it after any mod update.

[1.0.0]: https://github.com/markj/neosora-overrides/releases/tag/v1.0.0
