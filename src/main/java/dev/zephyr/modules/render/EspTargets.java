package dev.zephyr.modules.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

/** Shared entity selection + interpolation for ESP and Tracers. */
final class EspTargets {

    private EspTargets() {
    }

    static List<LivingEntity> collect(double range, boolean players, boolean hostiles, boolean others,
                                      boolean teammates) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity self = mc.player;
        List<LivingEntity> out = new ArrayList<>();
        if (self == null || mc.world == null) {
            return out;
        }
        double rangeSq = range * range;
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity le) || e == self || !le.isAlive() || e.isSpectator()) {
                continue;
            }
            if (self.squaredDistanceTo(e) > rangeSq) {
                continue;
            }
            boolean ok;
            if (e instanceof PlayerEntity p) {
                ok = players && (teammates || !p.isTeammate(self));
            } else if (e instanceof Monster) {
                ok = hostiles;
            } else {
                ok = others && !(e instanceof net.minecraft.entity.decoration.ArmorStandEntity);
            }
            if (ok) {
                out.add(le);
            }
        }
        return out;
    }

    /** Bounding box at the current frame's interpolated position (no jitter). */
    static Box box(LivingEntity e, float tickDelta) {
        double x = MathHelper.lerp(tickDelta, e.lastRenderX, e.getX());
        double y = MathHelper.lerp(tickDelta, e.lastRenderY, e.getY());
        double z = MathHelper.lerp(tickDelta, e.lastRenderZ, e.getZ());
        return e.getBoundingBox().offset(x - e.getX(), y - e.getY(), z - e.getZ());
    }

    /** Health colour green to red, or a per-kind colour. */
    static float[] color(LivingEntity e, boolean byHealth) {
        if (!byHealth) {
            return e instanceof PlayerEntity ? new float[]{0.42f, 0.85f, 1f}
                    : e instanceof Monster ? new float[]{1f, 0.35f, 0.35f} : new float[]{0.6f, 1f, 0.6f};
        }
        float ratio = MathHelper.clamp((e.getHealth() + e.getAbsorptionAmount()) / Math.max(1f, e.getMaxHealth()), 0f, 1f);
        return new float[]{Math.min(1f, 2f * (1f - ratio)), Math.min(1f, 2f * ratio), 0.1f};
    }
}
