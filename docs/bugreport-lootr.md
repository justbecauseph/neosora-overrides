# `closeContainers()` called from inside the `unpackLootTable` mixin permits unbounded reentrancy → `StackOverflowError`

## Environment

| | |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.243 |
| Java | 21.0.11 (Linux, dedicated server) |
| Lootr | 1.21.1-1.11.37.122 |
| Other mod involved | tensura_minesura 2.0.0 |

## Summary

`ticker.MixinRandomizableContainer` (from `lootr-common.mixins.json`) injects into
`RandomizableContainer.unpackLootTable` and calls `LootrAPI.closeContainers()` at
`LootrAPI.java:626`. That call fires `PlayerContainerEvent.Close`.

If any listener on that event reads a slot of the container being closed, it re-enters
`unpackLootTable` — and because the container has not yet been marked unpacked, the
re-entrant call proceeds and calls `closeContainers()` again. The recursion is
unbounded and terminates only by exhausting the JVM stack.

I want to be upfront that this is inferred from the stack trace, not from reading your
source — I don't know whether the unpacked flag is genuinely set after the
`closeContainers()` call, or whether something else prevents the guard from taking
effect here. But the observed behaviour is a clean repeating cycle through
`unpackLootTable` → `closeContainers` → close event → `getItem` → `unpackLootTable`,
which is hard to explain unless the re-entrant call is passing the same check the first
one did.

On our server this killed the process twice in 41 minutes.

## Stack trace

One full cycle, trimmed of mixin/classloader decorations. Full crash report available on
request.

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

Relevant mixin, from the frame decoration:

```
pl:mixin:APP:lootr-common.mixins.json:ticker.MixinRandomizableContainer from mod lootr
```

## The other half

The listener closing the loop is `tensura_minesura`'s `UltimateWeaponHandler`, which on
`PlayerContainerEvent.Close` iterates every non-player-inventory slot of the container
and calls `Slot.getItem()` looking for items to remove. Reconstructed from bytecode:

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

That mod has been notified separately and their side is the cheaper fix, so this may
well be "not our bug" from your perspective. I'm filing it anyway because the reentrancy
looks like it would be triggerable by any close-event listener that touches slot
contents, which seems worth hardening against regardless of this particular mod.

## Reproduction

Not minimised — this is from a production server. Conditions:

1. NeoForge 1.21.1 server with Lootr and tensura_minesura installed.
2. A player opens and closes a Lootr container not yet unpacked for them.
3. `StackOverflowError` on the server thread; process terminates.

## Suggested fix

A reentrancy guard around the injection point, so a nested `unpackLootTable` for the
same container returns immediately rather than re-running `closeContainers()`. For
example a `ThreadLocal<Set<BlockPos>>` (or the container's identity) held for the
duration of the call.

Alternatively, deferring `closeContainers()` to the end of the tick via
`server.execute(...)` would take it off the `unpackLootTable` call stack entirely and
remove the class of problem rather than this one instance of it.

## Note on versions

We are on `1.21.1-1.11.37.122`, which appears to be at or ahead of the latest published
build I can find (`1.11.37.120`), so this does not look like something already fixed in
a release we're behind on. Happy to test a snapshot if that would help.
