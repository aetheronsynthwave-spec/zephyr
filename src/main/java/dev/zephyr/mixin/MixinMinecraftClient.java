package dev.zephyr.mixin;

import dev.zephyr.ZephyrClient;
import dev.zephyr.core.event.ZephyrEvents;
import dev.zephyr.modules.movement.NoDelay;
import dev.zephyr.mixin.accessor.MinecraftClientAccessor;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NoDelay: removes the vanilla attack-delay gate in {@code doAttack}.
 * The cooldown field is zeroed at HEAD so the vanilla code path (swing, interaction,
 * server validation) stays 100% intact - only the artificial wait disappears.
 *
 * Also fires {@link ZephyrEvents#ATTACK_LANDED} at RETURN so swing-triggered modules
 * (STap combo stepping, JumpReset chaining) react to real manual attacks.
 */
@Mixin(MinecraftClient.class)
public abstract class MixinMinecraftClient {

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
