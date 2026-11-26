package dev.zephyr.mixin;

import dev.zephyr.ZephyrClient;
import dev.zephyr.modules.combat.BreachSwap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** BreachSwap: every melee attack (yours or a module's) can be attribute-swapped to the mace. */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class MixinClientPlayerInteractionManager {

    /** ActionState: every attack path (vanilla click, module, re-entrant swap, spear jab). */
    @Inject(method = "attackEntity", at = @At("HEAD"))
    private void zephyr$noteAttack(PlayerEntity player, Entity target, CallbackInfo ci) {
        dev.zephyr.core.combat.ActionState.noteAttack();
    }

    @Inject(method = "attackWithPiercingWeapon", at = @At("HEAD"))
    private void zephyr$noteJab(CallbackInfo ci) {
        dev.zephyr.core.combat.ActionState.noteAttack();
    }

    @Inject(method = "stopUsingItem", at = @At("HEAD"))
    private void zephyr$noteRelease(PlayerEntity player, CallbackInfo ci) {
        dev.zephyr.core.combat.ActionState.noteRelease();
    }

    @Inject(method = "attackEntity", at = @At("HEAD"), cancellable = true)
    private void zephyr$breachSwap(PlayerEntity player, Entity target, CallbackInfo ci) {
        if (ZephyrClient.modules() == null) {
            return;
        }
        BreachSwap breach = ZephyrClient.modules().get(BreachSwap.class);
        // beforeAttack re-runs this same attack with the mace selected; the original call
        // is then cancelled so the hit is only sent once.
        if (breach != null && breach.beforeAttack(MinecraftClient.getInstance(), target)) {
            ci.cancel();
        }
    }
}
