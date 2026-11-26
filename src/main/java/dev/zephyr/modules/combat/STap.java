package dev.zephyr.modules.combat;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.event.ZephyrEvents;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.ModeSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.target.TargetManager;
import dev.zephyr.core.timing.RandomController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.EntityHitResult;

import java.util.List;

/**
 * STap / WTap - the combo sprint-reset, done the way PvP players do it:
 *
 * <ol>
 *   <li>You hit while sprinting (sprint hits deal extra knockback).</li>
 *   <li>Right after the hit, movement goes BACK (S-tap) or just stops going forward
 *       (W-tap) for 1-3 ticks. Losing forward input ends the sprint, and the S-tap also
 *       gives you a little spacing so the opponent cannot trade back.</li>
 *   <li>Forward resumes and sprint is re-engaged, so the NEXT hit is a sprint hit again
 *       with full knockback - that is what keeps the combo going.</li>
 * </ol>
 *
 * <p>The old version pressed the W/S key bindings, which the real W key's repeat events
 * kept overwriting, so it barely did anything. This one overrides the movement input
 * itself for those ticks (MixinKeyboardInput), after the keyboard has been read.
 */
public final class STap extends Module {

    private final ModeSetting mode = s(new ModeSetting("mode", "Mode", List.of("S_TAP", "W_TAP"), "S_TAP"));
    private final NumberSetting tapMin = num("tapMin", "Tap ticks min", 1, 6, 1, 2);
    private final NumberSetting tapMax = num("tapMax", "Tap ticks max", 1, 8, 1, 3);
    private final NumberSetting delay = num("delay", "Delay after hit (ticks)", 0, 4, 1, 0);
    private final NumberSetting maxRange = num("maxRange", "Max combo range", 2.0, 6.0, 0.1, 4.0);
    private final BooleanSetting onlySprinting = bool("onlySprint", "Only on sprint hits", true);
    private final BooleanSetting resprint = bool("resprint", "Re-sprint after tap", true);
    private final BooleanSetting skipIfCrouching = bool("noCrouch", "Skip while crouching", true);

    private static STap instance;

    private int waitTicks = -1;
    private int tapTicks;
    private boolean resprintPending;
    private int taps;
    private boolean sprintSample;

    public STap() {
        super("STap", ModuleCategory.COMBAT, 0);
        instance = this;
        ZephyrEvents.ATTACK_LANDED.subscribe(this::onAttack);
    }

    @Override
    protected void onDisable() {
        waitTicks = -1;
        tapTicks = 0;
        resprintPending = false;
    }

    private void onAttack(ZephyrEvents.AttackLanded event) {
        if (!isEnabled() || !event.landed) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity self = client.player;
        if (self == null || tapTicks > 0 || waitTicks >= 0) {
            return;
        }
        if (self.isGliding() || self.isTouchingWater() || self.isInLava() || self.isUsingItem()
                || (skipIfCrouching.get() && self.isSneaking())) {
            return;
        }
        // Vanilla clears the sprint flag as part of a sprint hit, so judge by the sprint
        // state sampled at the end of the previous tick (i.e. right before this hit).
        if (onlySprinting.get() && !(sprintSample || self.isSprinting())) {
            return;
        }
        LivingEntity target = TargetManager.currentTarget();
        if (target == null && client.crosshairTarget instanceof EntityHitResult ehr
                && ehr.getEntity() instanceof LivingEntity hit) {
            target = hit;
        }
        if (target == null || self.squaredDistanceTo(target) > maxRange.get() * maxRange.get()) {
            return;
        }
        waitTicks = delay.get().intValue();
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        if (self == null) {
            return;
        }
        if (waitTicks >= 0) {
            if (waitTicks-- <= 0) {
                int lo = tapMin.get().intValue();
                int hi = Math.max(lo, tapMax.get().intValue());
                tapTicks = (int) Math.round(RandomController.uniform(lo, hi));
                waitTicks = -1;
                taps++;
            }
        } else if (tapTicks > 0) {
            if (--tapTicks == 0) {
                resprintPending = resprint.get();
            }
        } else if (resprintPending) {
            resprintPending = false;
            // Forward is back: re-engage sprint so the next hit is a sprint hit again.
            if (client.options.forwardKey.isPressed() && !self.isSneaking() && !self.isUsingItem()
                    && self.getHungerManager().getFoodLevel() > 6) {
                self.setSprinting(true);
            }
        }
        sprintSample = self.isSprinting();
    }

    /** 0 = no override, 1 = S-tap (move back), 2 = W-tap (stop forward). Read by MixinKeyboardInput. */
    public static int inputOverride() {
        STap s = instance;
        if (s == null || !s.isEnabled() || s.tapTicks <= 0) {
            return 0;
        }
        return s.mode.is("S_TAP") ? 1 : 2;
    }

    @Override
    public String hudLabel() {
        return name + " · " + mode.get().replace('_', '-').toLowerCase() + " · " + taps;
    }
}
