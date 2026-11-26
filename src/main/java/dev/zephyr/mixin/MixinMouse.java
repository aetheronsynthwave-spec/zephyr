package dev.zephyr.mixin;

import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mouse-level look hook (1.21.11).
 *
 * <p>IMPORTANT 1.21.11 FACT (verified against the shipped bytecode): {@code Mouse
 * #onCursorPos} does NOT call {@code Entity#changeLookDirection} anymore - it only
 * accumulates cursorDeltaX/Y. The actual call moved to {@code Mouse#tick()} (with the
 * receiver typed ClientPlayerEntity). The v1.2 @Redirect on onCursorPos therefore
 * never applied and failed silently under defaultRequire=0.
 *
 * <p>Freelook input is intercepted one level deeper, at {@code Entity
 * #changeLookDirection} HEAD (see MixinEntity) - that hook applies to every call site
 * (Mouse.tick now, and any future caller) and needs no per-call-site knowledge. This
 * mixin remains as a lightweight debug/extension point for cursor-level features
 * (e.g. raw delta sampling for future modules) and documents the flow.
 */
@Mixin(Mouse.class)
public abstract class MixinMouse {

    /**
     * Post-tick probe: after vanilla applied the smoothed+inverted deltas to the
     * player's look direction. Kept cheap (two field reads) so it costs nothing when
     * nothing subscribes.
     */
    /** Records mouse-button presses for Zephyr binds (side buttons etc.). */
    @Inject(method = "onMouseButton", at = @At("HEAD"))
    private void zephyr$onMouseButton(long window, net.minecraft.client.input.MouseInput input, int action,
                                      CallbackInfo ci) {
        if (action == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
            dev.zephyr.core.input.BindInput.mousePressed(input.button());
        }
    }

    @Inject(method = "tick", at = @At("RETURN"), require = 0)
    private void zephyr$afterMouseTick(CallbackInfo ci) {
        // Intentionally empty by default - the look-delta interception lives in
        // MixinEntity.changeLookDirection, which is call-site independent.
    }
}
