package dev.zephyr.core.combat;

import dev.zephyr.core.timing.TickPhase;
import dev.zephyr.mixin.accessor.ClientPlayerInteractionManagerAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerInventory;

import java.util.function.BooleanSupplier;

/**
 * The single owner of automated hotbar selection (attribute swaps for AutoMace, BreachSwap
 * and LungeMacro, plus every module that selects an item through
 * {@code InventoryManager#selectHotbar}).
 *
 * <p>Rules, all enforced here so no module can break them:
 * <ul>
 *   <li><b>Valid index only.</b> Anything outside {@code 0..8}
 *       ({@link PlayerInventory#isValidHotbarIndex}) is rejected before any state changes.</li>
 *   <li><b>Vanilla sync path.</b> The client slot is set, then vanilla's own
 *       {@code ClientPlayerInteractionManager#syncSelectedSlot()} is invoked. That sends
 *       {@code UpdateSelectedSlotC2SPacket} only when the server-known slot
 *       ({@code lastSelectedSlot}) differs, and records it - so the follow-up sync inside
 *       {@code attackEntity} / {@code interactItem} and the next tick's
 *       {@code interactionManager.tick()} have nothing left to send. No duplicates.</li>
 *   <li><b>One server-visible slot change per tick.</b> A second change to a different slot
 *       in the same tick (A&rarr;B&rarr;A bounce, or two modules fighting over the hotbar)
 *       is refused; the caller retries next tick.</li>
 *   <li><b>Pre-movement only.</b> Changes are only made while {@link TickPhase#actionWindowOpen()}
 *       - before this tick's movement packet - never from late hooks or render frames.</li>
 *   <li><b>Swap-back on a later tick.</b> An attribute swap returns to the home slot at
 *       least one tick later, during that tick's pre-movement stage.</li>
 * </ul>
 */
public final class SwapManager {

    private SwapManager() {
    }

    /** Pending swap-back state ({@code originalSlot == -1} means none). */
    private static int originalSlot = -1;
    private static int swappedSlot = -1;
    private static int ticksLeft;
    /** Tick id of the last slot change the server was told about by us. */
    private static long lastChangeTick = Long.MIN_VALUE;
    /** True while one of our own swap attacks is running (BreachSwap must not re-enter). */
    private static boolean inSwap;

    public static boolean isValidSlot(int slot) {
        return PlayerInventory.isValidHotbarIndex(slot);
    }

    public static boolean inSwap() {
        return inSwap;
    }

    /** True while a delayed swap-back is scheduled. */
    public static boolean pending() {
        return originalSlot != -1;
    }

    /** True if a server-visible slot change was already sent this tick. */
    public static boolean changedThisTick() {
        return lastChangeTick == TickPhase.tickId();
    }

    /** Drops all state (world join / disconnect: the new interaction manager starts at slot 0). */
    public static void reset() {
        clearPending();
        lastChangeTick = Long.MIN_VALUE;
        inSwap = false;
    }

    /**
     * Explicit selection by a module. Takes ownership of the hotbar, so a pending
     * swap-back is cancelled once the selection succeeds.
     *
     * @return true when {@code slot} is now selected on both client and server
     */
    public static boolean select(MinecraftClient client, int slot) {
        if (!apply(client, slot)) {
            return false;
        }
        clearPending();
        return true;
    }

    /**
     * Makes sure the server holds the currently selected slot before an item use, without
     * cancelling a pending swap-back.
     *
     * @return false when it cannot be synced this tick; the item must not be used then
     */
    public static boolean syncHeld(MinecraftClient client) {
        return client.player != null && apply(client, client.player.getInventory().getSelectedSlot());
    }

    /** The slot to return to: the pending original if a swap is already in progress. */
    public static int homeSlot(ClientPlayerEntity player) {
        return originalSlot != -1 ? originalSlot : player.getInventory().getSelectedSlot();
    }

    /**
     * Attribute swap: selects {@code slot}, runs {@code action} (the attack), then schedules
     * the return to the home slot {@code max(1, backTicks)} ticks later.
     *
     * <p>If the swap cannot be made this tick (invalid slot, action window closed, or a
     * slot change was already sent this tick) the action is <em>not</em> run and false is
     * returned, so an attack is never sent with the wrong item.
     *
     * @return the action's result, or false if the swap was refused
     */
    public static boolean withSwap(MinecraftClient client, int slot, int backTicks, BooleanSupplier action) {
        ClientPlayerEntity player = client.player;
        if (player == null || !isValidSlot(slot)) {
            return false;
        }
        int home = homeSlot(player);
        if (!apply(client, slot)) {
            return false;
        }
        boolean result;
        inSwap = true;
        try {
            result = action.getAsBoolean();
        } finally {
            inSwap = false;
        }
        if (home != slot && isValidSlot(home)) {
            originalSlot = home;
            swappedSlot = slot;
            ticksLeft = effectiveBackTicks(backTicks);
        } else {
            clearPending();
        }
        return result;
    }

    /** Swap-backs never happen in the tick of the swap itself. */
    public static int effectiveBackTicks(int requested) {
        return Math.max(1, requested);
    }

    /**
     * Pre-movement stage, before modules tick: performs a due swap-back. If the player (or
     * the server, via {@code UpdateSelectedSlotS2CPacket}) moved off the swapped slot, the
     * swap-back is dropped and their choice is left alone. A refused swap-back (slot budget
     * already used this tick) stays due and runs next tick.
     */
    public static void tick(MinecraftClient client) {
        if (originalSlot == -1) {
            return;
        }
        ClientPlayerEntity player = client.player;
        if (player == null || player.getInventory().getSelectedSlot() != swappedSlot) {
            clearPending();
            return;
        }
        if (ticksLeft > 0) {
            ticksLeft--;
        }
        if (ticksLeft <= 0 && apply(client, originalSlot)) {
            clearPending();
        }
    }

    /**
     * Makes {@code slot} the selected slot on client and server through vanilla's sync.
     * Sends at most one packet, and nothing at all when the server already has it.
     */
    private static boolean apply(MinecraftClient client, int slot) {
        ClientPlayerEntity player = client.player;
        ClientPlayerInteractionManager im = client.interactionManager;
        if (player == null || im == null || client.getNetworkHandler() == null || !isValidSlot(slot)) {
            return false;
        }
        if (!TickPhase.actionWindowOpen()) {
            return false;
        }
        PlayerInventory inventory = player.getInventory();
        ClientPlayerInteractionManagerAccessor sync = (ClientPlayerInteractionManagerAccessor) im;
        int serverSlot = sync.zephyr$getLastSelectedSlot();
        if (serverSlot == slot) {
            // Server already holds this slot: at most an unsynced local change to undo, no packet.
            if (inventory.getSelectedSlot() != slot) {
                inventory.setSelectedSlot(slot);
            }
            return true;
        }
        if (changedThisTick()) {
            return false; // one server-visible change per tick: no bounce, no burst
        }
        inventory.setSelectedSlot(slot);
        sync.zephyr$syncSelectedSlot(); // exactly one UpdateSelectedSlotC2SPacket
        lastChangeTick = TickPhase.tickId();
        return true;
    }

    private static void clearPending() {
        originalSlot = -1;
        swappedSlot = -1;
        ticksLeft = 0;
    }
}
