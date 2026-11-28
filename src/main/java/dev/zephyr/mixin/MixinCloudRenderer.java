package dev.zephyr.mixin;

import dev.zephyr.ZephyrClient;
import dev.zephyr.modules.render.CustomSky;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.render.CloudRenderer;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** CustomSky: vanilla clouds would sit as grey blocks on top of the painted sky. */
@Mixin(CloudRenderer.class)
public abstract class MixinCloudRenderer {

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true, require = 0)
    private void zephyr$clouds(int color, CloudRenderMode mode, float height, Vec3d camera, long ticks, float delta,
                               CallbackInfo ci) {
        CustomSky sky = ZephyrClient.modules() == null ? null : ZephyrClient.modules().get(CustomSky.class);
        if (sky != null && sky.isEnabled() && sky.hidesClouds()) {
            ci.cancel();
        }
    }
}
