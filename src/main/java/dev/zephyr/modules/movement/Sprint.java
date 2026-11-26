package dev.zephyr.modules.movement;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * Sprint - holds sprint while moving forward (the vanilla toggle-sprint behaviour, but
 * respecting hunger, sneaking, item use and blindness). From Echo's Sprint.
 */
public final class Sprint extends Module {

    private final BooleanSetting omni = bool("omni", "Allow sideways/back", false);

    public Sprint() {
        super("Sprint", ModuleCategory.MOVEMENT, 0);
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.currentScreen != null) {
            return;
        }
        boolean moving = omni.get() ? p.input.getMovementInput().lengthSquared() > 0 : p.input.hasForwardMovement();
        if (moving && !p.isSneaking() && !p.isUsingItem() && !p.horizontalCollision
                && p.getHungerManager().getFoodLevel() > 6 && !p.hasStatusEffect(
                net.minecraft.entity.effect.StatusEffects.BLINDNESS)) {
            p.setSprinting(true);
        }
    }
}
