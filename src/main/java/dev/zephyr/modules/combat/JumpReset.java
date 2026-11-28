package dev.zephyr.modules.combat;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.timing.RandomController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * JumpReset - jumps on the tick a melee knockback arrives, so the push is taken in the air
 * and drags you less far.
 *
 * <p>It only answers a real hit: damage and a knockback velocity together, with someone close
 * enough to have thrown it and the push pointing away from them. Fire, poison, fall damage,
 * explosions without an attacker nearby, wind charges and pearls do not make you hop. It stays
 * quiet with a screen open, while sneaking, eating or flying, and while you hold jump yourself.
 */
public final class JumpReset extends Module {

    private final NumberSetting armWindow = num("armWindow", "Landing window ticks", 0, 6, 1, 2);
    private final NumberSetting successChance = num("chance", "Reset success", 0.30, 1.0, 0.05, 1.0);
    private final BooleanSetting resetInAir = bool("inAir", "Jump on landing if hit airborne", true);
    private final BooleanSetting playersOnly = bool("playersOnly", "Only hits from players", false);
    private final BooleanSetting movingOnly = bool("movingOnly", "Only while walking forward", false);
    private final NumberSetting minPush = num("minPush", "Min knockback strength", 0.05, 0.6, 0.01, 0.15);
    private final NumberSetting attackerRange = num("attackerRange", "Attacker within", 3.0, 8.0, 0.1, 5.0);

    /** Knockback velocity from the packet hook, consumed by the next movement tick. */
    private Vec3d knockback;
    private int lastHurtTime;
    private boolean armed;
    private int armedTicksLeft;
    /** Our one-tick jump flag, cleared at the next movement tick. */
    private boolean jumpPulse;

    public JumpReset() {
        super("JumpReset", ModuleCategory.COMBAT, 0);
    }

    @Override
    protected void onDisable() {
        jumpPulse = false;
        armed = false;
        knockback = null;
        lastHurtTime = 0;
    }

    /**
     * Movement-tick HEAD hook, called directly from {@code MixinLivingEntity}: the hit and its
     * knockback are read here and the jump flag set before this tick's movement runs.
     */
    public void zephyr$onMovementHook(ClientPlayerEntity self) {
        jumpPulse = false;
        if (!isEnabled()) {
            armed = false;
            knockback = null;
            lastHurtTime = 0;
            return;
        }
        // hurtTime snaps to 10 on an accepted hit and counts down, so a rise is a new one.
        boolean hurt = self.hurtTime > lastHurtTime && self.hurtTime >= 8;
        lastHurtTime = self.hurtTime;
        Vec3d push = knockback;
        knockback = null;
        if (push != null && (hurt || self.hurtTime >= 9) && fromAttacker(self, push)) {
            armed = true;
            armedTicksLeft = Math.max(1, armWindow.get().intValue());
        }
        if (!armed) {
            return;
        }
        if (self.isOnGround() && canJump(self)) {
            // Applied after vanilla reads the keyboard this tick (see consumeJump).
            jumpPulse = RandomController.chance(successChance.get());
            armed = false;
        } else if (!resetInAir.get() || --armedTicksLeft <= 0) {
            armed = false;
        }
    }

    private boolean canJump(ClientPlayerEntity self) {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.currentScreen == null && !self.isSneaking() && !self.isUsingItem()
                && !self.getAbilities().flying && !self.hasVehicle() && !self.isGliding()
                && !self.isTouchingWater() && !self.isInLava() && !self.isClimbing()
                && !client.options.jumpKey.isPressed()
                && (!movingOnly.get() || client.options.forwardKey.isPressed());
    }

    /** Someone close enough to have hit us, with the push pointing away from them. */
    private boolean fromAttacker(ClientPlayerEntity self, Vec3d push) {
        double flat = Math.hypot(push.x, push.z);
        net.minecraft.client.world.ClientWorld world = MinecraftClient.getInstance().world;
        if (flat < minPush.get() || world == null) {
            return false;
        }
        double range = attackerRange.get();
        for (var entity : world.getOtherEntities(self, self.getBoundingBox().expand(range))) {
            if (!(entity instanceof LivingEntity living) || !living.isAlive()
                    || playersOnly.get() && !(living instanceof PlayerEntity)
                    || self.squaredDistanceTo(living) > range * range) {
                continue;
            }
            double dx = self.getX() - living.getX();
            double dz = self.getZ() - living.getZ();
            double length = Math.hypot(dx, dz);
            if (length < 1.0e-3 || (dx * push.x + dz * push.z) / (length * flat) > 0.5) {
                return true;
            }
        }
        return false;
    }

    /** Called from the velocity-packet hook with the server-sent knockback velocity. */
    public void onKnockback(Vec3d velocity) {
        if (isEnabled()) {
            knockback = velocity;
        }
    }

    /** Called right after vanilla reads movement input; true = force the jump this tick. */
    public boolean consumeJump() {
        boolean jump = jumpPulse && isEnabled();
        jumpPulse = false;
        return jump;
    }

    @Override
    public String hudLabel() {
        return name + (armed ? " · armed " + armedTicksLeft + "t" : "");
    }
}
