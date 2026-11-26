package dev.zephyr.modules.movement;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;

/**
 * NoDelay - input/use/jump/attack delay removal. The actual logic lives in the mixins
 * (MixinMinecraftClient, MixinItemCooldownManager, ClientPlayerEntity jump-cooldown
 * accessor hook); this module is the live switch panel and stores which delay classes
 * are currently bypassed.
 */
public final class NoDelay extends Module {

    private final BooleanSetting attackDelay = bool("attack", "No attack delay", true);
    private final BooleanSetting useDelay = bool("use", "No item-use cooldown", true);
    private final BooleanSetting jumpDelay = bool("jump", "No jump cooldown", true);

    public NoDelay() {
        super("NoDelay", ModuleCategory.MOVEMENT, 0);
    }

    public boolean attackDelayEnabled() {
        return attackDelay.get();
    }

    public boolean useDelayEnabled() {
        return useDelay.get();
    }

    public boolean jumpDelayEnabled() {
        return jumpDelay.get();
    }

    /**
     * Ticks to re-arm after an attack passes the gate. 0 = full removal; a small
     * non-zero value keeps the rhythm less robotic while still removing most of the
     * vanilla wait.
     */
    public int postAttackCooldownTicks() {
        return 0;
    }

    @Override
    public String hudLabel() {
        StringBuilder label = new StringBuilder(name).append(" ·");
        if (attackDelay.get()) {
            label.append(" atk");
        }
        if (useDelay.get()) {
            label.append(" use");
        }
        if (jumpDelay.get()) {
            label.append(" jmp");
        }
        return label.toString();
    }
}
