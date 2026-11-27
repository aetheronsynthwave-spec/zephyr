package dev.zephyr.modules.survival;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.DamageCalculator;
import dev.zephyr.core.inventory.InventoryManager;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.ModeSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.timing.MsTimer;
import dev.zephyr.core.timing.RandomController;
import dev.zephyr.core.util.ItemUtil;
import dev.zephyr.core.world.EntityScan;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

/**
 * AutoTotem - offhand restocking that walks through the inventory like a player.
 *
 * <p>Two execution profiles:
 * <ul>
 *   <li><b>STANDARD</b>: the full inventory choreography - optionally opens the
 *       inventory screen (8-24 ms), PICKUP-clicks a totem off its slot, carries it to
 *       the offhand slot, returns the leftover cursor stack, and closes the screen.
 *       Every hop is a vanilla screen-handler click paced by an independently
 *       randomized 20-55 ms micro-timer, so the click stream is bursty and uneven
 *       exactly like human inventory management under pressure.</li>
 *   <li><b>HOVER</b>: fastest possible restock - if a totem is already on the mouse
 *       cursor (player mid-drag), it clicks straight into the offhand.</li>
 * </ul>
 *
 * <p>Escalation: when the player is in "panic" (critical health band), the micro-timers
 * run at a tightened band and the screen-open step is skipped. Danger gates: crystal
 * proximity, fire/lava, a fatal fall in progress, or a incoming blast predicted to
 * pierce the remaining health.
 */
public final class AutoTotem extends Module {

    private final ModeSetting mode = s(new ModeSetting("mode", "Mode",
            java.util.List.of("STANDARD", "HOVER"), "STANDARD"));
    private final NumberSetting healthThreshold = num("health", "Health threshold", 2.0, 20.0, 0.5, 8.0);
    private final NumberSetting panicHealth = num("panic", "Panic health", 0.0, 10.0, 0.5, 4.0);
    private final NumberSetting crystalProximity = num("crystalRange", "Crystal danger range", 0.0, 8.0, 0.5, 4.0);
    private final NumberSetting clickMin = num("clickMin", "Click delay min ms", 10, 120, 1, 20);
    private final NumberSetting clickMax = num("clickMax", "Click delay max ms", 10, 150, 1, 55);
    private final BooleanSetting openScreen = bool("openScreen", "Open inventory visually", true);
    private final BooleanSetting dangerFire = bool("fire", "Danger: fire/fall", true);
    private final BooleanSetting closeDelay = bool("closeDelay", "Randomized close delay", true);

    private enum Stage { IDLE, OPEN, PICK, PLACE, RETURN, CLOSE }

    private Stage stage = Stage.IDLE;
    private final MsTimer timer = new MsTimer();
    private int sourceSlot = -1;

    /** Danger-eval throttle: the lethal-blast forecast is expensive (raycasts per
     *  crystal), so it is cached for a few ticks and refreshed instantly on damage. */
    private boolean dangerCache;
    private int dangerRefresh;
    private double lastHealth = -1.0;

    public AutoTotem() {
        super("AutoTotem", ModuleCategory.SURVIVAL, 0);
    }

    @Override
    protected void onDisable() {
        stage = Stage.IDLE;
        sourceSlot = -1;
        timer.invalidate();
        dangerRefresh = 0;
        lastHealth = -1.0;
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        if (self == null) {
            stage = Stage.IDLE;
            return;
        }

        switch (stage) {
            case IDLE -> idle(client, self);
            case OPEN -> openStage(client, self);
            case PICK -> pickStage(client, self);
            case PLACE -> placeStage(client, self);
            case RETURN -> returnStage(client, self);
            case CLOSE -> closeStage(client, self);
        }
    }

    // ------------------------------------------------------------------
    // Stage machine
    // ------------------------------------------------------------------

    private void idle(MinecraftClient client, ClientPlayerEntity self) {
        if (ItemUtil.isTotem(self.getOffHandStack())) {
            return; // healthy offhand
        }
        boolean lowHealth = self.getHealth() <= healthThreshold.get();
        boolean inDanger = cachedDanger(client, self);
        if (!lowHealth && !inDanger) {
            return;
        }

        // HOVER fast path: a totem already under the cursor goes straight to the offhand.
        if (mode.is("HOVER")) {
            ItemStack cursor = self.currentScreenHandler.getCursorStack();
            if (ItemUtil.isTotem(cursor)) {
                InventoryManager.clickSlot(client, 45, 0, SlotActionType.PICKUP);
                timer.restart(clickMin.get(), clickMax.get());
                stage = Stage.CLOSE;
                sourceSlot = -2; // cursor-only flow
                return;
            }
        }

        int slot = InventoryManager.findHandlerSlot(self, ItemUtil::isTotem);
        if (slot == -1) {
            return; // no totems left
        }
        sourceSlot = slot;

        boolean panic = self.getHealth() <= panicHealth.get();
        if (panic || !openScreen.get() || client.currentScreen != null) {
            stage = Stage.PICK; // skip the visual open (already open, or panic speed)
        } else {
            stage = Stage.OPEN;
        }
        // Panic tightens the whole band ~40%.
        double scale = panic ? 0.6 : 1.0;
        timer.restart(clickMin.get() * scale, clickMax.get() * scale);
    }

    private void openStage(MinecraftClient client, ClientPlayerEntity self) {
        if (!timer.isDue()) {
            return;
        }
        if (!(client.currentScreen instanceof InventoryScreen)) {
            client.setScreen(new InventoryScreen(self));
        }
        stage = Stage.PICK;
        restartJittered();
    }

    private void pickStage(MinecraftClient client, ClientPlayerEntity self) {
        if (!timer.isDue()) {
            return;
        }
        // Re-validate: the source stack may have moved while the screen was opening.
        if (ItemUtil.isTotem(self.currentScreenHandler.getSlot(sourceSlot).getStack())) {
            InventoryManager.clickSlot(client, sourceSlot, 0, SlotActionType.PICKUP);
            stage = Stage.PLACE;
        } else {
            int slot = InventoryManager.findHandlerSlot(self, ItemUtil::isTotem);
            if (slot == -1) {
                finish(client);
                return;
            }
            sourceSlot = slot;
            InventoryManager.clickSlot(client, sourceSlot, 0, SlotActionType.PICKUP);
            stage = Stage.PLACE;
        }
        restartJittered();
    }

    private void placeStage(MinecraftClient client, ClientPlayerEntity self) {
        if (!timer.isDue()) {
            return;
        }
        // Second click drops the carried totem into the offhand slot (45).
        if (ItemUtil.isTotem(self.currentScreenHandler.getCursorStack())) {
            InventoryManager.clickSlot(client, 45, 0, SlotActionType.PICKUP);
            stage = Stage.RETURN;
        } else if (ItemUtil.isTotem(self.getOffHandStack())) {
            stage = Stage.RETURN; // already done (swap race)
        } else {
            // Lost the carried stack somehow - restart the pick from scratch.
            stage = Stage.PICK;
        }
        restartJittered();
    }

    private void returnStage(MinecraftClient client, ClientPlayerEntity self) {
        if (!timer.isDue()) {
            return;
        }
        // Put whatever the offhand used to hold back where the totem came from.
        ItemStack cursor = self.currentScreenHandler.getCursorStack();
        if (!cursor.isEmpty() && sourceSlot >= 0) {
            InventoryManager.clickSlot(client, sourceSlot, 0, SlotActionType.PICKUP);
        }
        stage = Stage.CLOSE;
        if (closeDelay.get()) {
            timer.restart(clickMin.get(), clickMax.get());
        } else {
            timer.restart(2.0, 8.0);
        }
    }

    private void closeStage(MinecraftClient client, ClientPlayerEntity self) {
        if (!timer.isDue()) {
            return;
        }
        finish(client);
    }

    private void finish(MinecraftClient client) {
        if (client.currentScreen instanceof InventoryScreen) {
            client.setScreen(null);
        }
        stage = Stage.IDLE;
        sourceSlot = -1;
        timer.invalidate();
    }

    /** Fresh randomized per-click delay - never a fixed period. */
    private void restartJittered() {
        double d = RandomController.uniform(clickMin.get(), clickMax.get());
        timer.restart(d, d + 4.0);
    }

    // ------------------------------------------------------------------
    // Danger evaluation
    // ------------------------------------------------------------------

    /**
     * Danger flag with a 3-tick cache. The lethal-blast forecast raycasts per nearby
     * crystal; running it every tick was pure waste when nothing moved. A health drop
     * (fresh damage) always forces an immediate re-evaluation - panic must react now.
     */
    private boolean cachedDanger(MinecraftClient client, ClientPlayerEntity self) {
        boolean damaged = lastHealth >= 0.0 && self.getHealth() < lastHealth - 0.01;
        lastHealth = self.getHealth();
        if (dangerRefresh > 0 && !damaged) {
            dangerRefresh--;
            return dangerCache;
        }
        dangerCache = dangerEvaluation(client, self);
        dangerRefresh = 3;
        return dangerCache;
    }

    /** Crystal proximity, standing in fire/lava, a fatal fall, or a lethal incoming blast. */
    private boolean dangerEvaluation(MinecraftClient client, ClientPlayerEntity self) {
        if (crystalProximity.get() > 0) {
            double rangeSq = crystalProximity.get() * crystalProximity.get();
            for (var e : EntityScan.around(client.world, self.getLerpedPos(1.0F), crystalProximity.get())) {
                if (e instanceof EndCrystalEntity crystal
                        && crystal.squaredDistanceTo(self) <= rangeSq
                        && crystal.isAlive()) {
                    return true;
                }
            }
        }
        if (dangerFire.get()) {
            if (self.isInLava() || self.isOnFire() || self.fallDistance > 8.0) {
                return true;
            }
        }
        // Lethal-blast forecast: any crystal that would take us below one heart.
        for (var e : EntityScan.around(client.world, self.getLerpedPos(1.0F), 6.0)) {
            if (e instanceof EndCrystalEntity crystal && crystal.isAlive()) {
                double dmg = DamageCalculator.crystalDamage(client.world,
                        crystal.getLerpedPos(1.0F), self);
                if (dmg >= self.getHealth() + self.getAbsorptionAmount() - 1.0) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public String hudLabel() {
        return name + " · " + mode.get().toLowerCase()
                + (stage != Stage.IDLE ? " · " + stage.name().toLowerCase() : "");
    }
}
