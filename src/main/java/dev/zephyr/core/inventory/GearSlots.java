package dev.zephyr.core.inventory;

import dev.zephyr.core.combat.ActionState;
import dev.zephyr.core.timing.TickPhase;
import dev.zephyr.core.util.ItemUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.function.Predicate;

/**
 * Chest-slot / hotbar / inventory lookups and the one-click chest swap used by the elytra
 * mace loop.
 *
 * <p>Player screen-handler layout (no container open, sync id 0): armour slots are 5..8
 * (head, chest, legs, feet), main inventory 9..35, hotbar 36..44. A {@code SWAP} click on
 * the chest slot (6) with button {@code h} exchanges the worn item with hotbar slot
 * {@code h} in ONE vanilla transaction - the same click as hovering the chest slot and
 * pressing a number key. The item that was worn lands in that same hotbar slot, so the
 * swap is its own inverse: click again with the same button to swap back.
 */
public final class GearSlots {

    private GearSlots() {
    }

    public static final int CHEST_HANDLER_SLOT = 6;

    public static boolean isElytra(ItemStack s) {
        return s.isOf(Items.ELYTRA);
    }

    /** Elytra that can still glide (vanilla stops gliding at 1 durability left). */
    public static boolean isUsableElytra(ItemStack s) {
        return isElytra(s) && s.getMaxDamage() - s.getDamage() > 2;
    }

    public static boolean isChestplate(ItemStack s) {
        return ItemUtil.id(s).endsWith("_chestplate");
    }

    public static boolean isFirework(ItemStack s) {
        return s.isOf(Items.FIREWORK_ROCKET);
    }

    public static ItemStack worn(ClientPlayerEntity p) {
        return p.getEquippedStack(EquipmentSlot.CHEST);
    }

    public static boolean wearingElytra(ClientPlayerEntity p) {
        return isElytra(worn(p));
    }

    public static boolean wearingUsableElytra(ClientPlayerEntity p) {
        return isUsableElytra(worn(p));
    }

    /** First main-inventory handler slot (9..35, hotbar excluded) matching the predicate, or -1. */
    public static int findMainSlot(ClientPlayerEntity p, Predicate<ItemStack> pred) {
        ScreenHandler h = p.playerScreenHandler;
        for (int slot = 9; slot < 36 && slot < h.slots.size(); slot++) {
            if (pred.test(h.slots.get(slot).getStack())) {
                return slot;
            }
        }
        return -1;
    }

    /** True when the item exists in the hotbar or main inventory (worn/offhand excluded). */
    public static boolean has(ClientPlayerEntity p, Predicate<ItemStack> pred) {
        return InventoryManager.findHotbarSlot(p, pred) != -1 || findMainSlot(p, pred) != -1;
    }

    /**
     * Hotbar slot holding the item that a chest swap would put on: the chestplate while
     * the elytra is worn, otherwise a usable elytra. -1 if it is not in the hotbar.
     */
    public static int counterpartHotbarSlot(ClientPlayerEntity p) {
        return InventoryManager.findHotbarSlot(p, wearingElytra(p) ? GearSlots::isChestplate : GearSlots::isUsableElytra);
    }

    /** Inventory clicks need the player's own handler, no screen, an open action window. */
    public static boolean canClick(MinecraftClient c) {
        ClientPlayerEntity p = c.player;
        return p != null && c.interactionManager != null && p.isAlive() && c.currentScreen == null
                && TickPhase.actionWindowOpen()
                && p.currentScreenHandler == p.playerScreenHandler
                && !p.isUsingItem()
                && !ActionState.attackedThisTick();
    }

    /**
     * Swaps the worn chest item with hotbar slot {@code hotbarSlot} (one SWAP click). Only
     * elytra &lt;-&gt; chestplate exchanges are accepted; anything else is refused unsent.
     */
    public static boolean swapWorn(MinecraftClient c, int hotbarSlot) {
        ClientPlayerEntity p = c.player;
        if (p == null || hotbarSlot < 0 || hotbarSlot > 8 || !canClick(c)) {
            return false;
        }
        ItemStack other = p.getInventory().getStack(hotbarSlot);
        ItemStack worn = worn(p);
        boolean valid = isElytra(worn) ? isChestplate(other) : isUsableElytra(other);
        if (!valid) {
            return false;
        }
        InventoryManager.clickSlot(c, CHEST_HANDLER_SLOT, hotbarSlot, SlotActionType.SWAP);
        return true;
    }

    /**
     * Moves a main-inventory item into the hotbar with one SWAP click (target slot chosen
     * by {@link #pickStagingSlot}). Call once per tick until the item shows up in the hotbar.
     *
     * @return true if a click was sent this call
     */
    public static boolean stageToHotbar(MinecraftClient c, Predicate<ItemStack> pred, int... reserved) {
        ClientPlayerEntity p = c.player;
        if (p == null || !canClick(c)) {
            return false;
        }
        int src = findMainSlot(p, pred);
        int dst = pickStagingSlot(p, reserved);
        if (src == -1 || dst == -1) {
            return false;
        }
        InventoryManager.swapWithHotbar(c, src, dst);
        return true;
    }

    /** An empty hotbar slot if there is one, else any slot that is neither reserved nor selected. */
    public static int pickStagingSlot(ClientPlayerEntity p, int... reserved) {
        int selected = p.getInventory().getSelectedSlot();
        int fallback = -1;
        for (int i = 8; i >= 0; i--) {
            if (i == selected || contains(reserved, i)) {
                continue;
            }
            if (p.getInventory().getStack(i).isEmpty()) {
                return i;
            }
            if (fallback == -1) {
                fallback = i;
            }
        }
        return fallback;
    }

    private static boolean contains(int[] arr, int v) {
        for (int a : arr) {
            if (a == v) {
                return true;
            }
        }
        return false;
    }
}
