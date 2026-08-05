# NeoSora Overrides

Server-side patches for three third-party mod bugs that were crashing the NeoSora server.

Adds no content, registers nothing, ships no assets. It is three mixins and a safety net.

**Target platform:** Minecraft 1.21.1, NeoForge 21.1.243, Java 21.

---

## What it fixes

All three were diagnosed from crash reports on 2026-08-05, which produced five server kills
in one afternoon across these three independent bugs. None of them are load-related, so none
would have been fixed by TPS or spawn tuning.

### 1. `StackOverflowError` — tensura_minesura ↔ Lootr

*Killed the server at 17:10 and 17:51 (and 17:52, the watchdog finishing off the wedged
process).*

`UltimateWeaponHandler.onContainerClose` calls `Slot.getItem()` on every slot of a closing
container. On a Lootr container that forces `unpackLootTable()`, which Lootr hooks to call
`LootrAPI.closeContainers()`, which fires the close event again, which re-enters the handler.
~92 laps later the 1024-frame JVM stack is exhausted.

**Patch:** report un-unpacked loot slots as empty instead of forcing the unpack. A container
whose loot has never been generated cannot hold a player-stashed weapon, so the handler has
nothing to find there anyway.

### 2. Server-thread hang — ProgressiveStages

*Killed the server at 18:33.*

`StageRegressionData.grantTimes` is a plain `HashMap`, read on the entity-tracking path and
written by the stage grant/clear paths. An off-thread write during a resize can splice a
bucket into a cycle; the next read spins forever in `HashMap.getNode` and the watchdog fires
at 60s.

**Patch:** swap it for a `ConcurrentHashMap`.

Why corruption and not just slowness: the persisted map is 991 bytes (not big), keys are
Strings (not degenerate), the thread was RUNNABLE (not blocked), and a spark profile found
ProgressiveStages in **0 of 68,864** sampled nodes while `ChunkMap$TrackedEntity` had 40 —
so it is provably cold and could not have caught the sample unless it was eating that tick.

### 3. Chunk-load deadlock — Voiceless Survival vs C2ME

*Killed the server at 16:11.*

`SculkVibrationHelper.findNearbySculkSensors` probes every block position in a cube around
the speaker with `getBlockEntity`, which force-loads chunks synchronously on the server
thread. With an async chunk system that wait can go unserved — at the time of the crash all
six `c2me-worker` threads plus `c2me-sched` and every `C2ME Storage` thread were idle while
the server thread waited.

**Patch:** skip positions in unloaded chunks. A sculk sensor in an unloaded chunk has no
listener ticking and nobody to hear it.

**Not patched:** the scan is cubic — `(2r+1)³` lookups per voice packet, ~36,000 at r=16.
That needs an upstream fix (iterate loaded chunks and read their block-entity maps).

---

## Building

```bash
./gradlew build
```

Output: `build/libs/neosora_overrides-1.0.0.jar`. Drop it in the server's `mods/` folder.

The first build needs network access — none of NeoForge 21.1.243, the ModDevGradle plugin,
or Parchment are in the local Gradle cache.

### If your default JDK is too new

Gradle 8.x cannot run on Java 25 (`Unsupported class file major version 69`). This is about
the JVM Gradle *itself* runs on — the mod is always compiled against Java 21 via an
auto-provisioned toolchain regardless.

If `java -version` reports 25 or newer, point Gradle at an older JDK for this build:

```bash
./gradlew build -Dorg.gradle.java.home="C:/Program Files/Microsoft/jdk-17.0.16.8-hotspot"
```

Installing a JDK 21 and setting `JAVA_HOME` to it is the cleaner permanent fix — it matches
what NeoForge 1.21.1 targets and what CI uses, so local and CI builds stay identical.

This is deliberately *not* pinned in `gradle.properties`: a machine-specific path there
would break the GitHub Actions build, which provisions its own JDK 21.

> **One unverified pin:** `net.neoforged.moddev` is set to `2.0.78` in `build.gradle`. That
> version could not be checked offline. If the build fails resolving the plugin, bump it to
> a version that exists on the Gradle Plugin Portal — nothing else depends on the exact value.

---

## Verifying it actually works

On server start this logs exactly which patches are live:

```
=== NeoSora Overrides: active patches ===
  [ACTIVE ] progressivestages - StageRegressionData.grantTimes -> ConcurrentHashMap ...
  [ACTIVE ] ezvcsurvival - SculkVibrationHelper skips unloaded chunks ...
  [ACTIVE ] tensura_minesura - UltimateWeaponHandler no longer forces loot unpack ...
```

Three states per line:

| state | meaning |
|---|---|
| `[ACTIVE ]` | patch applied, bug fixed |
| `[skipped]` | that mod isn't installed, nothing to patch |
| `[FAILED ]` | **the mod IS installed but the patch did not apply — the bug is live** |

`[FAILED ]` is the one to care about. It almost always means the target mod updated and moved
the code being hooked.

### Why it warns instead of crashing

The injectors use `require = 0`, so a mod update that moves an injection point will not take
a live server down. The cost of that choice is silence, which is why the report exists — a
patch you believe is protecting you but isn't is the worst outcome, so the log closes the
gap.

If you would rather fail loudly instead, set `"defaultRequire": 1` in
`src/main/resources/neosora_overrides.mixins.json`. The server will then refuse to start when
a patch cannot apply.

---

## When a target mod updates

1. Check the startup report for `[FAILED ]`.
2. Disassemble the new version to see what moved:
   ```bash
   javap -p -c -cp <mod>.jar <fully.qualified.ClassName>
   ```
3. Update the `@At` target descriptor in the corresponding mixin.

Watch the descriptor *owner* specifically. In `EzvcSurvivalSculkVibrationHelperMixin` the
target is `ServerLevel.getBlockEntity`, not `Level.getBlockEntity`, because
`findNearbySculkSensors` takes a `ServerLevel` and javac emitted the call against that type.
Targeting the superclass silently fails to match.

---

## Design notes

**No compile-time dependency on the patched mods.** Every mixin soft-targets its class by
string via `@Pseudo`, and every injection handler deals only in Minecraft/JDK types. So the
build needs none of those jars, and they can update without forcing a recompile. The
trade-off — no compile-time verification of targets — is covered by `NeoSoraMixinPlugin` and
the startup report.

**Every patch is gated on its mod being loaded.** Mixin fails loudly when a declared target
class is missing, so without gating, removing any patched mod would hard-crash startup.

**Fix the trigger, not the symptom.** For the Lootr recursion, a reentrancy guard on
`onContainerClose` would also break the cycle, but a `ThreadLocal` set at HEAD and cleared at
RETURN leaks if the handler throws, and a leaked flag would silently disable the anti-stash
feature permanently. Removing the trigger avoids that class of problem entirely.

---

## Upstream

These are worked around here, not solved. The bug reports live alongside the pack:

- `docs/bugreport-tensura_minesura.md`
- `docs/bugreport-lootr.md`
- `docs/bugreport-progressivestages.md`
- `docs/bugreport-ezvcsurvival.md`

Once any of them is fixed upstream, drop the corresponding mixin from
`neosora_overrides.mixins.json` and from `GATES` in `NeoSoraMixinPlugin`.
