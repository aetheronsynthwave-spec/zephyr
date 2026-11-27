package dev.zephyr.core.combat;

import dev.zephyr.core.timing.TickPhase;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * Per-tick player-action bookkeeping so attacks, digging, item use/release, inventory
 * clicks and right-clicks never overlap in ways vanilla 1.21.11 cannot produce.
 *
 * <p>Vanilla rules this mirrors ({@code MinecraftClient#handleInputEvents}):
 * <ul>
 *   <li>While an item is being used (eating, drawing a bow, raising a shield) neither
 *       {@code doAttack} nor {@code doItemUse} runs - only the release.</li>
 *   <li>{@code doItemUse} does nothing while {@code interactionManager.isBreakingBlock()}.</li>
 *   <li>With any screen open, no attack / use / dig input is handled at all.</li>
 *   <li>A digging action is ended with {@code cancelBlockBreaking()}, which sends
 *       {@code PlayerActionC2SPacket(ABORT_DESTROY_BLOCK)} and clears the break state.</li>
 * </ul>
 * On top of that, an automated attack and an item use never share a tick, and automated
 * actions are limited to one attack per tick, one item use
 * per tick (a second {@code PlayerInteractItemC2SPacket} in the same tick is a textbook
 * macro signature - vanilla's {@code doItemUse} pass can only ever emit one accepted
 * use), and nothing is attacked or used in the same tick as a use-release or a module
 * inventory click.
 */
public final class ActionState {

    private ActionState() {
    }

    private static long lastAttackTick = Long.MIN_VALUE;
    private static long lastReleaseTick = Long.MIN_VALUE;
    private static long lastInventoryClickTick = Long.MIN_VALUE;
    private static long lastItemUseTick = Long.MIN_VALUE;
    /** Item type -> tick of the last guarded module use (see {@link #usedRecently}). */
    private static final java.util.Map<Object, Long> guardedUses = new java.util.HashMap<>();

    /** Any entity attack (vanilla click, module, BreachSwap re-entry, spear jab). */
    public static void noteAttack() {
        lastAttackTick = TickPhase.tickId();
    }

    /** {@code stopUsingItem} - RELEASE_USE_ITEM was sent this tick. */
    public static void noteRelease() {
        lastReleaseTick = TickPhase.tickId();
    }

    /** A module {@code clickSlot} (inventory transaction) was sent this tick. */
    public static void noteInventoryClick() {
        lastInventoryClickTick = TickPhase.tickId();
    }

    /**
     * {@code ClientPlayerInteractionManager#interactItem} was entered this tick - i.e. a
     * {@code PlayerInteractItemC2SPacket} was (or is about to be) sent. Noted by the mixin
     * at HEAD, so vanilla's own right-click and every module use count alike.
     */
    public static void noteItemUse() {
        lastItemUseTick = TickPhase.tickId();
    }

    /** A guarded module use of {@code itemKey} went out this tick. */
    public static void noteGuardedUse(Object itemKey) {
        guardedUses.put(itemKey, TickPhase.tickId());
    }

    /** True when a guarded use of {@code itemKey} went out this tick or the one before. */
    public static boolean usedRecently(Object itemKey) {
        Long tick = guardedUses.get(itemKey);
        return tick != null && TickPhase.tickId() - tick <= 1;
    }

    public static boolean attackedThisTick() {
        return lastAttackTick == TickPhase.tickId();
    }

    /** A use-item packet already went out this tick (vanilla click or module). */
    public static boolean itemUsedThisTick() {
        return lastItemUseTick == TickPhase.tickId();
    }

    /** Shared preconditions for any automated attack or use action. */
    private static boolean baseReady(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.interactionManager == null || !p.isAlive() || p.isSpectator()) {
            return false;
        }
        if (!TickPhase.actionWindowOpen() || client.currentScreen != null) {
            return false; // after the movement packet, between ticks, or a screen is open
        }
        if (p.isUsingItem()) {
            return false; // vanilla never attacks or right-clicks mid-use
        }
        long now = TickPhase.tickId();
        return lastReleaseTick != now && lastInventoryClickTick != now;
    }

    /**
     * Gate for an entity attack. If the player is mid-dig, the dig is aborted first through
     * vanilla's {@code cancelBlockBreaking()} (ABORT_DESTROY_BLOCK), so the attack packet
     * never arrives while the server still thinks a block is being broken.
     *
     * @return true when the attack may be sent now
     */
    public static boolean prepareAttack(MinecraftClient client) {
        if (!canAttack(client)) {
            return false;
        }
        if (client.interactionManager.isBreakingBlock()) {
            client.interactionManager.cancelBlockBreaking();
        }
        return true;
    }

    /** Side-effect-free check: would {@link #prepareAttack} allow an attack right now? */
    public static boolean canAttack(MinecraftClient client) {
        return baseReady(client) && !attackedThisTick() && !itemUsedThisTick();
    }

    /**
     * Gate for right-click actions (interactBlock / interactItem). Mirrors vanilla
     * {@code doItemUse}: refused while a block is being broken.
     */
    public static boolean prepareUse(MinecraftClient client) {
        return baseReady(client) && !client.interactionManager.isBreakingBlock();
    }

    /**
     * Stricter gate for item use in air ({@code interactItem} - pearls, wind charges,
     * XP bottles, fireworks): vanilla's single {@code doItemUse} pass emits at most one
     * accepted use-item packet per tick, so a module use is refused when one already
     * went out this tick. Block interactions keep the looser {@link #prepareUse} gate
     * (vanilla right-clicks a block and then uses the item in the same pass).
     */
    public static boolean prepareItemUse(MinecraftClient client) {
        return prepareUse(client) && !itemUsedThisTick() && !attackedThisTick();
    }
}
