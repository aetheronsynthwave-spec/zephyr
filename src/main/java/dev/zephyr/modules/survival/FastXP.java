package dev.zephyr.modules.survival;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.CombatActions;
import dev.zephyr.core.inventory.InventoryManager;
import dev.zephyr.core.rotation.RotationManager;
import dev.zephyr.core.rotation.RotationRequest;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.timing.MsTimer;
import dev.zephyr.core.timing.RandomController;
import dev.zephyr.core.util.ItemUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;

/**
 * FastXP - experience-bottle throwing at 14-17 CPS with gaussian pacing.
 *
 * <p>Gates: bottle must be in the hotbar (auto-selected), item not on cooldown,
 * and a configurable ground-focus rotation keeps the throws landing at the player's
 * feet instead of flying off at the current view angle.
 */
public final class FastXP extends Module {

    private final NumberSetting cpsMin = num("cpsMin", "CPS min", 8, 20, 0.5, 14);
    private final NumberSetting cpsMax = num("cpsMax", "CPS max", 8, 20, 0.5, 17);
    private final NumberSetting pitch = num("pitch", "Throw pitch", 40.0, 90.0, 1.0, 82.0);
    private final NumberSetting keepAbove = num("keep", "Keep stacks above", 0, 64, 1, 0);

    private final MsTimer throwTimer = new MsTimer();
    private double lastCps;

    public FastXP() {
        super("FastXP", ModuleCategory.SURVIVAL, 0);
    }

    @Override
    protected void onEnable() {
        throwTimer.restartCps(cpsMin.get(), 15);
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        if (self == null || client.currentScreen != null) {
            return;
        }
        if (InventoryManager.findHotbarSlot(self, ItemUtil::isXpBottle) == -1) {
            return; // nothing to throw: don't hold the aim down
        }
        // Hold the ground-focus rotation every tick (not only on throw ticks) so the
        // packet pitch never flicks back to the real view between throws.
        RotationManager.request(RotationRequest
                .to(self.getYaw(), pitch.get())
                .speed(45, 90)
                .jitter(0.3)
                .silent(true)
                .expiresAfter(3)
                .build());
        if (!throwTimer.isDue()) {
            return;
        }
        // Bottles thrown before the pitch arrives fly off forward and are wasted.
        if (Math.abs(RotationManager.currentPitch() - pitch.get()) > 8.0) {
            return;
        }
        throwTimer.restartCps(lastCps = RandomController.uniform(cpsMin.get(), cpsMax.get()), 15);

        int slot = InventoryManager.findHotbarSlot(self, stack -> {
            if (!ItemUtil.isXpBottle(stack)) {
                return false;
            }
            return stack.getCount() > keepAbove.get() || keepAbove.get() == 0;
        });
        if (slot == -1) {
            return;
        }
        if (!InventoryManager.selectHotbar(client, slot)) {
            return;
        }
        ItemStack held = self.getMainHandStack();
        if (!ItemUtil.isXpBottle(held) || ItemUtil.coolingDown(self, held)) {
            return;
        }

        CombatActions.interactItem(client);
    }

    @Override
    public String hudLabel() {
        return String.format("%s · %.1fcps", name, lastCps);
    }
}
