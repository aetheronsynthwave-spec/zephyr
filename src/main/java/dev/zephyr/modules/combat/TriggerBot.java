package dev.zephyr.modules.combat;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.CombatActions;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.timing.MsTimer;
import dev.zephyr.core.timing.RandomController;
import dev.zephyr.core.util.ItemUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

/**
 * TriggerBot - attacks only when the vanilla crosshair is actually on a valid enemy and
 * the weapon is charged. No rotations, no aiming: it clicks when YOU are aimed. Idea from
 * Echo's TriggerBot; timing is randomised and cooldown-gated so hits always land at full
 * damage (no wasted weak swings).
 */
public final class TriggerBot extends Module {

    private final NumberSetting cooldownGate = num("cooldown", "Min cooldown", 0.5, 1.0, 0.01, 0.92);
    private final NumberSetting reactMin = num("reactMin", "React min ms", 0, 300, 5, 40);
    private final NumberSetting reactMax = num("reactMax", "React max ms", 0, 400, 5, 110);
    private final BooleanSetting playersOnly = bool("playersOnly", "Players only", true);
    private final BooleanSetting weaponOnly = bool("weapon", "Only with weapon", true);

    private final MsTimer timer = new MsTimer();
    private Entity armedOn;
    private int hits;

    public TriggerBot() {
        super("TriggerBot", ModuleCategory.COMBAT, 0);
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        if (self == null || client.currentScreen != null || client.interactionManager == null) {
            return;
        }
        HitResult hr = client.crosshairTarget;
        if (!(hr instanceof EntityHitResult ehr) || !(ehr.getEntity() instanceof LivingEntity target)
                || !target.isAlive() || target == self) {
            armedOn = null;
            return;
        }
        if (playersOnly.get() && !(target instanceof PlayerEntity)) {
            return;
        }
        if (self.isTeammate(target) || self.isUsingItem()) {
            return;
        }
        if (weaponOnly.get()) {
            String id = ItemUtil.id(self.getMainHandStack());
            if (!(id.endsWith("sword") || id.endsWith("axe") || id.endsWith("mace") || id.endsWith("spear"))) {
                return;
            }
        }
        // Leave the hit to AutoMace (slam coming) or ShieldBreaker (raised shield).
        if (dev.zephyr.core.combat.CombatCoordinator.meleeShouldYield(client, target)) {
            return;
        }
        if (armedOn != target) { // fresh reaction window per new crosshair target
            armedOn = target;
            timer.restart(reactMin.get(), Math.max(reactMin.get(), reactMax.get()));
        }
        if (!timer.isDue() || ItemUtil.attackCooldownProgress(self) < cooldownGate.get()) {
            return;
        }
        // SMART: never waste a charged hit into the target's invulnerability frames.
        if (smart() && target.hurtTime > 1) {
            return;
        }
        if (CombatActions.attackEntity(client, target)) {
            hits++;
            timer.restart(reactMin.get() * 0.5, Math.max(reactMin.get(), reactMax.get()) * 0.6);
        }
    }

    @Override
    public String hudLabel() {
        return name + " · " + hits;
    }
}
