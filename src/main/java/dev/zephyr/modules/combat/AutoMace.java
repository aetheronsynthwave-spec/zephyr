package dev.zephyr.modules.combat;

import dev.zephyr.ZephyrClient;
import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.CombatActions;
import dev.zephyr.core.combat.DamageCalculator;
import dev.zephyr.core.event.ZephyrEvents;
import dev.zephyr.core.rotation.RotationManager;
import dev.zephyr.core.rotation.RotationRequest;
import dev.zephyr.core.settings.ModeSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.target.TargetManager;
import dev.zephyr.core.target.TargetCriteria;
import dev.zephyr.core.util.ItemUtil;
import dev.zephyr.core.util.MathUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;

/**
 * AutoMace - mace slam execution with two timing models:
 *
 * <p><b>STANDARD</b>: fall/velocity/height gate. Attacks when the local player is
 * descending ({@code velocity.y < 0}) with at least {@code minFall} blocks of accumulated
 * fall distance, sufficient height advantage over the target, and a closable gap to the
 * surface so the strike actually lands on the target rather than the floor.
 *
 * <p><b>DENSITY (0-tick)</b>: arms a same-tick trigger that fires the instant the landing
 * event (fired from the movement mixin with the last airborne fall distance) satisfies
 * the gate - the attack goes out in the same tick the server registers the smash landing,
 * which is exactly the timing a Density-mace player performs by hand.
 *
 * <p>Both modes respect target hurt-time (server invulnerability frames), rotation
 * convergence, weapon cooldown, and the BreachSwap hotbar transition.
 */
public final class AutoMace extends Module {

    private final ModeSetting mode = s(new ModeSetting("mode", "Mode",
            java.util.List.of("STANDARD", "DENSITY"), "STANDARD"));

    private final NumberSetting minFall = num("minFall", "Min fall (blocks)", 0.5, 8.0, 0.25, 1.5);
    private final NumberSetting maxTargetDist = num("maxDist", "Max target dist", 1.5, 4.0, 0.1, 3.2);
    private final NumberSetting maxHurtTime = num("hurtTime", "Max target hurt time", 0, 10, 1, 5);
    private final NumberSetting minCooldown = num("cooldown", "Min cooldown progress", 0.5, 1.0, 0.05, 0.9);
    private final NumberSetting rotSpeedMin = num("rotMin", "Rotation min deg/t", 10, 120, 5, 35);
    private final NumberSetting rotSpeedMax = num("rotMax", "Rotation max deg/t", 10, 180, 5, 65);
    private final NumberSetting jitter = num("jitter", "Rotation jitter", 0.0, 2.0, 0.05, 0.4);

    /** Stage shown in the HUD while a Density 0-tick strike is armed. */
    private boolean armedZeroTick;
    private int ticksSinceStrike = Integer.MAX_VALUE;
    private int tickCounter;

    public AutoMace() {
        super("AutoMace", ModuleCategory.COMBAT, 0);
    }

    @Override
    protected void onEnable() {
        ZephyrEvents.GROUND_LAND.subscribe(this::onLand);
    }

    @Override
    protected void onDisable() {
        armedZeroTick = false;
        RotationManager.clear(0);
    }

    private void onLand(ZephyrEvents.GroundLand event) {
        // Density 0-tick: strike in the same tick as the landing.
        if (!isEnabled() || !mode.is("DENSITY")) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity self = client.player;
        if (self == null || event.fallDistance < minFall.get()) {
            return;
        }
        LivingEntity target = TargetManager.currentTarget();
        if (target == null || !gateOk(client, self, target, event.fallDistance)) {
            return;
        }
        strike(client, self, target, event.fallDistance);
    }

    @Override
    public void onTick(MinecraftClient client) {
        tickCounter++;
        if (ticksSinceStrike < Integer.MAX_VALUE) {
            ticksSinceStrike++;
        }
        ClientPlayerEntity self = client.player;
        if (self == null) {
            return;
        }
        TargetCriteria criteria = new TargetCriteria().range(maxTargetDist.get() + 1.5);
        LivingEntity target = TargetManager.acquire(client, criteria);
        TargetManager.cleanup();
        if (target == null) {
            armedZeroTick = false;
            return;
        }

        double fall = self.fallDistance;
        double distToGround = DamageCalculator.distanceToGround(client.world, self);

        if (mode.is("DENSITY")) {
            // Arm while a qualifying slam is incoming (speed + fall + closable ground gap).
            armedZeroTick = self.getVelocity().y < -0.25
                    && fall >= minFall.get() * 0.75
                    && distToGround <= Math.abs(self.getVelocity().y) * 1.5;
            aimAt(client, self, target);
            return; // the strike itself happens in onLand
        }

        // STANDARD gate: descending, fall banked, height advantage, ground gap closable.
        boolean descending = self.getVelocity().y < -0.08;
        boolean heightOk = self.getY() - target.getY() >= 0.75;
        boolean groundGapOk = distToGround <= 2.5;
        if (!descending || fall < minFall.get() || !heightOk || !groundGapOk) {
            return;
        }
        if (!gateOk(client, self, target, fall)) {
            return;
        }
        strike(client, self, target, fall);
    }

    /** Shared pre-strike gates: hurt-time, cooldown, reach, rotation aim. */
    private boolean gateOk(MinecraftClient client, ClientPlayerEntity self, LivingEntity target, double fall) {
        if (target.hurtTime > maxHurtTime.get().intValue()) {
            return false;
        }
        double distSq = self.squaredDistanceTo(target);
        double reach = maxTargetDist.get();
        if (distSq > reach * reach) {
            return false;
        }
        if (ItemUtil.attackCooldownProgress(self) < minCooldown.get()) {
            return false;
        }
        Vec3d aim = MathUtil.aimPoint(target);
        RotationManager.request(RotationRequest.to(
                        MathUtil.yawTo(self.getEyePos(), aim), MathUtil.pitchTo(self.getEyePos(), aim))
                .speed(rotSpeedMin.get(), rotSpeedMax.get())
                .jitter(jitter.get())
                .silent(false)
                .validWhile(() -> target.isAlive())
                .expiresAfter(4)
                .build());
        return RotationManager.isAimedAt(aim, 6.0);
    }

    /** Requests the BreachSwap transition, then attacks through the vanilla path. */
    private void strike(MinecraftClient client, ClientPlayerEntity self, LivingEntity target, double fall) {
        BreachSwap breach = ZephyrClient.modules().get(BreachSwap.class);
        if (breach != null) {
            breach.requestStrike();
        }
        if (!ItemUtil.isMace(self.getMainHandStack())) {
            return; // swap module owns the transition; strike resumes next tick(s)
        }
        if (CombatActions.attackEntity(client, target)) {
            ticksSinceStrike = 0;
            armedZeroTick = false;
        }
    }

    private void aimAt(MinecraftClient client, ClientPlayerEntity self, LivingEntity target) {
        Vec3d aim = MathUtil.aimPoint(target);
        RotationManager.request(RotationRequest.to(
                        MathUtil.yawTo(self.getEyePos(), aim), MathUtil.pitchTo(self.getEyePos(), aim))
                .speed(rotSpeedMin.get(), rotSpeedMax.get())
                .jitter(jitter.get())
                .silent(false)
                .validWhile(() -> target.isAlive())
                .expiresAfter(4)
                .build());
    }

    @Override
    public String hudLabel() {
        StringBuilder label = new StringBuilder(name).append(" · ").append(mode.get());
        if (armedZeroTick) {
            label.append(" · armed");
        }
        return label.toString();
    }
}
