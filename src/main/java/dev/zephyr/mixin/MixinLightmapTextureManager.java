package dev.zephyr.mixin;

import dev.zephyr.ZephyrClient;
import dev.zephyr.modules.render.Fullbright;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;

@Mixin(LightmapTextureManager.class)
public abstract class MixinLightmapTextureManager {

    /** First SimpleOption#getValue after GameOptions#getGamma is the gamma read. */
    @Redirect(method = "update", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/option/SimpleOption;getValue()Ljava/lang/Object;", ordinal = 0),
            slice = @Slice(from = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/option/GameOptions;getGamma()Lnet/minecraft/client/option/SimpleOption;")),
            require = 0)
    private Object zephyr$fullbright(SimpleOption<?> option) {
        Fullbright fb = ZephyrClient.modules() == null ? null : ZephyrClient.modules().get(Fullbright.class);
        if (fb != null && fb.isEnabled()) {
            Fullbright.applied++;
            return Fullbright.GAMMA;
        }
        return option.getValue();
    }
}
