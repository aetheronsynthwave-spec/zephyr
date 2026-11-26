package dev.zephyr.modules.combat;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.event.ZephyrEvents;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.target.TargetManager;
import dev.zephyr.core.timing.MsTimer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.LivingEntity;

/**
 * STap - the sword combo rhythm module.
 *
 * <p>Technique: immediately after a hit connects, briefly step BACKWARDS (the classic
 * "S-tap" key pattern), then push FORWARD again into the target. The backward step
 * pulls the opponent's knockback phase forward (they drift into the space you vacated),
 * and the forward push restores reach while your sprint re-arms - so every swing of the
 * combo keeps the opponent inside the hit window instead of letting them drift out.
 *
 * <p>The movement is injected through the vanilla keybinding state (back/forward key
 * presses held for a randomized ms duration) - the server sees plain vanilla movement
 * packets from the standard input pipeline, identical to a player tapping S then W.
 *
 * <p>Trigger discipline: the pattern only arms on a LANDED attack (the
 * {@code doAttack} return hook), while a target is actually inside combo range, and
 * never while gliding/swimming/using an item where a movement tap would look wrong.
 */
public final class STap extends Module {

    private final NumberSetting backMsMin = num("backMin", "Backstep min ms", 30, 300, 5, 90);
    private final NumberSetting backMsMax = num("backMax", "Backstep max ms", 30, 400, 5, 150);
    private final NumberSetting fwdMsMin = num("fwdMin", "Push fwd min ms", 20, 250, 5, 60);
    private final NumberSetting fwdMsMax = num("fwdMax", "Push fwd max ms", 20, 300, 5, 110);
    private final NumberSetting maxRange = num("maxRange", "Max combo range", 2.5, 6.0, 0.1, 4.5);
    private final BooleanSetting requireTarget = bool("needTarget", "Require live target", true);
    private final BooleanSetting skipIfCrouching = bool("noCrouch", "Skip while crouching", true);

    private enum Phase { NONE, BACK, FORWARD }

    private Phase phase = Phase.NONE;
    private final MsTimer phaseTimer = new MsTimer();
    /** Key states captured when the pattern starts, restored when it ends. */
    private boolean savedForward;
    private boolean savedBack;

    public STap() {
        super("STap", ModuleCategory.COMBAT, 0);
        // Subscribe once; subscribing in onEnable stacked a new listener per toggle.
        ZephyrEvents.ATTACK_LANDED.subscribe(this::onAttack);
    }

    @Override
    protected void onDisable() {
        restoreKeys(MinecraftClient.getInstance());
        phase = Phase.NONE;
        phaseTimer.invalidate();
    }

    /** ATTACK_LANDED hook: a real swing just connected - start the backstep phase. */
    private void onAttack(ZephyrEvents.AttackLanded event) {
        if (!isEnabled() || !event.landed) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity self = client.player;
        if (self == null || phase != Phase.NONE) {
            return;
        }
        if (self.isGliding() || self.isTouchingWater() || self.isInLava() || self.isUsingItem()
                || (skipIfCrouching.get() && self.isSneaking())) {
            return;
        }
        if (requireTarget.get()) {
            LivingEntity target = TargetManager.currentTarget();
            if (target == null || !target.isAlive()
                    || self.squaredDistanceTo(target) > maxRange.get() * maxRange.get()) {
                return;
            }
        }
        captureKeys(client);
        phase = Phase.BACK;
        phaseTimer.restart(backMsMin.get(), backMsMax.get());
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        if (self == null) {
            restoreKeys(client);
            phase = Phase.NONE;
            return;
        }
        KeyBinding forward = client.options.forwardKey;
        KeyBinding back = client.options.backKey;

        switch (phase) {
            case BACK -> {
                // Cancel forward, tap backward - the vanilla S-tap pattern.
                forward.setPressed(false);
                back.setPressed(true);
                if (phaseTimer.isDue()) {
                    phase = Phase.FORWARD;
                    phaseTimer.restart(fwdMsMin.get(), fwdMsMax.get());
                }
            }
            case FORWARD -> {
                // Drop backward, push forward into the next swing.
                back.setPressed(false);
                forward.setPressed(true);
                if (phaseTimer.isDue()) {
                    restoreKeys(client);
                    phase = Phase.NONE;
                }
            }
            case NONE -> {
                // idle
            }
        }
    }

    private void captureKeys(MinecraftClient client) {
        savedForward = client.options.forwardKey.isPressed();
        savedBack = client.options.backKey.isPressed();
    }

    /**
     * Hands W/S back to the player's PHYSICAL keys. Restoring the state captured at the
     * start left W stuck down whenever the player let go of it during the tap (no
     * release event arrives for a key we pressed programmatically).
     */
    private void restoreKeys(MinecraftClient client) {
        if (client == null || client.options == null || client.getWindow() == null) {
            return;
        }
        client.options.forwardKey.setPressed(physicallyDown(client, client.options.forwardKey, savedForward));
        client.options.backKey.setPressed(physicallyDown(client, client.options.backKey, savedBack));
    }

    private static boolean physicallyDown(MinecraftClient client, KeyBinding kb, boolean fallback) {
        net.minecraft.client.util.InputUtil.Key key =
                net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.getBoundKeyOf(kb);
        long handle = client.getWindow().getHandle();
        int code = key.getCode();
        if (key.getCategory() == net.minecraft.client.util.InputUtil.Type.KEYSYM && code >= 0) {
            return org.lwjgl.glfw.GLFW.glfwGetKey(handle, code) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        }
        if (key.getCategory() == net.minecraft.client.util.InputUtil.Type.MOUSE && code >= 0) {
            return org.lwjgl.glfw.GLFW.glfwGetMouseButton(handle, code) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        }
        return fallback;
    }

    @Override
    public String hudLabel() {
        return name + " · " + phase.name().toLowerCase();
    }
}
