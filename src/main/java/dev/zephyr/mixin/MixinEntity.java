package dev.zephyr.mixin;

import dev.zephyr.ZephyrClient;
import dev.zephyr.modules.movement.NoPush;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NoPush: entity push-out and fluid push cancellation for the local player only.
 * (1.21.11 renamed the fluid probe to isPushedByFluids.) Other entities are never
 * touched, so world physics and entity interpolation stay vanilla.
 */
@Mixin(Entity.class)
public abstract class MixinEntity {

    @Inject(method = "pushAwayFrom", at = @At("HEAD"), cancellable = true)
    private void zephyr$noEntityPush(Entity other, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if ((Object) this == client.player) {
            NoPush noPush = ZephyrClient.modules().get(NoPush.class);
            if (noPush != null && noPush.isEnabled() && noPush.entityPushEnabled()) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "isPushedByFluids", at = @At("HEAD"), cancellable = true)
    private void zephyr$noFluidPush(CallbackInfoReturnable<Boolean> cir) {
        MinecraftClient client = MinecraftClient.getInstance();
        if ((Object) this == client.player) {
            NoPush noPush = ZephyrClient.modules().get(NoPush.class);
            if (noPush != null && noPush.isEnabled() && noPush.fluidPushEnabled()) {
                cir.setReturnValue(false);
            }
        }
    }
}
