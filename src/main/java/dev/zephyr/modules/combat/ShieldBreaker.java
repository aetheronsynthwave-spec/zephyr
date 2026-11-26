package dev.zephyr.modules.combat;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.CombatActions;
import dev.zephyr.core.combat.CombatCoordinator;
import dev.zephyr.core.inventory.InventoryManager;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.target.TargetManager;
import dev.zephyr.core.util.ItemUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.EntityHitResult;

/**
 * ShieldBreaker - when an enemy's raised shield faces you, attribute-swaps to an axe,
 * disables the shield with one hit and swaps back.
 *
 * <p>Works with AutoMace for a <b>stun slam</b>: during a mace drop it also targets
 * AutoMace's target (not just the crosshair), breaks the shield on the way down, records
 * the break in {@link CombatCoordinator}, and AutoMace smashes on the next tick into the
 * now-open guard. The swap goes through SwapManager, so AutoMace's own swap returns to your
 * original weapon, not to the axe.
 */
public final class ShieldBreaker extends Module {

    private final NumberSetting swapBackMs = num("swapBack", "Swap-back delay ms", 50, 400, 10, 120);
    private final BooleanSetting stunSlam = bool("stunSlam", "Stun slam with AutoMace", true);
    private final NumberSetting reach = num("reach", "Reach", 2.5, 3.0, 0.05, 3.0);
    public int breaks;

    public ShieldBreaker() {
        super("ShieldBreaker", ModuleCategory.COMBAT, 0);
    }

    /** Coordinator query: would this module break {@code target}'s shield now? */
    public boolean wantsToBreak(MinecraftClient client, LivingEntity target) {
        ClientPlayerEntity self = client.player;
        return self != null && target != null && target.isAlive()
                && CombatCoordinator.shieldBlocks(target, self)
                && inReach(self, target)
                && axeSlot(self) != -1;
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        if (self == null || client.interactionManager == null) {
            return;
        }
        LivingEntity target = pickTarget(client, self);
        if (target == null || !wantsToBreak(client, target)) {
            return;
        }
        int axe = axeSlot(self);
        boolean onAxe = self.getInventory().getSelectedSlot() == axe;
        // Already holding the axe: wait for a charged swing like a player would. Swapping
        // in resets the client cooldown anyway, and the disable does not depend on charge.
        if (onAxe && ItemUtil.attackCooldownProgress(self) < 0.9f) {
            return;
        }
        int backTicks = Math.max(1, (int) Math.round(swapBackMs.get() / 50.0));
        if (CombatActions.attributeSwapAttack(client, axe, target, backTicks)) {
            breaks++;
            CombatCoordinator.noteShieldBroken(target);
        }
    }

    /** Crosshair target, or AutoMace's target while a slam is coming (stun slam). */
    private LivingEntity pickTarget(MinecraftClient client, ClientPlayerEntity self) {
        if (client.crosshairTarget instanceof EntityHitResult ehr
                && ehr.getEntity() instanceof LivingEntity t && t != self) {
            return t;
        }
        if (stunSlam.get() && CombatCoordinator.maceSlamPending()) {
            return TargetManager.currentTarget();
        }
        return null;
    }

    private boolean inReach(ClientPlayerEntity self, LivingEntity target) {
        double r = reach.get();
        // Reach to the nearest point of the hitbox, as the server measures it.
        return target.getBoundingBox().squaredMagnitude(self.getEyePos()) <= r * r;
    }

    private static int axeSlot(ClientPlayerEntity self) {
        return InventoryManager.findHotbarSlot(self, s -> ItemUtil.id(s).endsWith("_axe"));
    }

    @Override
    public String hudLabel() {
        return name + " · " + breaks;
    }
}
