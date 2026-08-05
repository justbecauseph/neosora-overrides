package dev.neosora.overrides.mixin;

import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fixes: {@code StackOverflowError} in the server tick loop when a player closes a Lootr
 * container - "Exception in server tick loop", server dies.
 *
 * <p>tensura_minesura 2.0.0 deletes stashed Ultimate Weapons on container close
 * (decompiled from {@code UltimateWeaponHandler.onContainerClose}, line 28):
 *
 * <pre>{@code
 * for (Slot slot : menu.slots) {
 *     if (slot.container == player.getInventory()) continue;
 *     if (slot.getItem().getItem() instanceof IUltimateWeapon) {   // <-- forces loot unpack
 *         slot.set(ItemStack.EMPTY);
 *     }
 * }
 * }</pre>
 *
 * <p>On a vanilla container {@code Slot.getItem()} is a cheap field read. On a
 * {@code RandomizableContainerBlockEntity} it is not: {@code getItem} calls
 * {@code unpackLootTable()} first, because loot containers generate contents lazily. Lootr
 * mixes into {@code unpackLootTable} and calls {@code LootrAPI.closeContainers()} from inside
 * it, which closes the container again, fires {@code PlayerContainerEvent.Close} again, and
 * re-enters this handler. Because the container has not yet been flagged as unpacked, the
 * re-entrant call unpacks again. Roughly 92 laps later the 1024-frame JVM stack is gone.
 *
 * <p>Neither mod is wrong alone - it is the pair. This patches the tensura_minesura side
 * because it is the cheaper and safer of the two to intercept.
 *
 * <p>The fix: when a slot belongs to a loot container whose loot has not been generated yet,
 * report it as empty instead of forcing the unpack. That is semantically correct, not just a
 * workaround - a container whose loot has never been generated cannot contain a weapon a
 * player stashed there, so there is nothing for this handler to find. Once the loot has been
 * unpacked normally (by the player opening it), {@code getLootTable()} returns null and the
 * scan proceeds as usual.
 *
 * <p>Deliberately <b>not</b> done: a reentrancy guard on {@code onContainerClose}. It would
 * also break the cycle, but a {@code ThreadLocal} flag set at HEAD and cleared at RETURN
 * leaks if the handler throws, and a leaked flag would silently disable the anti-stash
 * feature for the rest of the server's life. Removing the trigger is cleaner than trying to
 * survive it.
 *
 * <p>Reported upstream: see {@code bugreport-tensura_minesura.md} and
 * {@code bugreport-lootr.md}.
 */
@Pseudo
@Mixin(targets = "com.joaomaia.tensura_minesura.event.UltimateWeaponHandler", remap = false)
public abstract class TensuraMinesuraUltimateWeaponHandlerMixin {

    @Redirect(
            method = "onContainerClose",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/inventory/Slot;getItem()Lnet/minecraft/world/item/ItemStack;"
            ),
            remap = false,
            require = 0
    )
    private static ItemStack neosora$dontForceLootUnpack(Slot slot) {
        Container container = slot.container;

        // getLootTable() != null means the loot table is still pending generation. Touching
        // the slot now would trigger unpackLootTable() -> Lootr closeContainers() -> recursion.
        if (container instanceof RandomizableContainer randomizable && randomizable.getLootTable() != null) {
            return ItemStack.EMPTY;
        }

        return slot.getItem();
    }
}
