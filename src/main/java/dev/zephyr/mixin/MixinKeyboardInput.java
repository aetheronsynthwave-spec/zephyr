package dev.zephyr.mixin;

import dev.zephyr.modules.combat.STap;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** STap: overrides the forward/back movement input for the tap ticks, after the keys are read. */
@Mixin(KeyboardInput.class)
public abstract class MixinKeyboardInput extends Input {

    @Inject(method = "tick", at = @At("RETURN"))
    private void zephyr$stap(CallbackInfo ci) {
        int mode = STap.inputOverride();
        if (mode == 0) {
            return;
        }
        PlayerInput in = this.playerInput;
        boolean back = mode == 1;
        this.playerInput = new PlayerInput(false, back, in.left(), in.right(), in.jump(), in.sneak(), false);
        Vec2f mv = this.movementVector;
        this.movementVector = new Vec2f(mv == null ? 0f : mv.x, back ? -1f : 0f);
    }
}
