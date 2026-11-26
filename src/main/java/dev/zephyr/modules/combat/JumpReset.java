package dev.zephyr.modules.combat;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.event.ZephyrEvents;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.timing.RandomController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * JumpReset - the combo-breaker technique, fired in the SAME TICK the damage registers.
 *
 * <p>Mechanics: vanilla applies knockback while the target is grounded as mostly
 * horizontal push. If the jump flag is already live during the movement tick that
 * carries the hit, the player leaves the ground on that exact tick and the same
 * knockback is absorbed on top of the upward jump arc - the "jump reset" that turns a
 * combo into a short upward pop instead of a horizontal drag. Timing is everything:
 * one tick late and the horizontal velocity is already committed.
 *
 * <p>Pipeline (all detection happens inside the movement-tick HEAD hook, so the jump
 * flag is set BEFORE vanilla's movement code runs on the damage tick itself):
 * <ol>
 *   <li><b>Detect</b>: a fresh hit is {@code hurtTime} jumping back up (10 -> 9..)
 *       or a health drop vs. the previous movement tick. Both signals are evaluated
 *       right here - not at end-of-tick - so the reaction costs ZERO ticks.</li>
 *   <li><b>Fire</b>: if grounded, the vanilla jump input flag is set immediately for
 *       this single tick - the identical code path a physical spacebar press takes,
 *       indistinguishable from a human frame-perfect reset.</li>
 *   <li><b>Humanize</b>: a configurable success chance models real reaction misses;
 *       a hit that lands mid-air re-arms and fires on the first grounded tick.</li>
 * </ol>
 */
public final class JumpReset extends Module {

    private final NumberSetting armWindow = num("armWindow", "Arm window ticks", 0, 12, 1, 6);
    private final NumberSetting successChance = num("chance", "Reset success", 0.30, 1.0, 0.05, 1.0);
    private final BooleanSetting resetInAir = bool("inAir", "Arm when hit airborne", true);

    private double lastHealth = -1.0;
    /** Set by the knockback packet hook; consumed by the next movement tick. */
    private boolean knockbackPending;
    private int lastHurtTime;
    private boolean armed;
    private int armedTicksLeft;
    /** Our one-tick jump flag, cleared at the next movement tick. */
    private boolean jumpPulse;

    public JumpReset() {
        super("JumpReset", ModuleCategory.COMBAT, 0);
        ZephyrEvents.GROUND_LAND.subscribe(this::onLand); // once, not per toggle
    }

    @Override
    protected void onDisable() {
        clearPulse();
        armed = false;
        lastHealth = -1.0;
        lastHurtTime = 0;
    }

    /**
     * Movement-tick HEAD hook, called directly from {@code MixinLivingEntity}. Damage
     * detection AND the jump fire happen here, on the same tick the hit data arrived -
     * the vanilla jump code then runs a few instructions later in the same tick.
     */
    public void zephyr$onMovementHook(ClientPlayerEntity self) {
        jumpPulse = false;
        if (!isEnabled()) {
            armed = false;
            lastHealth = -1.0;
            lastHurtTime = 0;
            return;
        }

        // ---- damage detection (same tick the client sees the hit) ----
        int hurt = self.hurtTime;
        // hurtTime snaps to 10 on an accepted server hit and decays each tick, so a
        // JUMP back up to >= 9 marks a brand-new hit (not the decay of an older one).
        boolean freshHurt = hurt > lastHurtTime && hurt >= 9;
        double health = self.getHealth();
        boolean healthDrop = lastHealth >= 0.0 && health < lastHealth - 0.01;
        lastHurtTime = hurt;
        lastHealth = health;

        if (knockbackPending) {
            // Exact moment: the knockback velocity just arrived this tick.
            knockbackPending = false;
            armed = true;
            armedTicksLeft = Math.max(1, armWindow.get().intValue());
        } else if ((freshHurt || healthDrop) && !smart()) {
            // Non-smart fallback: any hit arms it (SMART waits for real knockback, so
            // fire/fall/poison damage never makes you hop).
            armed = true;
            armedTicksLeft = Math.max(1, armWindow.get().intValue());
        }

        // ---- fire: same tick when grounded, first grounded tick otherwise ----
        if (armed && self.isOnGround() && !self.isTouchingWater() && !self.isInLava()
                && !self.isClimbing()) {
            if (RandomController.chance(successChance.get())) {
                // Applied after vanilla reads the keyboard this tick (see consumeJump):
                // setting the flag here was overwritten by the input read, so the old
                // version never actually jumped.
                jumpPulse = true;
            }
            armed = false;
        } else if (armed && --armedTicksLeft <= 0) {
            armed = false;
        }
    }

    /** Called from the velocity-packet hook with the server-sent knockback velocity. */
    public void onKnockback(net.minecraft.util.math.Vec3d velocity) {
        if (!isEnabled()) {
            return;
        }
        // Real melee knockback has a horizontal push and lifts you slightly.
        if (Math.hypot(velocity.x, velocity.z) > 0.08 || velocity.y > 0.1) {
            knockbackPending = true;
        }
    }

    /** Hit landed while airborne: re-arm so the jump fires the tick we touch ground. */
    private void onLand(ZephyrEvents.GroundLand event) {
        if (!isEnabled() || !resetInAir.get()) {
            return;
        }
        if (armed && event.player.isOnGround()) {
            armedTicksLeft = 1; // jump next movement tick, now that we are grounded
        }
    }

    private void clearPulse() {
        jumpPulse = false;
    }

    /** Called right after vanilla reads movement input; true = force the jump this tick. */
    public boolean consumeJump() {
        boolean j = jumpPulse && isEnabled();
        jumpPulse = false;
        return j;
    }

    @Override
    public String hudLabel() {
        return name + (armed ? " · armed " + armedTicksLeft + "t" : "");
    }
}
