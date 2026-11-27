package dev.zephyr.modules.combat;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.CombatActions;
import dev.zephyr.core.inventory.InventoryManager;
import dev.zephyr.core.rotation.RotationManager;
import dev.zephyr.core.rotation.RotationRequest;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.target.TargetCriteria;
import dev.zephyr.core.target.TargetManager;
import dev.zephyr.core.timing.MsTimer;
import dev.zephyr.core.util.ItemUtil;
import dev.zephyr.core.util.MathUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/**
 * LungeMacro - one-key attribute-swap macro that erases the attack-charge cooldown.
 *
 * <p>The trick: a player's attack-charge meter is tied to the ATTACK SPEED ATTRIBUTE of
 * the held item. Vanilla resets the charge when the held-item attributes change - so
 * swapping from a sword (slow attack speed, mid-charge) to a spear (fast attack speed)
 * instantly refills the meter and lets the spear land a FULL-STRENGTH hit the moment
 * the server registers the slot change.
 *
 * <pre>TRIGGER (module bind)
 *    v
 *  SWORD: if the current weapon's charge is already full -> hit with it right now
 *    |    (the sword hit also respects target hurt-frames)
 *    v
 *  SWAP: swap to the lunge spear in the hotbar (attribute swap resets the charge)
 *    v
 *  SPEAR: rotate to the target (visible) and land the full-strength spear hit
 *    v
 *  RESTORE: swap back to whatever was held before the trigger</pre>
 *
 * <p>The bind is a TRIGGER, not a toggle. Enable the module in the GUI; the key fires
 * one engagement.
 */
public final class LungeMacro extends Module {

    private final NumberSetting engageRange = num("engageRange", "Engage range", 1.5, 5.0, 0.1, 3.2);
    private final NumberSetting cooldownGate = num("gate", "Sword-hit charge gate", 0.5, 1.0, 0.05, 0.95);
    private final NumberSetting swapMin = num("swapMin", "Swap delay min ms", 5, 100, 1, 10);
    private final NumberSetting swapMax = num("swapMax", "Swap delay max ms", 5, 200, 1, 30);
    private final NumberSetting restoreMin = num("restMin", "Restore delay min ms", 30, 500, 10, 80);
    private final NumberSetting restoreMax = num("restMax", "Restore delay max ms", 30, 800, 10, 240);
    private final BooleanSetting rotate = bool("rotate", "Rotate to target", true);

    private enum Stage { IDLE, SWORD, SWAP, SPEAR, RESTORE }

    private Stage stage = Stage.IDLE;
    private final MsTimer stageTimer = new MsTimer();
    private LivingEntity target;
    private int returnSlot = -1;
    private int stageTicks;

    public LungeMacro() {
        // H fires one engagement; enable in the GUI first.
        super("LungeMacro", ModuleCategory.COMBAT, GLFW.GLFW_KEY_H);
    }

    /** The bind fires one engagement instead of toggling the module. */
    @Override
    public void handleKeyPress(int keyCode) {
        if (keyCode == bind().get()) {
            trigger();
        }
    }

    private void trigger() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity self = client.player;
        if (!isEnabled() || self == null || client.world == null) {
            return;
        }
        if (stage != Stage.IDLE) {
            return; // a chain is already running
        }
        target = TargetManager.acquire(client, new TargetCriteria().range(engageRange.get()));
        TargetManager.cleanup();
        if (target == null || !target.isAlive()) {
            return;
        }
        returnSlot = InventoryManager.selectedHotbar(self);
        stageTicks = 0;
        if (ItemUtil.attackCooldownProgress(self) >= cooldownGate.get()) {
            stage = Stage.SWORD;
            stageTimer.restart(0.0, 6.0);
        } else {
            stage = Stage.SWAP;
            stageTimer.restart(0.0, 6.0);
        }
    }

    @Override
    protected void onDisable() {
        abort(client());
    }

    private MinecraftClient client() {
        return MinecraftClient.getInstance();
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        if (self == null || client.world == null) {
            stage = Stage.IDLE;
            return;
        }
        if (stage != Stage.IDLE) {
            // Target validity is re-checked at every stage - a dead or vanished
            // target drops the chain straight to RESTORE instead of firing at air.
            if (target == null || !target.isAlive()
                    || self.squaredDistanceTo(target) > (engageRange.get() + 1.5)
                    * (engageRange.get() + 1.5)) {
                beginRestore(client);
                return;
            }
        }
        stageTicks++;
        switch (stage) {
            case IDLE -> { }
            case SWORD -> swordStage(client, self);
            case SWAP -> swapStage(client, self);
            case SPEAR -> spearStage(client, self);
            case RESTORE -> restoreStage(client, self);
        }
    }

    private void swordStage(MinecraftClient client, ClientPlayerEntity self) {
        if (aimAtTarget(client, self)
                && target.hurtTime <= 8
                && ItemUtil.attackCooldownProgress(self) >= cooldownGate.get()
                && self.squaredDistanceTo(target) <= engageRange.get() * engageRange.get()
                && stageTimer.isDue()) {
            CombatActions.attackEntity(client, target);
            stage = Stage.SWAP;
            stageTimer.restart(0.0, 6.0);
            stageTicks = 0;
            return;
        }
        if (stageTicks > 12) {
            // Charge never came back (or aim blocked) - skip straight to the spear.
            stage = Stage.SWAP;
            stageTimer.restart(0.0, 6.0);
            stageTicks = 0;
        }
    }

    private void swapStage(MinecraftClient client, ClientPlayerEntity self) {
        if (ItemUtil.isSpear(self.getMainHandStack())) {
            stage = Stage.SPEAR;
            stageTicks = 0;
            return;
        }
        if (!stageTimer.isDue()) {
            return;
        }
        int spearSlot = InventoryManager.findHotbarSlot(self, ItemUtil::isSpear);
        if (spearSlot == -1) {
            // No spear available: the attribute swap is impossible, restore and stop.
            beginRestore(client);
            return;
        }
        InventoryManager.selectHotbar(client, spearSlot);
        stage = Stage.SPEAR;
        stageTicks = 0;
    }

    private void spearStage(MinecraftClient client, ClientPlayerEntity self) {
        if (!ItemUtil.isSpear(self.getMainHandStack())) {
            if (stageTicks > 6) {
                beginRestore(client);
            }
            return; // give the slot packet a tick to land
        }
        // The attribute swap refilled the charge; the spear hit is full-strength.
        if (aimAtTarget(client, self)
                && target.hurtTime <= 8
                && self.squaredDistanceTo(target) <= engageRange.get() * engageRange.get()
                && stageTicks >= 1) {
            CombatActions.attackEntity(client, target);
            beginRestore(client);
            return;
        }
        if (stageTicks > 14) {
            beginRestore(client);
        }
    }

    private void restoreStage(MinecraftClient client, ClientPlayerEntity self) {
        if (!stageTimer.isDue()) {
            return;
        }
        finishRestore(client);
    }

    private void beginRestore(MinecraftClient client) {
        stageTimer.restart(restoreMin.get(), restoreMax.get());
        stage = Stage.RESTORE;
        stageTicks = 0;
    }

    private void finishRestore(MinecraftClient client) {
        if (returnSlot != -1) {
            InventoryManager.selectHotbar(client, returnSlot);
        }
        stage = Stage.IDLE;
        stageTimer.invalidate();
        target = null;
    }

    private void abort(MinecraftClient client) {
        stage = Stage.IDLE;
        stageTimer.invalidate();
        target = null;
    }

    /** Requests a visible rotation toward the target; true when the server view is close enough to hit. */
    private boolean aimAtTarget(MinecraftClient client, ClientPlayerEntity self) {
        Vec3d aim = MathUtil.bestAimPoint(self, target,
                self.getYaw(), self.getPitch());
        if (rotate.get()) {
            RotationManager.request(RotationRequest
                    .to(MathUtil.yawTo(self.getEyePos(), aim),
                            MathUtil.pitchTo(self.getEyePos(), aim))
                    .speed(35, 80).jitter(0.4).silent(false)
                    .validWhile(() -> target != null && target.isAlive())
                    .expiresAfter(4).build());
        }
        return RotationManager.isAimedAt(aim, 35.0);
    }

    @Override
    public String hudLabel() {
        return name + " · " + stage.name().toLowerCase();
    }
}
