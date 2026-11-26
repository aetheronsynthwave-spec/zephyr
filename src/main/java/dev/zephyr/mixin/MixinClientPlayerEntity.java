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
        // From here on this tick's action window is closed: any slot packet or attack sent
        // now would land after the movement packet (see TickPhase).
        dev.zephyr.core.timing.TickPhase.movementPacketsStarted();
        RotationManager.beforeMovementPackets((ClientPlayerEntity) (Object) this);
    }

    /**
     * JumpReset: vanilla sets the jump flag from the keyboard here (called from inside
     * LivingEntity#tickMovement), so a forced jump must be applied AFTER this read - the
     * same tick, just before the jump check runs.
     */
    @Inject(method = "tickMovementInput", at = @At("RETURN"))
    private void zephyr$afterMovementInput(CallbackInfo ci) {
        var jr = dev.zephyr.ZephyrClient.modules().get(dev.zephyr.modules.combat.JumpReset.class);
        if (jr != null && jr.consumeJump()) {
            ((ClientPlayerEntity) (Object) this).setJumping(true);
        }
    }

    @Inject(method = "sendMovementPackets", at = @At("RETURN"))
    private void zephyr$postSendMovement(CallbackInfo ci) {
        RotationManager.afterMovementPackets((ClientPlayerEntity) (Object) this);
    }
}
