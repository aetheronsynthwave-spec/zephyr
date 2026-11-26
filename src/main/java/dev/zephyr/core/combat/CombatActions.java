package dev.zephyr.core.combat;

import dev.zephyr.core.rotation.RotationManager;
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
        if (client.player == null || client.interactionManager == null || target == null) {
            return false;
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
     * Attribute swap: select {@code hotbarSlot}, attack, and re-select the original slot,
     * all inside the same client tick. The server applies the hit with the swapped item's
     * attributes (mace smash, spear lunge, breach) but never sees that item held across a
     * tick, so your held weapon and its cooldown stay as they were. Visually you never
     * leave your current item.
     *
     * @return true if the attack went out.
     */
    public static boolean attributeSwapAttack(MinecraftClient client, int hotbarSlot, Entity target) {
        if (client.player == null || client.getNetworkHandler() == null || hotbarSlot < 0 || hotbarSlot > 8) {
            return false;
        }
        var inv = client.player.getInventory();
        int original = inv.getSelectedSlot();
        if (original == hotbarSlot) {
            return attackEntity(client, target);
        }
        inv.setSelectedSlot(hotbarSlot);
        client.getNetworkHandler().sendPacket(
                new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(hotbarSlot));
        boolean sent = attackEntity(client, target);
        inv.setSelectedSlot(original);
        client.getNetworkHandler().sendPacket(
                new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(original));
        return sent;
    }

    /** Right-click a block (crystal/anchor/glowstone placement); swings like vanilla on success. */
    public static ActionResult interactBlock(MinecraftClient client, net.minecraft.util.hit.BlockHitResult hit) {
        if (client.player == null || client.interactionManager == null) {
            return ActionResult.FAIL;
        }
        ActionResult r = RotationManager.withServerRotation(() ->
                client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit));
        swingIfVanillaWould(client, r);
        return r;
    }

    /** Item use (pearl throw, XP bottle); swings like vanilla on success. */
    public static ActionResult interactItem(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) {
            return ActionResult.FAIL;
        }
        ActionResult r = RotationManager.withServerRotation(() ->
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND));
        swingIfVanillaWould(client, r);
        return r;
    }

    private static void swingIfVanillaWould(MinecraftClient client, ActionResult r) {
        if (r instanceof ActionResult.Success s && s.swingSource() == ActionResult.SwingSource.CLIENT) {
            client.player.swingHand(Hand.MAIN_HAND);
        }
    }
}
