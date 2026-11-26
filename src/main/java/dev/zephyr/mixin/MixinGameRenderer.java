package dev.zephyr.mixin;

import dev.zephyr.ZephyrClient;
import dev.zephyr.modules.utility.Zoom;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Zoom: scales the world FOV returned by {@code GameRenderer#getFov(Camera, float, boolean)}
 * (1.21.11 signature). {@code changingFov == false} is the hand/item pass, which is left
 * alone so the held item does not balloon while zoomed.
 */
@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void zephyr$zoom(Camera camera, float tickProgress, boolean changingFov,
                             CallbackInfoReturnable<Float> cir) {
        if (!changingFov || ZephyrClient.modules() == null) {
            return;
        }
        Zoom zoom = ZephyrClient.modules().get(Zoom.class);
        if (zoom == null) {
            return;
        }
        float mul = zoom.fovMultiplier();
        if (mul != 1f) {
            cir.setReturnValue(cir.getReturnValueF() * mul);
        }
    }
}
