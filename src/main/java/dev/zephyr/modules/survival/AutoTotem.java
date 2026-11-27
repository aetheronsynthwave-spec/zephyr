package dev.zephyr.modules.survival;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.inventory.InventoryManager;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.ModeSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.timing.MsTimer;
import dev.zephyr.core.util.ItemUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

import java.util.List;

/**
 * AutoTotem / HoverTotem - offhand totem restocking with 64-112 ms randomized pacing.
 *
 * <p>Modes:
 * <ul>
 *   <li><b>STANDARD</b> (AutoTotem): restocks from inventory (handler slots) into the
 *       offhand through the vanilla F-key swap path whenever the offhand loses its totem
 *       and the configured health/danger gate trips.</li>
 *   <li><b>HOVER</b> (HoverTotem): prioritizes the stack currently held on the mouse
 *       cursor - if the player is mid-inventory-interaction holding a totem, it clicks
 *       straight into the offhand first.</li>
 * </ul>
 *
 * <p>Guards: never acts while a screen open-blocks interaction (configurable), never
 * double-swaps while a swap is in flight, and tracks totem inventory count so health
 * thresholds can escalate to "panic" mode (lower delay band when nearly out of totems).
 */
public final class AutoTotem extends Module {

    private final ModeSetting mode = s(new ModeSetting("mode", "Mode",
            List.of("STANDARD", "HOVER"), "STANDARD"));
    private final NumberSetting healthThreshold = num("health", "Health threshold", 2.0, 20.0, 0.5, 8.0);
    private final NumberSetting crystalProximity = num("crystalRange", "Crystal danger range", 0.0, 8.0, 0.5, 4.0);
    private final NumberSetting delayMin = num("delayMin", "Delay min ms", 20, 300, 2, 64);
    private final NumberSetting delayMax = num("delayMax", "Delay max ms", 20, 400, 2, 112);
    private final BooleanSetting actInScreens = bool("inScreens", "Act while screen open", false);
    private final BooleanSetting dangerFire = bool("fire", "Danger: fire/fall", true);

    private final MsTimer swapTimer = new MsTimer();
    private int pendingSwapSlot = -1;

    public AutoTotem() {
        super("AutoTotem", ModuleCategory.SURVIVAL, 0);
    }

    @Override
    protected void onDisable() {
        pendingSwapSlot = -1;
        swapTimer.invalidate();
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        if (self == null) {
            return;
        }

        // In-flight swap resolution.
        if (pendingSwapSlot != -1) {
            if (ItemUtil.isTotem(self.getOffHandStack())) {
                pendingSwapSlot = -1;
                swapTimer.invalidate();
                return;
            }
            if (!swapTimer.isDue()) {
                return;
            }
            InventoryManager.clickSlot(client, pendingSwapSlot, InventoryManager.SWAP_OFFHAND_BUTTON, SlotActionType.SWAP);
            pendingSwapSlot = -1;
            swapTimer.restart(delayMin.get(), delayMax.get());
            return;
        }

        if (ItemUtil.isTotem(self.getOffHandStack())) {
            return; // healthy offhand
        }

        // ---- danger / health gate ----------------------------------------
        boolean lowHealth = self.getHealth() <= healthThreshold.get();
        boolean inDanger = dangerEvaluation(client, self);
        if (!lowHealth && !inDanger) {
            return;
        }

        // ---- screen guard -------------------------------------------------
        if (!actInScreens.get() && client.currentScreen != null) {
            return;
        }

        // ---- find source stack --------------------------------------------
        int sourceSlot = -1;
        if (mode.is("HOVER")) {
            // Cursor stack first (HoverTotem semantics): the totem already "in hand" on
            // the cursor is the fastest possible restock - click it into the offhand.
            ItemStack cursor = self.currentScreenHandler.getCursorStack();
            if (ItemUtil.isTotem(cursor)) {
                InventoryManager.clickSlot(client, 45, 0, SlotActionType.PICKUP);
                swapTimer.restart(delayMin.get(), delayMax.get());
                return;
            }
        }
        sourceSlot = InventoryManager.findHandlerSlot(self, ItemUtil::isTotem);
        if (sourceSlot == -1) {
            return; // no totems left
        }

        // Arm the randomized swap.
        pendingSwapSlot = sourceSlot;
        swapTimer.restart(delayMin.get(), delayMax.get());
    }

    /** Crystal proximity, standing in fire/lava, or a fatal fall in progress. */
    private boolean dangerEvaluation(MinecraftClient client, ClientPlayerEntity self) {
        if (crystalProximity.get() > 0) {
            double rangeSq = crystalProximity.get() * crystalProximity.get();
            for (var e : client.world.getEntities()) {
                if (e instanceof EndCrystalEntity crystal
                        && crystal.squaredDistanceTo(self) <= rangeSq
                        && !!crystal.isAlive()) {
                    return true;
                }
            }
        }
        if (dangerFire.get()) {
            if (self.isInLava() || self.isOnFire() || self.fallDistance > 8.0f) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String hudLabel() {
        return name + " · " + mode.get().toLowerCase() + (pendingSwapSlot != -1 ? " · swapping" : "");
    }
}
