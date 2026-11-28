package dev.zephyr.mixin;

import dev.zephyr.ZephyrClient;
import dev.zephyr.core.input.InputOverride;
import dev.zephyr.modules.combat.STap;
import dev.zephyr.modules.crystal.AutoHitCrystal;
import dev.zephyr.modules.movement.WalkInventory;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Movement-input overrides applied right after vanilla reads the keys:
 * <ul>
 *   <li>STap: forward/back override for the tap ticks.</li>
 *   <li>InputOverride: one-tick jump / forward / sprint requests from the mace bots
 *       (elytra take-off, wind-charge jump, approach). Requests only ever add presses.</li>
 *   <li>AutoHitCrystal: a jump that would climb onto obsidian is dropped.</li>
 *   <li>WalkInventory: movement keys still count while an inventory is open.</li>
 * </ul>
 */
@Mixin(KeyboardInput.class)
public abstract class MixinKeyboardInput extends Input {

    @Inject(method = "tick", at = @At("RETURN"))
    private void zephyr$inputOverrides(CallbackInfo ci) {
        int tap = STap.inputOverride();
        boolean bot = InputOverride.active();
        PlayerInput in = this.playerInput;
        WalkInventory walk = ZephyrClient.modules() == null ? null
                : ZephyrClient.modules().get(WalkInventory.class);
        PlayerInput held = walk == null ? null : walk.input();
        if (held != null) {
            this.playerInput = held;
            float ahead = (held.forward() ? 1f : 0f) - (held.backward() ? 1f : 0f);
            float aside = (held.left() ? 1f : 0f) - (held.right() ? 1f : 0f);
            this.movementVector = new Vec2f(aside, ahead).normalize();
            return;
        }
        AutoHitCrystal crystals = ZephyrClient.modules() == null ? null
                : ZephyrClient.modules().get(AutoHitCrystal.class);
        boolean holdJump = crystals != null && in.jump()
                && crystals.blocksJump(in.forward(), in.backward(), in.left(), in.right());
        if (tap == 0 && !bot && !holdJump) {
            return;
        }
        Vec2f mv = this.movementVector;
        float side = mv == null ? 0f : mv.x;
        float fwd = mv == null ? 0f : mv.y;
        if (tap != 0) {
            boolean back = tap == 1;
            in = new PlayerInput(false, back, in.left(), in.right(), in.jump(), in.sneak(), false);
            fwd = back ? -1f : 0f;
        }
        if (bot) {
            in = InputOverride.apply(in);
            // The movement vector has to follow the added keys, or they do nothing.
            Vec2f keys = new Vec2f((in.left() ? 1f : 0f) - (in.right() ? 1f : 0f),
                    (in.forward() ? 1f : 0f) - (in.backward() ? 1f : 0f)).normalize();
            side = keys.x;
            fwd = keys.y;
        }
        if (holdJump) {
            in = new PlayerInput(in.forward(), in.backward(), in.left(), in.right(), false, in.sneak(), in.sprint());
        }
        this.playerInput = in;
        this.movementVector = new Vec2f(side, fwd);
    }
}
