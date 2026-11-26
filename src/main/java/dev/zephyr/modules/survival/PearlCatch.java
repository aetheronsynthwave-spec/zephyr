package dev.zephyr.modules.survival;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.CombatActions;
import dev.zephyr.core.inventory.InventoryManager;
import dev.zephyr.core.rotation.RotationManager;
import dev.zephyr.core.rotation.RotationRequest;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.timing.MsTimer;
import dev.zephyr.core.util.ItemUtil;
import dev.zephyr.core.util.MathUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/**
 * PearlCatch - one-key emergency catch sequence, run as a visible, human-paced FSM:
 *
 * <pre>TRIGGER (module bind)
 *    v
 *  LOOK UP: the camera genuinely tilts to the configured upward pitch (default 84)
 *    v
 *  PEARL: select the pearl hotbar slot and throw it
 *    |   (a very small randomized gap - default 15-45 ms)
 *    v
 *  WIND CHARGE: select the wind-charge slot and throw it (the wind burst catches the
 *               falling player and cancels the pearl's landing damage)
 *    v
 *  RESTORE: swap back to whatever was held before the trigger</pre>
 *
 * <p>The bind is a TRIGGER, not a toggle: pressing it starts one sequence. Enable the
 * module in the GUI; the key fires the catch. Everything is a vanilla action path
 * (visible rotation, hotbar select packet, right-click use + swing).
 */
public final class PearlCatch extends Module {

    private final NumberSetting lookPitch = num("lookPitch", "Look-up pitch", 50, 90, 1, 90);
    private final NumberSetting gapMin = num("gapMin", "Pearl->wind delay min ms", 5, 500, 5, 200);
    private final NumberSetting gapMax = num("gapMax", "Pearl->wind delay max ms", 5, 600, 5, 260);
    private final NumberSetting restoreMin = num("restMin", "Restore delay min ms", 20, 500, 5, 60);
    private final NumberSetting restoreMax = num("restMax", "Restore delay max ms", 20, 800, 5, 160);
    private final BooleanSetting windCharge = bool("wind", "Finish with wind charge", true);

    private enum Stage { IDLE, TURN, PEARL, GAP, WIND, RESTORE }

    private Stage stage = Stage.IDLE;
    private final MsTimer stageTimer = new MsTimer();
    private int returnSlot = -1;
    private Vec3d lookPoint;
    private int lookTicks;
    private int cooldownTicks;

    public PearlCatch() {
        // G fires one catch sequence; enable in the GUI first.
        super("PearlCatch", ModuleCategory.SURVIVAL, GLFW.GLFW_KEY_G);
    }

    /**
     * The bind starts one catch sequence instead of toggling the module - "should
     * require a trigger". Toggling stays on the GUI left-click.
     */
    @Override
    public void handleKeyPress(int keyCode) {
        if (keyCode == bind().get()) {
            // The key always works: it arms the module if it was off (onTick only runs
            // for enabled modules), then fires the catch.
            if (!isEnabled()) {
                setEnabled(true);
            }
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
            return; // a sequence is already in flight
        }
        returnSlot = InventoryManager.selectedHotbar(self);
        // Look-up point: straight above the eyes, tilted by the configured pitch
        // toward the current view direction so the turn looks like a deliberate flick.
        double dist = 10.0;
        double rad = Math.toRadians(lookPitch.get());
        double horizontal = dist * Math.cos(rad);
        double vertical = dist * Math.sin(rad);
        double yawRad = Math.toRadians(self.getYaw());
        lookPoint = self.getEyePos().add(
                -Math.sin(yawRad) * horizontal, vertical, Math.cos(yawRad) * horizontal);
        lookTicks = 0;
        cooldownTicks = 0;
        stage = Stage.TURN;
    }

    @Override
    protected void onDisable() {
        stage = Stage.IDLE;
        stageTimer.invalidate();
        lookPoint = null;
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        if (self == null || client.world == null) {
            stage = Stage.IDLE;
            return;
        }
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }
        switch (stage) {
            case IDLE -> { }
            case TURN -> turnStage(client, self);
            case PEARL -> pearlStage(client, self);
            case GAP -> {
                if (smart() && windCharge.get()) {
                    smartGap(client, self);
                    return;
                }
                if (stageTimer.isDue()) {
                    if (windCharge.get() && beginItem(client, self, ItemUtil::isWindCharge, true)) {
                        stage = Stage.WIND;
                    } else {
                        beginRestore(client);
                    }
                }
            }
            case WIND -> windStage(client, self);
            case RESTORE -> restoreStage(client, self);
        }
    }

    private void turnStage(MinecraftClient client, ClientPlayerEntity self) {
        // Visible rotation to the up-look point.
        // Keep the current yaw and tilt straight up (the clip: pearl and wind charge both
        // thrown vertically, so the wind charge meets the rising pearl on the same line).
        double upPitch = -lookPitch.get();
        if (smart() && self.getPitch() < -20.0) {
            // SMART: you are already aiming upward - throw along YOUR line (diagonal
            // catches); the intercept solver handles the wind charge for any angle.
            if (beginItem(client, self, ItemUtil::isPearl, false)) {
                stage = Stage.PEARL;
            } else if (++lookTicks > 25) {
                beginRestore(client);
            }
            return;
        }
        RotationManager.request(RotationRequest
                .to(self.getYaw(), upPitch)
                .speed(45, 95).jitter(0.2).silent(false)
                .expiresAfter(4).build());
        if (Math.abs(self.getPitch() - upPitch) > 3.0) {
            if (++lookTicks > 20) {
                beginRestore(client); // look never converged (blocked?), abort cleanly
            }
            return;
        }
        if (!beginItem(client, self, ItemUtil::isPearl, false)) {
            if (++lookTicks > 25) {
                beginRestore(client);
            }
            return;
        }
        stage = Stage.PEARL;
    }

    private void pearlStage(MinecraftClient client, ClientPlayerEntity self) {
        ItemStack held = self.getMainHandStack();
        if (!ItemUtil.isPearl(held)) {
            beginRestore(client);
            return;
        }
        if (cooldownTicks > 0 || ItemUtil.coolingDown(self, held)) {
            cooldownTicks = Math.max(cooldownTicks, 5);
            return; // pearl still on item cooldown - hold the aim a few ticks
        }
        CombatActions.interactItem(client);
        smartTicks = 0;
        stageTimer.restart(gapMin.get(), gapMax.get());
        stage = Stage.GAP;
    }

    // ------------------------------------------------------------------
    // SMART: intercept math
    // ------------------------------------------------------------------

    private static final double PEARL_DRAG = 0.99;
    private static final double PEARL_GRAVITY = 0.03;
    private static final double WIND_SPEED = 1.5;
    private int smartTicks;

    /**
     * Tracks our own pearl and solves where a wind charge thrown NOW meets it:
     * the pearl is stepped forward tick by tick with vanilla thrown-projectile physics
     * (move, then velocity *= 0.99, then vy -= 0.03); the wind charge flies in a straight
     * line at 1.5 blocks/tick plus our own motion. The earliest tick k at which the wind
     * charge can be exactly where the pearl will be (|gap - 1.5k| < half a tick of travel)
     * gives the aim point; we turn there and throw once the aim has arrived. Works for any
     * throw angle (diagonal catches included), not just straight up.
     */
    private void smartGap(MinecraftClient client, ClientPlayerEntity self) {
        smartTicks++;
        net.minecraft.entity.projectile.thrown.EnderPearlEntity pearl = null;
        for (var e : client.world.getEntities()) {
            if (e instanceof net.minecraft.entity.projectile.thrown.EnderPearlEntity ep && ep.getOwner() == self
                    && (pearl == null || ep.age < pearl.age)) {
                pearl = ep;
            }
        }
        if (pearl == null) {
            if (smartTicks > 6) {
                // pearl never showed up (or already landed): fall back to the timed throw
                smartTicks = 0;
                stageTimer.restart(0, 0);
                if (beginItem(client, self, ItemUtil::isWindCharge, true)) {
                    stage = Stage.WIND;
                } else {
                    beginRestore(client);
                }
            }
            return;
        }
        Vec3d eye = self.getEyePos();
        Vec3d selfVel = self.isOnGround() ? new Vec3d(self.getVelocity().x, 0, self.getVelocity().z) : self.getVelocity();
        Vec3d p = pearl.getEntityPos();
        Vec3d v = pearl.getVelocity();
        Vec3d aimPoint = null;
        for (int k = 1; k <= 30; k++) {
            p = p.add(v);
            v = v.multiply(PEARL_DRAG).add(0, -PEARL_GRAVITY, 0);
            Vec3d rel = p.subtract(eye.add(selfVel.multiply(k)));
            double gap = rel.length();
            if (gap < 1.2) {
                continue; // too close: the burst would just hit us
            }
            if (Math.abs(gap - WIND_SPEED * k) <= WIND_SPEED * 0.5) {
                aimPoint = p;
                break;
            }
        }
        if (aimPoint == null) {
            if (smartTicks > 25) {
                beginRestore(client); // no intercept possible any more
            }
            return;
        }
        RotationManager.request(RotationRequest
                .to(MathUtil.yawTo(eye, aimPoint), MathUtil.pitchTo(eye, aimPoint))
                .speed(60, 120).jitter(0.1).silent(false)
                .expiresAfter(3).build());
        if (RotationManager.isAimedAt(aimPoint, 3.0)
                && beginItem(client, self, ItemUtil::isWindCharge, true)) {
            smartTicks = 0;
            stage = Stage.WIND;
        }
    }

    private void windStage(MinecraftClient client, ClientPlayerEntity self) {
        ItemStack held = self.getMainHandStack();
        if (!ItemUtil.isWindCharge(held) || ItemUtil.coolingDown(self, held)) {
            beginRestore(client);
            return;
        }
        CombatActions.interactItem(client);
        beginRestore(client);
    }

    private void restoreStage(MinecraftClient client, ClientPlayerEntity self) {
        if (!stageTimer.isDue()) {
            return;
        }
        if (returnSlot != -1 && !InventoryManager.selectHotbar(client, returnSlot)) {
            return; // slot already changed this tick: restore on the next tick
        }
        stage = Stage.IDLE;
        stageTimer.invalidate();
    }

    private void beginRestore(MinecraftClient client) {
        stageTimer.restart(restoreMin.get(), restoreMax.get());
        stage = Stage.RESTORE;
    }

    /** Selects a hotbar slot holding the item; true when the item is now in hand. */
    private boolean beginItem(MinecraftClient client, ClientPlayerEntity self,
                              java.util.function.Predicate<ItemStack> pred,
                              boolean swapFromInventory) {
        int slot = InventoryManager.findHotbarSlot(self, pred);
        if (slot != -1) {
            InventoryManager.selectHotbar(client, slot);
            ItemStack held = self.getMainHandStack();
            if (pred.test(held)) {
                return true;
            }
        }
        if (!swapFromInventory) {
            return false;
        }
        int handler = InventoryManager.findHandlerSlot(self, pred);
        if (handler == -1) {
            return false;
        }
        int swapSlot = pickSacrificialSlot(self);
        if (swapSlot == -1) {
            return false;
        }
        InventoryManager.swapWithHotbar(client, handler, swapSlot);
        return pred.test(self.getInventory().getStack(swapSlot));
    }

    /** Hotbar slot for inventory swaps - never the pre-trigger "return" slot. */
    private int pickSacrificialSlot(ClientPlayerEntity self) {
        for (int i = 8; i >= 0; i--) {
            if (i != returnSlot && self.getInventory().getStack(i).isEmpty()) {
                return i;
            }
        }
        for (int i = 8; i >= 0; i--) {
            if (i != returnSlot) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String hudLabel() {
        return name + " · " + stage.name().toLowerCase();
    }
}
