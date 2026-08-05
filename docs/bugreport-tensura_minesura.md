# `UltimateWeaponHandler.onContainerClose` recurses infinitely with Lootr containers → server-side `StackOverflowError`

## Environment

| | |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.243 |
| Java | 21.0.11 (Linux, dedicated server) |
| tensura_minesura | 2.0.0 |
| Lootr | 1.21.1-1.11.37.122 |

## Summary

`UltimateWeaponHandler.onContainerClose` calls `Slot.getItem()` on every slot of a
closing container. When the container is a Lootr container whose loot table has not
yet been unpacked, `getItem()` triggers `unpackLootTable()`, and Lootr's mixin on that
method calls `LootrAPI.closeContainers()` — which closes the container again, fires
`PlayerContainerEvent.Close` again, and re-enters this handler.

The result is unbounded mutual recursion. It blows the JVM stack after ~92 iterations
and takes the dedicated server down with `Exception in server tick loop`.

This happened twice in 41 minutes on our server (17:10 and 17:51), each time killing
the process. It is reachable by any player closing any un-unpacked Lootr container, of
which our overworld has roughly 419.

## Stack trace

Repeating cycle, trimmed of mixin/classloader decorations for readability. Full crash
report available on request.

```
java.lang.StackOverflowError: Exception in server tick loop
    ...
    at net.neoforged.bus.EventBus.post(EventBus.java:328)
    at net.minecraft.server.level.ServerPlayer.doCloseContainer(ServerPlayer.java:1216)
    at net.minecraft.server.level.ServerPlayer.closeContainer(ServerPlayer.java:1209)
    at noobanidus.mods.lootr.common.api.LootrAPI.closeContainers(LootrAPI.java:626)
    at net.minecraft.world.RandomizableContainer.handler$bpf000$lootr$unpackLootTable(RandomizableContainer.java:555)
    at net.minecraft.world.RandomizableContainer.unpackLootTable(RandomizableContainer.java:88)
    at net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity.getItem(RandomizableContainerBlockEntity.java:57)
    at net.minecraft.world.inventory.Slot.getItem(Slot.java:50)
    at com.joaomaia.tensura_minesura.event.UltimateWeaponHandler.onContainerClose(UltimateWeaponHandler.java:28)
    at net.neoforged.bus.ConsumerEventHandler.invoke(ConsumerEventHandler.java:27)
    at net.neoforged.bus.EventBus.post(EventBus.java:360)
    at net.neoforged.bus.EventBus.post(EventBus.java:328)
    at net.minecraft.server.level.ServerPlayer.doCloseContainer(ServerPlayer.java:1216)
    ... repeats ~92 times until the 1024-frame limit
```

## Cause

Reconstructed from the bytecode of `UltimateWeaponHandler.class` in
`tensura_minesura-2.0.0.jar` (semantics should be exact; names and formatting are mine):

```java
public static void onContainerClose(PlayerContainerEvent.Close event) {
    Player player = event.getEntity();
    if (player.level().isClientSide) return;
    AbstractContainerMenu menu = event.getContainer();
    if (menu == null) return;
    for (Slot slot : menu.slots) {
        if (slot.container == player.getInventory()) continue;
        if (slot.getItem().getItem() instanceof IUltimateWeapon) {
            slot.set(ItemStack.EMPTY);
        }
    }
}
```

The problem is `slot.getItem()`.

On a vanilla container that is a cheap field read. On a `RandomizableContainerBlockEntity`
it is not — `getItem()` calls `unpackLootTable()` first, because loot containers
generate their contents lazily on first access. Lootr mixes into `unpackLootTable` and
closes all open containers from inside it, so the call re-enters
`PlayerContainerEvent.Close` before the first `getItem()` has returned.

Because the container is still mid-unpack, the re-entrant call also sees it as
un-unpacked, and the cycle repeats until the stack is exhausted.

## Reproduction

Not yet minimised — this came from a production server, not a test instance. What we
can state:

1. Server-side, NeoForge 1.21.1, with Lootr and tensura_minesura both installed.
2. A player opens and then closes a Lootr container whose loot table has not yet been
   unpacked for that player.
3. `StackOverflowError` on the server thread; server terminates.

There is no guard on the handler — no particular item, player state, or dimension is
required — so it should reproduce with a plain Lootr chest.

## Suggested fix

Any one of these should break the cycle. In rough order of preference:

**1. Don't touch slots backed by a block entity.** The handler's purpose appears to be
preventing Ultimate Weapons from being stashed in containers. Reading the *menu's*
carried/held stack, or filtering to inventory-backed slots, avoids forcing a lazy unpack:

```java
if (!(slot.container instanceof net.minecraft.world.level.block.entity.BlockEntity)) { ... }
```

**2. Defer the scan by one tick**, so it runs outside the close-event call stack:

```java
player.getServer().execute(() -> { /* scan */ });
```

This also has the nice property that the container is fully settled by the time you
look at it.

**3. Add a reentrancy guard** — a `ThreadLocal<Boolean>` set for the duration of the
handler, with an early return if already set. Cheapest change, but treats the symptom.

I'd lean towards (1) or (2), since a reentrancy guard would leave the underlying
"reading a slot can run arbitrary mod code" hazard in place.

## Unrelated minor note

`slot.set(ItemStack.EMPTY)` destroys the item outright rather than dropping it or
returning it to the player. If that is intentional as an anti-stashing measure, fair
enough — but it is silent, and from a player's side it is indistinguishable from an
item-loss bug. A log line or a message to the player would make it much easier to
support.

## Cross-reference

Filed against Lootr as well, since the reentrancy on their side is arguably the deeper
issue: `LootrAPI.closeContainers()` is invoked from within the `unpackLootTable` mixin
before the container is flagged as unpacked.
