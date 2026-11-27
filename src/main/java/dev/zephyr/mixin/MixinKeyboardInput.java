package dev.zephyr.mixin;

import dev.zephyr.core.input.InputOverride;
import dev.zephyr.modules.combat.STap;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Movement-input overrides applied right after vanilla reads the keys:
 * <ul>
 *   <li>STap: forward/back override for the tap ticks.</li>
 *   <li>InputOverride: one-tick jump / forward / sprint requests from the mace bots
 *       (elytra take-off, wind-charge jump, approach). Requests only ever add presses.</li>
 * </ul>
 */
@Mixin(KeyboardInput.class)
public abstract class MixinKeyboardInput extends Input {

    @Inject(method = "tick", at = @At("RETURN"))
    private void zephyr$inputOverrides(CallbackInfo ci) {
        int tap = STap.inputOverride();
        boolean bot = InputOverride.active();
        if (tap == 0 && !bot) {
            return;
        }
        PlayerInput in = this.playerInput;
        Vec2f mv = this.movementVector;
        float side = mv == null ? 0f : mv.x;
        float fwd = mv == null ? 0f : mv.y;
        if (tap != 0) {
            boolean back = tap == 1;
            in = new PlayerInput(false, back, in.left(), in.right(), in.jump(), in.sneak(), false);
            fwd = back ? -1f : 0f;
        }
        if (bot) {
            in = InputOverride.apply(in);
            if (InputOverride.forceForward()) {
                fwd = 1f;
            }
        }
        this.playerInput = in;
        this.movementVector = new Vec2f(side, fwd);
    }
}
