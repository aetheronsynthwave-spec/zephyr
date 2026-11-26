package dev.zephyr.core.inventory;

import dev.zephyr.core.combat.SwapManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.function.Predicate;

/**
 * Hotbar and screen-handler swap manager.
 *
 * <p>All inventory mutations go through the vanilla interaction path: {@code clickSlot}
 * for screen-handler swaps, and {@link SwapManager} (vanilla {@code syncSelectedSlot()})
 * for hotbar selection, so every swap is exactly what the server would accept from a
 * human - no illegal slot pokes, no duplicate slot packets.
 * Modules schedule swaps through the ms-timed FSMs rather than hammering packets.
 */
public final class InventoryManager {

    private InventoryManager() {
    }

    public static final int SWAP_OFFHAND_BUTTON = 40;

    /** @return hotbar index (0-8) or -1. */
    public static int findHotbarSlot(ClientPlayerEntity player, Predicate<ItemStack> predicate) {
        for (int i = 0; i < 9; i++) {
            if (predicate.test(player.getInventory().getStack(i))) {
                return i;
            }
        }
        return -1;
    }

    /** @return screen-handler slot id or -1 (searches main inventory + hotbar region). */
    public static int findHandlerSlot(ClientPlayerEntity player, Predicate<ItemStack> predicate) {
        ScreenHandler handler = player.currentScreenHandler;
        for (int slot = 9; slot < 45; slot++) {
            if (slot < handler.slots.size() && predicate.test(handler.slots.get(slot).getStack())) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Selects a hotbar slot on client and server. Delegates to {@link SwapManager#select},
     * which validates 0..8, skips the packet when the server already has the slot, and
     * refuses a second slot change in the same tick.
     *
     * @return true when the slot is now selected; false means "not this tick" - callers
     *         must not use the item and should retry next tick
     */
    public static boolean selectHotbar(MinecraftClient client, int slot) {
        return SwapManager.select(client, slot);
    }

    /** Currently selected hotbar index (0-8). */
    public static int selectedHotbar(ClientPlayerEntity player) {
        return player.getInventory().getSelectedSlot();
    }

    /**
     * Shift-free click on a screen-handler slot. {@code button} semantics follow vanilla:
     * left/right click, or the swap-arg for {@link SlotActionType#SWAP}.
     */
    public static void clickSlot(MinecraftClient client, int handlerSlot, int button, SlotActionType type) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null) {
            return;
        }
        client.interactionManager.clickSlot(player.currentScreenHandler.syncId, handlerSlot, button, type, player);
    }

    /** Equips {@code handlerSlot} stack into the offhand (vanilla F-key style swap). */
    public static void swapToOffhand(MinecraftClient client, int handlerSlot) {
        clickSlot(client, handlerSlot, SWAP_OFFHAND_BUTTON, SlotActionType.SWAP);
    }

    /**
     * Generic same-tick hotbar swap: exchanges a screen-handler slot with the given
     * hotbar index (button 0-8 in SWAP mode). Used by breach/lunge swap chains.
     */
    public static void swapWithHotbar(MinecraftClient client, int handlerSlot, int hotbarIndex) {
        clickSlot(client, handlerSlot, hotbarIndex, SlotActionType.SWAP);
    }

    /** @return stack currently in the selected hotbar slot. */
    public static ItemStack heldStack(ClientPlayerEntity player) {
        return player.getMainHandStack();
    }
}
