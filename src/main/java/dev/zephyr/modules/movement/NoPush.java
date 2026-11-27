package dev.zephyr.modules.movement;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.event.ZephyrEvents;
import dev.zephyr.core.settings.BooleanSetting;

/**
 * NoPush - entity / knockback / fluid push cancellation for the local player only.
 * Backing hooks live in MixinEntity (pushAwayFrom + isPushedByFluid) and
 * MixinLivingEntity (knockback). Interpolation and world physics of all other entities
 * remain fully vanilla, so nothing else in the scene reacts to this module.
 */
public final class NoPush extends Module {

    private final BooleanSetting entityPush = bool("entities", "Block entity push", true);
    private final BooleanSetting knockback = bool("knockback", "Block knockback", true);
    private final BooleanSetting fluidPush = bool("fluids", "Block fluid push", false);

    public NoPush() {
        super("NoPush", ModuleCategory.MOVEMENT, 0);
    }

    public boolean entityPushEnabled() {
        return entityPush.get();
    }

    public boolean knockbackEnabled() {
        return knockback.get();
    }

    public boolean fluidPushEnabled() {
        return fluidPush.get();
    }

    @Override
    public String hudLabel() {
        StringBuilder label = new StringBuilder(name).append(" ·");
        if (entityPush.get()) {
            label.append(" ent");
        }
        if (knockback.get()) {
            label.append(" kb");
        }
        if (fluidPush.get()) {
            label.append(" fluid");
        }
        return label.toString();
    }
}
