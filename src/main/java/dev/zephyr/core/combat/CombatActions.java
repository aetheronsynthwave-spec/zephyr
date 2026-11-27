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
        Boolean sent = RotationManager.withServerRotation(() -> {
            client.interactionManager.attackEntity(client.player, target);
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(sent);
    }

    /** Right-click a block (crystal/anchor/glowstone placement). */
    public static ActionResult interactBlock(MinecraftClient client, net.minecraft.util.hit.BlockHitResult hit) {
        if (client.player == null || client.interactionManager == null) {
            return ActionResult.FAIL;
        }
        return RotationManager.withServerRotation(() ->
                client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit));
    }

    /** Item use (pearl throw, XP bottle, crystal on block via item hand). */
    public static ActionResult interactItem(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) {
            return ActionResult.FAIL;
        }
        return RotationManager.withServerRotation(() ->
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND));
    }
}
