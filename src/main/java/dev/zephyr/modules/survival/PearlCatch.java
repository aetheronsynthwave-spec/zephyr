package dev.zephyr.modules.survival;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.CombatActions;
import dev.zephyr.core.inventory.InventoryManager;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.timing.MsTimer;
import dev.zephyr.core.util.ItemUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * PearlCatch - rapid swap/throw reaction for ender pearl catches.
 *
 * <p>Technique implemented: while your own pearl is airborne, the matching pearl hotbar
 * slot is pre-selected (so the follow-up throw needs zero inventory interaction). The
 * moment the pearl lands - detected by the entity vanishing from the client world while
 * owned by the local player - a randomized 15-45 ms reaction timer fires and the next
 * pearl is thrown immediately.
 */
public final class PearlCatch extends Module {

    private final NumberSetting reactionMin = num("reactMin", "Reaction min ms", 5, 100, 1, 15);
    private final NumberSetting reactionMax = num("reactMax", "Reaction max ms", 5, 150, 1, 45);
    private final NumberSetting preSwap = num("preSwap", "Pre-swap when airborne ms", 0, 1000, 10, 0);

    /** pearl entity id -> tracked. */
    private final Map<Integer, Boolean> trackedPearls = new HashMap<>();
    private final MsTimer reactionTimer = new MsTimer();
    private int queuedThrowSlot = -1;
    private int preSwapDelayTicks;

    public PearlCatch() {
        super("PearlCatch", ModuleCategory.SURVIVAL, 0);
    }

    @Override
    protected void onDisable() {
        trackedPearls.clear();
        queuedThrowSlot = -1;
        reactionTimer.invalidate();
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        ClientWorld world = client.world;
        if (self == null || world == null) {
            trackedPearls.clear();
            return;
        }

        // ---- tracking -------------------------------------------------------
        java.util.Set<Integer> present = new java.util.HashSet<>();
        for (var e : world.getEntities()) {
            if (e instanceof EnderPearlEntity pearl && pearl.getOwner() == self) {
                present.add(pearl.getId());
                if (!trackedPearls.containsKey(pearl.getId())) {
                    trackedPearls.put(pearl.getId(), Boolean.TRUE);
                    // Pre-swap to the next pearl slot while the current pearl flies.
                    if (preSwap.get() > 0 && preSwapDelayTicks == 0) {
                        preSwapDelayTicks = (int) Math.max(1, preSwap.get() / 50);
                    }
                }
            }
        }
        if (preSwapDelayTicks > 0 && --preSwapDelayTicks == 0) {
            int pearlSlot = InventoryManager.findHotbarSlot(self, ItemUtil::isPearl);
            if (pearlSlot != -1) {
                InventoryManager.selectHotbar(client, pearlSlot);
            }
        }

        // ---- landing detection -------------------------------------------------
        boolean landed = false;
        for (Integer id : trackedPearls.keySet()) {
            if (!present.contains(id)) {
                landed = true;
            }
        }
        if (landed) {
            trackedPearls.clear();
            int pearlSlot = InventoryManager.findHotbarSlot(self, ItemUtil::isPearl);
            if (pearlSlot != -1) {
                InventoryManager.selectHotbar(client, pearlSlot);
                queuedThrowSlot = pearlSlot;
                reactionTimer.restart(reactionMin.get(), reactionMax.get());
            }
        }

        // ---- reaction throw ----------------------------------------------------
        if (queuedThrowSlot != -1 && reactionTimer.isDue()) {
            ItemStack held = self.getMainHandStack();
            if (self.getInventory().selectedSlot == queuedThrowSlot
                    && ItemUtil.isPearl(held)
                    && !ItemUtil.coolingDown(self, held)) {
                CombatActions.interactItem(client);
                CombatActions.swing(client);
            }
            queuedThrowSlot = -1;
            reactionTimer.invalidate();
        }
    }

    @Override
    public String hudLabel() {
        return name + (queuedThrowSlot != -1 ? " · armed" : "");
    }
}
