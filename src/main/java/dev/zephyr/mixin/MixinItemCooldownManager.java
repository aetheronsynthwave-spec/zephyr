package dev.zephyr.mixin;

import dev.zephyr.ZephyrClient;
import dev.zephyr.modules.movement.NoDelay;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Removes the item-use (cooldown) delay: pearls, crystals, XP bottles, anchors... */
@Mixin(net.minecraft.entity.player.ItemCooldownManager.class)
public abstract class MixinItemCooldownManager {

    @Inject(method = "set*", at = @At("HEAD"), cancellable = true)
    private void zephyr$noUseDelay(CallbackInfo ci) {
        NoDelay noDelay = ZephyrClient.modules().get(NoDelay.class);
        if (noDelay != null && noDelay.isEnabled() && noDelay.useDelayEnabled()) {
            ci.cancel();
        }
    }
}
