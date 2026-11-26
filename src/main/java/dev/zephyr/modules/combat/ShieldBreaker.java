package dev.zephyr.modules.combat;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.CombatActions;
import dev.zephyr.core.inventory.InventoryManager;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.timing.MsTimer;
import dev.zephyr.core.util.ItemUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.EntityHitResult;

/**
 * ShieldBreaker - when your crosshair is on an enemy that is blocking with a shield,
 * swaps to an axe from the hotbar, disables the shield with one hit and swaps back.
 * (From Echo's ShieldBreaker.)
 */
public final class ShieldBreaker extends Module {

    private final NumberSetting swapBackMs = num("swapBack", "Swap-back delay ms", 20, 400, 10, 120);
    private final MsTimer back = new MsTimer();
    private int returnSlot = -1;
    public int breaks;

    public ShieldBreaker() {
        super("ShieldBreaker", ModuleCategory.COMBAT, 0);
    }

    @Override
    protected void onDisable() {
        returnSlot = -1;
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        if (self == null || client.interactionManager == null) {
            return;
        }
        if (returnSlot >= 0) {
            if (back.isDue()) {
                InventoryManager.selectHotbar(client, returnSlot);
                returnSlot = -1;
            }
            return;
        }
        if (!(client.crosshairTarget instanceof EntityHitResult ehr)
                || !(ehr.getEntity() instanceof LivingEntity t) || !t.isBlocking()) {
            return;
        }
        // SMART: a raised shield only blocks hits from the front; from the side/back the
        // sword already gets through, so do not waste the swap.
        if (smart()) {
            net.minecraft.util.math.Vec3d facing = t.getRotationVec(1.0f);
            net.minecraft.util.math.Vec3d toUs = self.getEntityPos().subtract(t.getEntityPos()).normalize();
            if (facing.x * toUs.x + facing.z * toUs.z < 0.0) {
                return;
            }
        }
        int axe = InventoryManager.findHotbarSlot(self, s -> ItemUtil.id(s).endsWith("_axe"));
        if (axe < 0) {
            return;
        }
        int cur = InventoryManager.selectedHotbar(self);
        if (cur != axe) {
            InventoryManager.selectHotbar(client, axe);
        }
        if (ItemUtil.attackCooldownProgress(self) >= 0.9f || cur != axe) {
            CombatActions.attackEntity(client, t);
            breaks++;
            if (cur != axe) {
                returnSlot = cur;
                back.restartExact(swapBackMs.get());
            }
        }
    }

    @Override
    public String hudLabel() {
        return name + " · " + breaks;
    }
}
