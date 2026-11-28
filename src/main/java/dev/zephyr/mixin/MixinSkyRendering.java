package dev.zephyr.mixin;

import dev.zephyr.ZephyrClient;
import dev.zephyr.modules.render.CustomSky;
import net.minecraft.client.render.SkyRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.world.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * CustomSky: its sky is drawn where the vanilla sky disc would be, and the sunrise glow, sun,
 * moon, stars and the dark lower disc that follow are skipped.
 */
@Mixin(SkyRendering.class)
public abstract class MixinSkyRendering {

    private static CustomSky zephyr$sky() {
        CustomSky sky = ZephyrClient.modules() == null ? null : ZephyrClient.modules().get(CustomSky.class);
        return sky != null && sky.isEnabled() ? sky : null;
    }

    @Inject(method = "renderTopSky", at = @At("HEAD"), cancellable = true, require = 0)
    private void zephyr$top(int color, CallbackInfo ci) {
        CustomSky sky = zephyr$sky();
        if (sky != null) {
            sky.draw();
            ci.cancel();
        }
    }

    @Inject(method = "renderGlowingSky", at = @At("HEAD"), cancellable = true, require = 0)
    private void zephyr$glow(MatrixStack matrices, float angle, int color, CallbackInfo ci) {
        if (zephyr$sky() != null) {
            ci.cancel();
        }
    }

    @Inject(method = "renderCelestialBodies", at = @At("HEAD"), cancellable = true, require = 0)
    private void zephyr$bodies(MatrixStack matrices, float sun, float moon, float star, MoonPhase phase,
                               float rain, float brightness, CallbackInfo ci) {
        if (zephyr$sky() != null) {
            ci.cancel();
        }
    }

    @Inject(method = "renderSkyDark", at = @At("HEAD"), cancellable = true, require = 0)
    private void zephyr$dark(CallbackInfo ci) {
        if (zephyr$sky() != null) {
            ci.cancel();
        }
    }
}
