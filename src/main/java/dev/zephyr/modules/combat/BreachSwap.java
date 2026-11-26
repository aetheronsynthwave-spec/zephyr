package dev.zephyr.modules.combat;

import dev.zephyr.ZephyrClient;
import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.inventory.InventoryManager;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.timing.MsTimer;
import dev.zephyr.core.timing.RandomController;
import dev.zephyr.core.util.ItemUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

/**
 * BreachSwap - hotbar/inventory transition to a Breach-enchanted mace at the optimal
 * strike moment, then restore the previous weapon once the slam window closes.
 *
 * <p>Flow (micro-FSM, ms-paced with randomized delays):
 * <pre>IDLE -> (strike request) -> SWAP -> SWAPPED (window) -> RESTORE -> IDLE</pre>
 *
 * <p>Search order: first the hotbar (pure selection, zero inventory clicks), then the
 * main inventory (vanilla swap-click exchanging the mace with the active hotbar slot).
 */
public final class BreachSwap extends Module {

    private final BooleanSetting onlyBreach = bool("onlyBreach", "Require Breach enchant", true);
    private final BooleanSetting restoreAfter = bool("restore", "Restore weapon after", true);
    private final NumberSetting swapDelayMin = num("swapMin", "Swap delay min ms", 10, 200, 5, 20);
    private final NumberSetting swapDelayMax = num("swapMax", "Swap delay max ms", 10, 300, 5, 60);
    private final NumberSetting restoreDelayMin = num("restMin", "Restore delay min ms", 50, 500, 10, 80);
    private final NumberSetting restoreDelayMax = num("restMax", "Restore delay max ms", 50, 800, 10, 220);

    private enum Stage { IDLE, SWAP, SWAPPED, RESTORE }

    private Stage stage = Stage.IDLE;
    private final MsTimer stageTimer = new MsTimer();
    private int breachHotbarSlot = -1;
    private int breachHandlerSlot = -1;
    private int selectedBeforeSwap = -1;
    private int strikeWindowTicks = 0;
    private boolean strikeRequested;

    public BreachSwap() {
        super("BreachSwap", ModuleCategory.COMBAT, 0);
        dev.zephyr.core.event.ZephyrEvents.ATTACK_LANDED.subscribe(e -> {
            if (stage == Stage.SWAPPED) {
                attackedSinceSwap = true;
            }
        });
    }

    private boolean attackedSinceSwap;

    /** Called by AutoMace / LungeMacro to request a breach-mace strike window. */
    public void requestStrike() {
        if (isEnabled() && stage == Stage.IDLE) {
            strikeRequested = true;
        }
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        if (self == null) {
            reset();
            return;
        }
        switch (stage) {
            case IDLE -> {
                if (strikeRequested) {
                    strikeRequested = false;
                    if (findBreachMace(self)) {
                        selectedBeforeSwap = InventoryManager.selectedHotbar(self);
                        attackedSinceSwap = false;
                        stageTimer.restart(swapDelayMin.get(), swapDelayMax.get());
                        stage = Stage.SWAP;
                    }
                }
            }
            case SWAP -> {
                if (!stageTimer.isDue()) {
                    return;
                }
                performSwap(client, self);
            }
            case SWAPPED -> {
                // Window closes when the strike happened (mace cooldown engaged) or timeout.
                strikeWindowTicks++;
                // Maces have no item cooldown, so the old "coolingDown" check never fired and
                // the mace stayed out for the full 30-tick timeout after every hit.
                boolean struck = attackedSinceSwap;
                if (struck || strikeWindowTicks > 30) {
                    strikeWindowTicks = 0;
                    attackedSinceSwap = false;
                    if (restoreAfter.get() && needsRestore(self)) {
                        stageTimer.restart(restoreDelayMin.get(), restoreDelayMax.get());
                        stage = Stage.RESTORE;
                    } else {
                        reset();
                    }
                }
            }
            case RESTORE -> {
                if (!stageTimer.isDue()) {
                    return;
                }
                InventoryManager.selectHotbar(client, selectedBeforeSwap);
                if (breachHandlerSlot != -1) {
                    // Undo the inventory exchange (mace back to storage, weapon to hotbar).
                    InventoryManager.swapWithHotbar(client, breachHandlerSlot, selectedBeforeSwap);
                }
                reset();
            }
        }
    }

    private void performSwap(MinecraftClient client, ClientPlayerEntity self) {
        if (breachHotbarSlot != -1) {
            InventoryManager.selectHotbar(client, breachHotbarSlot);
        } else if (breachHandlerSlot != -1) {
            InventoryManager.swapWithHotbar(client, breachHandlerSlot, InventoryManager.selectedHotbar(self));
        } else {
            reset();
            return;
        }
        strikeWindowTicks = 0;
        stage = Stage.SWAPPED;
    }

    private boolean findBreachMace(ClientPlayerEntity self) {
        int hotbar = -1;
        int handler = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = self.getInventory().getStack(i);
            if (isQualifyingMace(stack)) {
                hotbar = i;
                break;
            }
        }
        if (hotbar == -1) {
            handler = InventoryManager.findHandlerSlot(self, this::isQualifyingMace);
        }
        breachHotbarSlot = hotbar;
        breachHandlerSlot = handler;
        return hotbar != -1 || handler != -1;
    }

    private boolean isQualifyingMace(ItemStack stack) {
        if (!ItemUtil.isMace(stack)) {
            return false;
        }
        return !onlyBreach.get() || ItemUtil.enchantLevel(stack, "breach") > 0;
    }

    private boolean needsRestore(ClientPlayerEntity self) {
        return InventoryManager.selectedHotbar(self) != selectedBeforeSwap || breachHandlerSlot != -1;
    }

    private void reset() {
        stage = Stage.IDLE;
        breachHotbarSlot = -1;
        breachHandlerSlot = -1;
        selectedBeforeSwap = -1;
        strikeWindowTicks = 0;
        stageTimer.invalidate();
    }

    @Override
    protected void onDisable() {
        reset();
    }

    @Override
    public String hudLabel() {
        return name + " · " + stage.name().toLowerCase();
    }
}
