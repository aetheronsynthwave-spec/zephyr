package dev.zephyr.mixin;

import dev.zephyr.ZephyrClient;
import dev.zephyr.core.event.ZephyrEvents;
import dev.zephyr.modules.movement.NoDelay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LivingEntity movement hooks, guarded to the local player (1.21.11 moved client-player
 * movement handling into the shared LivingEntity path, so tickMovement must be hooked here).
 * Provides: cancellable movement event, NoDelay jump-cooldown reset, ground-landing event.
 */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity {

    @Unique
    private float zephyr$lastFallDistance;

    @Unique
    private boolean zephyr$wasOnGround;

    @Unique
    private boolean zephyr$isLocalPlayer() {
        return (Object) this == MinecraftClient.getInstance().player;
    }

    @Inject(method = "tickMovement", at = @At("HEAD"), cancellable = true)
    private void zephyr$preTickMovement(CallbackInfo ci) {
        if (!zephyr$isLocalPlayer()) {
            return;
        }
        LivingEntity self = (LivingEntity) (Object) this;

        ZephyrEvents.PlayerMovement event = new ZephyrEvents.PlayerMovement((net.minecraft.client.network.ClientPlayerEntity) self);
        ZephyrEvents.PLAYER_MOVEMENT.fire(event);
        if (event.isCancelled()) {
            ci.cancel();
            return;
        }

        zephyr$lastFallDistance = self.fallDistance;
        zephyr$wasOnGround = self.isOnGround();

        NoDelay noDelay = ZephyrClient.modules().get(NoDelay.class);
        if (noDelay != null && noDelay.isEnabled() && noDelay.jumpDelayEnabled()) {
            ((dev.zephyr.mixin.accessor.LivingEntityAccessor) this).setJumpingCooldown(0);
        }
    }

    @Inject(method = "tickMovement", at = @At("RETURN"))
    private void zephyr$postTickMovement(CallbackInfo ci) {
        if (!zephyr$isLocalPlayer()) {
            return;
        }
        LivingEntity self = (LivingEntity) (Object) this;
        if (!zephyr$wasOnGround && self.isOnGround() && zephyr$lastFallDistance > 0.3f) {
            ZephyrEvents.GROUND_LAND.fire(new ZephyrEvents.GroundLand(
                    (net.minecraft.client.network.ClientPlayerEntity) self, zephyr$lastFallDistance));
        }
    }

    @Inject(method = "knockback", at = @At("HEAD"), cancellable = true)
    private void zephyr$noKnockback(CallbackInfo ci) {
        if (!zephyr$isLocalPlayer()) {
            return;
        }
        NoPush noPush = ZephyrClient.modules().get(NoPush.class);
        if (noPush != null && noPush.isEnabled() && noPush.knockbackEnabled()) {
            ci.cancel();
        }
    }
}
