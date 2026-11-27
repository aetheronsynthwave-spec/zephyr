package dev.zephyr.core.combat;

import dev.zephyr.core.rotation.RotationManager;
import dev.zephyr.core.timing.TickPhase;
import dev.zephyr.core.util.ItemUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;

/**
 * Every outbound combat interaction funnels through vanilla's interaction manager, so
 * packets (including 1.21.2+ interact packets carrying yaw/pitch and the block-change
 * sequence) are exactly what a human click would produce. When a silent rotation is
 * active, the player's live rotation is swapped to the server rotation for the duration
 * of the interaction call - the interact packet therefore carries the aimed rotation.
 *
 * <p>Timing: every action is refused unless {@link TickPhase#actionWindowOpen()} - i.e. it
 * is emitted inside the same tick and before that tick's movement packet, exactly where
 * vanilla's {@code handleInputEvents} sends a human click. Module ticks run in the
 * pre-movement stage, so in practice this only rejects calls from late hooks / frames.
 */
public final class CombatActions {

    private CombatActions() {
    }

    public static void swing(MinecraftClient client) {
        if (client.player != null) {
            client.player.swingHand(Hand.MAIN_HAND);
        }
    }

    /** Melee/entity attack through the vanilla path (sets cooldown + sends swing). */
    public static boolean attackEntity(MinecraftClient client, Entity target) {
        if (target == null || target.isRemoved() || !ActionState.prepareAttack(client)) {
            return false; // mid-use, screen open, already attacked this tick, or window closed
        }
        // Exactly what vanilla doAttack does: attack packet, then the hand swing (which
        // also sends the swing packet). Without the swing every hit was animation-less
        // and a textbook "no swing" anticheat flag.
        Boolean sent = RotationManager.withServerRotation(() -> {
            client.interactionManager.attackEntity(client.player, target);
            client.player.swingHand(Hand.MAIN_HAND);
            return Boolean.TRUE;
        });
        // Module attacks count as landed attacks too (STap combos off LegitAura/TriggerBot
        // hits, BreachSwap knowing the strike happened). Crystals are not combat hits.
        if (!(target instanceof net.minecraft.entity.decoration.EndCrystalEntity)) {
            dev.zephyr.core.event.ZephyrEvents.ATTACK_LANDED.fire(new dev.zephyr.core.event.ZephyrEvents.AttackLanded(true));
        }
        return Boolean.TRUE.equals(sent);
    }

    /**
     * Attribute swap: select {@code hotbarSlot}, attack, then return to the original slot
     * {@code max(1, backTicks)} ticks later (1 = the next tick's pre-movement stage; 2-4 = a
     * visible flash of the swapped item, like doing it by hand). The server applies the hit
     * with the swapped item's attributes (mace smash, breach, spear). Packet order inside
     * the tick: UpdateSelectedSlot -> attack -> swing -> movement.
     *
     * @return false (and nothing sent) if the slot is invalid or cannot change this tick
     */
    public static boolean attributeSwapAttack(MinecraftClient client, int hotbarSlot, Entity target, int backTicks) {
        if (client.player == null || client.getNetworkHandler() == null || !SwapManager.isValidSlot(hotbarSlot)
                || target == null || target.isRemoved() || !ActionState.canAttack(client)) {
            return false; // check BEFORE swapping, so a refused hit never leaves a stray slot change
        }
        return SwapManager.withSwap(client, hotbarSlot, backTicks, () -> attackEntity(client, target));
    }

    /** Right-click a block (crystal/anchor/glowstone placement); swings like vanilla on success. */
    public static ActionResult interactBlock(MinecraftClient client, net.minecraft.util.hit.BlockHitResult hit) {
        if (!ActionState.prepareUse(client)) {
            return ActionResult.FAIL;
        }
        ActionResult r = RotationManager.withServerRotation(() ->
                client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit));
        swingIfVanillaWould(client, r);
        return r;
    }

    /**
     * Item use (pearl throw, XP bottle); swings like vanilla on success.
     *
     * <p>Contract: returns exactly {@link ActionResult#FAIL} when the action-state gate
     * refused and <b>nothing was sent</b>; any other value means the use went out through
     * the vanilla interaction manager. Callers must treat any non-FAIL result as "the
     * use-item packet was emitted" - 1.21.11 sends {@code PlayerInteractItemC2SPacket}
     * before the client prediction runs (and even when the prediction passes on a
     * cooling-down item), so a non-accepted result does NOT mean "nothing happened".
     */
    public static ActionResult interactItem(MinecraftClient client) {
        if (!ActionState.prepareItemUse(client) || !heldItemReady(client)) {
            return ActionResult.FAIL;
        }
        ActionResult r = RotationManager.withServerRotation(() ->
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND));
        swingIfVanillaWould(client, r);
        return r;
    }

    /**
     * Silent item use at an explicit rotation. Since 1.21.2 {@code PlayerInteractItemC2SPacket}
     * carries yaw/pitch and the server applies them ({@code setAngles}) right before
     * {@code interactItem}, so the projectile leaves along this rotation. The player's real
     * yaw/pitch are restored before the call returns - the camera never moves.
     *
     * <p>Same contract as {@link #interactItem}: {@link ActionResult#FAIL} means the gate
     * refused and nothing was sent; anything else means the packet went out, even if the
     * client prediction itself returned PASS (e.g. the item went on cooldown mid-use).
     */
    public static ActionResult interactItemAt(MinecraftClient client, float yaw, float pitch) {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)
                || !ActionState.prepareItemUse(client) || !heldItemReady(client)) {
            return ActionResult.FAIL;
        }
        var p = client.player;
        float realYaw = p.getYaw();
        float realPitch = p.getPitch();
        ActionResult r;
        try {
            p.setYaw(yaw);
            p.setPitch(net.minecraft.util.math.MathHelper.clamp(pitch, -90f, 90f));
            r = client.interactionManager.interactItem(p, Hand.MAIN_HAND);
        } finally {
            p.setYaw(realYaw);
            p.setPitch(realPitch);
        }
        swingIfVanillaWould(client, r);
        return r;
    }

    /**
     * Selects hotbar slot {@code slot} and uses the item in it at the given rotation. Every
     * check that can refuse runs before the slot changes; the same item type is refused on
     * the tick after a use made through this method.
     *
     * @return {@link ActionResult#FAIL} when no use-item packet was sent
     */
    public static ActionResult interactItemFromSlot(MinecraftClient client, int slot,
                                                    java.util.function.Predicate<ItemStack> wanted,
                                                    float yaw, float pitch) {
        var p = client.player;
        if (p == null || !SwapManager.isValidSlot(slot)) {
            return ActionResult.FAIL;
        }
        ItemStack stack = p.getInventory().getStack(slot);
        if (stack.isEmpty() || !wanted.test(stack) || ItemUtil.coolingDown(p, stack)) {
            return ActionResult.FAIL;
        }
        var item = stack.getItem(); // read before the use can empty the stack
        if (ActionState.usedRecently(item) || !ActionState.prepareItemUse(client)
                || !SwapManager.select(client, slot)
                || p.getInventory().getSelectedSlot() != slot || !wanted.test(p.getMainHandStack())) {
            return ActionResult.FAIL;
        }
        ActionResult r = interactItemAt(client, yaw, pitch);
        if (r != ActionResult.FAIL) {
            ActionState.noteGuardedUse(item);
        }
        return r;
    }

    /** The held stack exists, is off cooldown, and its slot is the one the server holds. */
    private static boolean heldItemReady(MinecraftClient client) {
        ItemStack held = client.player.getMainHandStack();
        return !held.isEmpty() && !ItemUtil.coolingDown(client.player, held) && SwapManager.syncHeld(client);
    }

    private static void swingIfVanillaWould(MinecraftClient client, ActionResult r) {
        if (r instanceof ActionResult.Success s && s.swingSource() == ActionResult.SwingSource.CLIENT) {
            client.player.swingHand(Hand.MAIN_HAND);
        }
    }
}
