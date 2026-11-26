package dev.zephyr.mixin;

import dev.zephyr.core.input.BindInput;
import net.minecraft.client.Keyboard;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records every key PRESS (not repeat/release) for Zephyr binds - see {@link BindInput}. */
@Mixin(Keyboard.class)
public abstract class MixinKeyboard {

    @Inject(method = "onKey", at = @At("HEAD"))
    private void zephyr$onKey(long window, int action, KeyInput input, CallbackInfo ci) {
        if (action == GLFW.GLFW_PRESS) {
            BindInput.keyPressed(input.key());
        }
    }
}
