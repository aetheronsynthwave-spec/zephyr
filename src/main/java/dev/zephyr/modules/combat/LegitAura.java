package dev.zephyr.modules.combat;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.CombatActions;
import dev.zephyr.core.rotation.RotationManager;
import dev.zephyr.core.rotation.RotationRequest;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.target.TargetManager;
import dev.zephyr.core.target.TargetCriteria;
import dev.zephyr.core.timing.MsTimer;
import dev.zephyr.core.timing.RandomController;
import dev.zephyr.core.util.ItemUtil;
import dev.zephyr.core.util.MathUtil;
import dev.zephyr.core.world.RaycastValidator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;

/**
 * LegitAura - bounded-smoothness melee aura.
 *
 * <p>Discipline pipeline per tick:
 * <ol>
 *   <li>Target acquisition via the scoring pipeline (sticky lock).</li>
 *   <li>Visibility: the target hitbox must be raycast-visible from the eye.</li>
 *   <li>Rotation: bounded smoothing toward the aim point with min/max deg-per-tick caps
 *       and micro-jitter; attack only fires once the server rotation is within tolerance
 *       of the required angles.</li>
 *   <li>Attack pacing: gaussian-randomized CPS interval (never a fixed period).</li>
 *   <li>Human gates: weapon cooldown progress, target hurt-time, and a small random
 *       chance to skip a swing entirely (reaction imperfection).</li>
 * </ol>
 */
public final class LegitAura extends Module {

    private final NumberSetting range = num("range", "Range", 2.5, 4.5, 0.05, 3.0);
    private final NumberSetting cpsMin = num("cpsMin", "CPS min", 4, 20, 0.5, 8);
    private final NumberSetting cpsMax = num("cpsMax", "CPS max", 4, 20, 0.5, 14);
    private final NumberSetting cooldownGate = num("cooldown", "Min cooldown", 0.5, 1.0, 0.05, 0.85);
    private final NumberSetting maxHurtTime = num("hurtTime", "Max target hurt time", 0, 10, 1, 4);
    private final NumberSetting rotMin = num("rotMin", "Rotation min deg/t", 5, 120, 5, 25);
    private final NumberSetting rotMax = num("rotMax", "Rotation max deg/t", 5, 180, 5, 55);
    private final NumberSetting jitter = num("jitter", "Micro-jitter deg", 0.0, 2.0, 0.05, 0.45);
    private final NumberSetting tolerance = num("tolerance", "Aim tolerance deg", 1.0, 25.0, 0.5, 5.0);
    private final BooleanSetting requireVisible = bool("requireVisible", "Raycast hitbox check", true);
    private final BooleanSetting skipChance = bool("skipChance", "Random swing skip", true);
    private final BooleanSetting silentMode = bool("silent", "Silent rotations", false);

    private final MsTimer attackTimer = new MsTimer();
    private double lastCps;
    private int swings;
    private int ticks;

    public LegitAura() {
        super("LegitAura", ModuleCategory.COMBAT, 0);
    }

    @Override
    protected void onEnable() {
        attackTimer.restartCps(cpsMin.get(), 15);
    }

    @Override
    protected void onDisable() {
        RotationManager.clear(0);
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        if (self == null) {
            return;
        }
        ticks++;

        TargetCriteria criteria = new TargetCriteria().range(range.get());
        LivingEntity target = TargetManager.acquire(client, criteria);
        TargetManager.cleanup();
        if (target == null) {
            return;
        }

        // Visibility gate: full 3x3x3 hitbox probe from the eye.
        if (requireVisible.get()
                && !RaycastValidator.hitboxVisible(self.getEyePos(), target.getBoundingBox(), self)) {
            return;
        }

        // Bounded rotation toward the aim point (visible or silent per config).
        Vec3d aim = MathUtil.aimPoint(target);
        double yawReq = MathUtil.yawTo(self.getEyePos(), aim);
        double pitchReq = MathUtil.pitchTo(self.getEyePos(), aim);
        RotationManager.request(RotationRequest.to(yawReq, pitchReq)
                .speed(rotMin.get(), rotMax.get())
                .jitter(jitter.get())
                .silent(silentMode.get())
                .validWhile(target::isAlive)
                .expiresAfter(4)
                .build());

        // Attack path: pacing gate first, then the human gates.
        if (!attackTimer.isDue()) {
            return;
        }
        attackTimer.restartCps(lastCps = RandomController.uniform(cpsMin.get(), cpsMax.get()), 15);

        if (target.hurtTime > maxHurtTime.get().intValue()) {
            return;
        }
        if (ItemUtil.attackCooldownProgress(self) < cooldownGate.get()) {
            return;
        }
        if (!RotationManager.isAimedAt(aim, tolerance.get())) {
            return;
        }
        if (skipChance.get() && RandomController.chance(0.04)) {
            return; // occasional natural hesitation
        }
        if (CombatActions.attackEntity(client, target)) {
            swings++;
        }
    }

    @Override
    public String hudLabel() {
        return String.format("%s · %.1fcps · %d hits", name, lastCps, swings);
    }
}
