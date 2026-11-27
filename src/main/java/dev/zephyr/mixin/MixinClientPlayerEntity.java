package dev.zephyr.mixin;

import dev.zephyr.core.rotation.RotationManager;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Silent-rotation ride-along on the vanilla movement-packet writer. Movement event /
 * jump cooldown / landing hooks live in MixinLivingEntity (see note there).
 */
@Mixin(ClientPlayerEntity.class)
public abstract class MixinClientPlayerEntity {

    @Inject(method = "sendMovementPackets", at = @At("HEAD"))
    private void zephyr$preSendMovement(CallbackInfo ci) {
        RotationManager.beforeMovementPackets((ClientPlayerEntity) (Object) this);
    }

    @Inject(method = "sendMovementPackets", at = @At("RETURN"))
    private void zephyr$postSendMovement(CallbackInfo ci) {
        RotationManager.afterMovementPackets((ClientPlayerEntity) (Object) this);
    }
}
