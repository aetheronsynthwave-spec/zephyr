package dev.zephyr.core.combat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;

/**
 * Attribute-swap bookkeeping shared by AutoMace, BreachSwap and LungeMacro.
 *
 * <p>An attribute swap selects the special item (mace / breach mace / spear) right before
 * the hit, so the server applies the hit with that item, then returns to the original
 * slot. With a swap-back delay of N ticks the swapped item is visibly in hand for N ticks
 * (exactly what a hand-done swap looks like: sword, flash of mace, sword); with 0 the
 * return happens in the same tick and nothing is ever visible.
 */
public final class SwapManager {

    private SwapManager() {
    }

    private static int originalSlot = -1;
    private static int swappedSlot = -1;
    private static int ticksLeft;
    /** True while one of our own swap attacks is running (BreachSwap must not re-enter). */
    private static boolean inSwap;

    public static boolean inSwap() {
        return inSwap;
    }

    public static boolean pending() {
        return originalSlot != -1;
    }

    /** Client + server slot select (the packet goes out immediately, before the attack). */
    public static void select(MinecraftClient client, int slot) {
        if (client.player == null || client.getNetworkHandler() == null || slot < 0 || slot > 8) {
            return;
        }
        client.player.getInventory().setSelectedSlot(slot);
        client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    /** The slot to return to: the pending original if a swap is already in progress. */
    public static int homeSlot(MinecraftClient client) {
        return originalSlot != -1 ? originalSlot : client.player.getInventory().getSelectedSlot();
    }

    /**
     * Runs {@code action} with {@code slot} selected, then returns to the home slot either
     * immediately ({@code backTicks <= 0}) or after {@code backTicks} ticks.
     */
    public static <T> T withSwap(MinecraftClient client, int slot, int backTicks, java.util.function.Supplier<T> action) {
        int home = homeSlot(client);
        boolean changed = client.player.getInventory().getSelectedSlot() != slot;
        if (changed) {
            select(client, slot);
        }
        T result;
        inSwap = true;
        try {
            result = action.get();
        } finally {
            inSwap = false;
        }
        if (home != slot) {
            if (backTicks <= 0) {
                select(client, home);
                originalSlot = -1;
            } else {
                originalSlot = home;
                swappedSlot = slot;
                ticksLeft = backTicks;
            }
        }
        return result;
    }

    /** Called once per client tick: performs the delayed swap-back. */
    public static void tick(MinecraftClient client) {
        if (originalSlot == -1 || client.player == null) {
            originalSlot = -1;
            return;
        }
        if (client.player.getInventory().getSelectedSlot() != swappedSlot) {
            originalSlot = -1; // the player switched slots themselves: leave their choice alone
            return;
        }
        if (--ticksLeft <= 0) {
            select(client, originalSlot);
            originalSlot = -1;
        }
    }
}
