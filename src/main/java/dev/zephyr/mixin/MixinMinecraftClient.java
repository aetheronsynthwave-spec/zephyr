package dev.zephyr.mixin;

import dev.zephyr.ZephyrClient;
import dev.zephyr.core.event.ZephyrEvents;
import dev.zephyr.modules.movement.NoDelay;
import dev.zephyr.mixin.accessor.MinecraftClientAccessor;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NoDelay: removes the vanilla attack-delay gate in {@code doAttack}.
 * The cooldown field is zeroed at HEAD so the vanilla code path (swing, interaction,
 * server validation) stays 100% intact - only the artificial wait disappears.
 *
 * Also fires {@link ZephyrEvents#ATTACK_LANDED} at RETURN so swing-triggered modules
 * (STap combo stepping, JumpReset chaining) react to real manual attacks.
 *
 * <p>Pre-movement stage: Zephyr's module tick runs right before {@code ClientWorld#tickEntities}
 * inside {@code MinecraftClient#tick()} - after vanilla's {@code interactionManager.tick()}
 * slot sync and {@code handleInputEvents()} (so the player's own hotbar keys and clicks are
 * already applied), and before {@code ClientPlayerEntity#tick} sends this tick's input,
 * sprint and movement packets and before {@code ClientTickEndC2SPacket}. Slot updates and
 * attacks made here reach the server in the same order a vanilla click would.
 */
@Mixin(MinecraftClient.class)
public abstract class MixinMinecraftClient {

    /**
     * {@code require = 1}: if this target ever moves, the game must fail loudly at startup
     * instead of silently never ticking any module ({@code defaultRequire} is 0).
     * Only reached when a world is loaded and the game is not paused, which is exactly
     * when vanilla itself ticks entities and sends movement packets.
     */
    @Inject(method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/world/ClientWorld;tickEntities()V"),
            require = 1)
    private void zephyr$preMovementStage(CallbackInfo ci) {
        ZephyrClient.onPreMovementTick((MinecraftClient) (Object) this);
    }

    @Inject(method = "doAttack", at = @At("HEAD"))
    private void zephyr$noAttackDelay(CallbackInfoReturnable<Boolean> cir) {
        NoDelay noDelay = ZephyrClient.modules().get(NoDelay.class);
        if (noDelay != null && noDelay.isEnabled() && noDelay.attackDelayEnabled()) {
            ((MinecraftClientAccessor) this).setAttackCooldown(0);
        }
    }

    @Inject(method = "doAttack", at = @At("RETURN"))
    private void zephyr$attackLanded(CallbackInfoReturnable<Boolean> cir) {
        NoDelay noDelay = ZephyrClient.modules().get(NoDelay.class);
        if (noDelay != null && noDelay.isEnabled() && noDelay.attackDelayEnabled()) {
            ((MinecraftClientAccessor) this).setAttackCooldown(noDelay.postAttackCooldownTicks());
        }
        ZephyrEvents.ATTACK_LANDED.fire(new ZephyrEvents.AttackLanded(
                Boolean.TRUE.equals(cir.getReturnValue())));
    }
}
