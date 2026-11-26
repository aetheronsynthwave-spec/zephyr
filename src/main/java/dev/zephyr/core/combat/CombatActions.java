package dev.zephyr.core.combat;

import dev.zephyr.core.rotation.RotationManager;
import dev.zephyr.core.timing.TickPhase;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
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
        if (target == null || !ActionState.prepareAttack(client)) {
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
                || !ActionState.canAttack(client)) {
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

    /** Item use (pearl throw, XP bottle); swings like vanilla on success. */
    public static ActionResult interactItem(MinecraftClient client) {
        if (!ActionState.prepareUse(client)) {
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
     */
    public static ActionResult interactItemAt(MinecraftClient client, float yaw, float pitch) {
        if (!ActionState.prepareUse(client)) {
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

    private static void swingIfVanillaWould(MinecraftClient client, ActionResult r) {
        if (r instanceof ActionResult.Success s && s.swingSource() == ActionResult.SwingSource.CLIENT) {
            client.player.swingHand(Hand.MAIN_HAND);
        }
    }
}
