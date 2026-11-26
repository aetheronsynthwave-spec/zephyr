package dev.zephyr.modules.combat;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.rotation.RotationManager;
import dev.zephyr.core.rotation.RotationRequest;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.target.TargetCriteria;
import dev.zephyr.core.target.TargetManager;
import dev.zephyr.core.util.MathUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;

/**
 * AimAssist - gentle, human-scale tracking that only nudges your REAL view (never
 * silent) toward the nearest enemy inside a field-of-view cone. Idea from Echo's
 * AimAssist: it assists a drag, it does not snap. Defaults are deliberately weak.
 */
public final class AimAssist extends Module {

    private final NumberSetting range = num("range", "Range", 2.5, 6.0, 0.1, 4.2);
    private final NumberSetting fov = num("fov", "FOV cone", 10, 180, 5, 70);
    private final NumberSetting speedMin = num("speedMin", "Min deg/tick", 0.5, 20, 0.5, 2.0);
    private final NumberSetting speedMax = num("speedMax", "Max deg/tick", 1, 40, 0.5, 7.0);
    private final BooleanSetting onlyAttacking = bool("onlyWhileClicking", "Only while attack held", true);
    private final BooleanSetting verticalToo = bool("vertical", "Assist pitch", true);
    private final BooleanSetting weaponOnly = bool("weapon", "Only with weapon", true);

    private LivingEntity lastTarget;

    public AimAssist() {
        super("AimAssist", ModuleCategory.COMBAT, 0);
    }

    @Override
    protected void onDisable() {
        RotationManager.clear(0);
        lastTarget = null;
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        if (self == null || client.currentScreen != null) {
            return;
        }
        if (onlyAttacking.get() && !client.options.attackKey.isPressed()) {
            return;
        }
        if (weaponOnly.get()) {
            String id = dev.zephyr.core.util.ItemUtil.id(self.getMainHandStack());
            if (!(id.endsWith("sword") || id.endsWith("axe") || id.endsWith("mace") || id.endsWith("spear"))) {
                return;
            }
        }
        LivingEntity target = TargetManager.acquire(client, new TargetCriteria().range(range.get()));
        TargetManager.cleanup();
        if (target == null) {
            lastTarget = null;
            return;
        }
        // SMART: once the crosshair is on them, stop pulling (no over-correction / shake).
        if (smart() && client.crosshairTarget instanceof net.minecraft.util.hit.EntityHitResult ehr
                && ehr.getEntity() == target) {
            lastTarget = target;
            return;
        }
        Vec3d aim = MathUtil.aimPoint(target);
        double yawReq = MathUtil.yawTo(self.getEyePos(), aim);
        double pitchReq = verticalToo.get() ? MathUtil.pitchTo(self.getEyePos(), aim) : self.getPitch();
        double dy = Math.abs(MathUtil.angleDelta(self.getYaw(), yawReq));
        double dp = Math.abs(pitchReq - self.getPitch());
        if (Math.hypot(dy, dp) > fov.get() / 2.0) {
            return;
        }
        RotationManager.request(RotationRequest.to(yawReq, pitchReq)
                .speed(speedMin.get(), Math.max(speedMin.get(), speedMax.get()))
                .jitter(0.15)
                .silent(false)
                .priority(-1)
                .validWhile(target::isAlive)
                .expiresAfter(2)
                .build());
        lastTarget = target;
    }

    @Override
    public String hudLabel() {
        return name + (lastTarget != null ? " · locked" : "");
    }
}
