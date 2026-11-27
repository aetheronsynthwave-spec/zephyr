package dev.zephyr.mixin;

import dev.zephyr.ZephyrClient;
import dev.zephyr.core.event.ZephyrEvents;
import dev.zephyr.modules.movement.Freelook;
import net.minecraft.client.Mouse;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Freelook input interception: while active, cursor deltas are fed into the detached
 * free-rotation instead of the player's look direction, so the local view never moves
 * and the player's real rotation (and thus movement basis) is untouched.
 */
@Mixin(Mouse.class)
public abstract class MixinMouse {

    @Redirect(method = "onCursorPos",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;changeLookDirection(DD)V"))
    private void zephyr$interceptLook(Entity instance, double cursorDeltaX, double cursorDeltaY) {
        Freelook freelook = ZephyrClient.modules().get(Freelook.class);
        if (freelook != null && freelook.isEnabled()) {
            freelook.feed(cursorDeltaX, cursorDeltaY);
            ZephyrEvents.LOOK_DELTA.fire(new ZephyrEvents.LookDelta(cursorDeltaX, cursorDeltaY));
            return;
        }
        instance.changeLookDirection(cursorDeltaX, cursorDeltaY);
    }
}
