package dev.zephyr.mixin;

import dev.zephyr.ZephyrClient;
import dev.zephyr.core.combat.ActionState;
import dev.zephyr.modules.combat.BreachSwap;
import dev.zephyr.modules.utility.AuctionFlipper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** ActionState bookkeeping, plus BreachSwap's attribute swap on every melee attack. */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class MixinClientPlayerInteractionManager {

    /** ActionState: every attack path (vanilla click, module, re-entrant swap, spear jab). */
    @Inject(method = "attackEntity", at = @At("HEAD"))
    private void zephyr$noteAttack(PlayerEntity player, Entity target, CallbackInfo ci) {
        ActionState.noteAttack();
    }

    /**
     * ActionState: every item use (vanilla right-click or module). 1.21.11 sends
     * {@code PlayerInteractItemC2SPacket} inside this call before the client prediction
     * runs, so noting at HEAD is exactly "a use-item packet is going out this tick".
     * {@code interactItem} returns {@link ActionResult}, so the callback must be returnable.
     */
    @Inject(method = "interactItem", at = @At("HEAD"))
    private void zephyr$noteItemUse(PlayerEntity player, Hand hand,
                                    CallbackInfoReturnable<ActionResult> cir) {
        ActionState.noteItemUse();
    }

    @Inject(method = "attackWithPiercingWeapon", at = @At("HEAD"))
    private void zephyr$noteJab(CallbackInfo ci) {
        ActionState.noteAttack();
    }

    @Inject(method = "stopUsingItem", at = @At("HEAD"))
    private void zephyr$noteRelease(PlayerEntity player, CallbackInfo ci) {
        ActionState.noteRelease();
    }

    /** AuctionFlipper: learns button positions from your own clicks. */
    @Inject(method = "clickSlot", at = @At("HEAD"))
    private void zephyr$learnClick(int syncId, int slotId, int button, SlotActionType actionType,
                                   PlayerEntity player, CallbackInfo ci) {
        if (ZephyrClient.modules() == null) {
            return;
        }
        AuctionFlipper flipper = ZephyrClient.modules().get(AuctionFlipper.class);
        if (flipper != null) {
            flipper.onSlotClick(syncId, slotId);
        }
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
