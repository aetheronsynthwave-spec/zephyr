package dev.zephyr.modules.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared entity selection + interpolation for ESP and Tracers.
 *
 * <p>Performance: the old version walked EVERY loaded entity in the world (items, XP orbs,
 * arrows...) once per frame per module and allocated a list, a colour array and two boxes
 * per entity. Now the selection is rebuilt once per client tick per filter set (the entity
 * set only changes per tick; per-frame motion comes from interpolation). Players-only uses
 * the world's player list, mob filters use a section-backed box query, and drawing reads
 * interpolated coordinates directly - no per-frame allocation.
 */
final class EspTargets {

    private EspTargets() {
    }

    /** One cached selection per distinct filter set (ESP and Tracers usually share one). */
    private static final class Selection {
        int key = Integer.MIN_VALUE;
        int tick = Integer.MIN_VALUE;
        double range;
        final List<LivingEntity> list = new ArrayList<>(32);
    }

    private static final Selection[] CACHE = {new Selection(), new Selection()};
    private static int nextSlot;

    static List<LivingEntity> collect(double range, boolean players, boolean hostiles, boolean others,
                                      boolean teammates) {
        int key = (players ? 1 : 0) | (hostiles ? 2 : 0) | (others ? 4 : 0) | (teammates ? 8 : 0);
        int tick = RenderUtil.currentTick();
        for (Selection s : CACHE) {
            if (s.key == key && s.tick == tick && s.range == range) {
                return s.list;
            }
        }
        Selection s = CACHE[nextSlot];
        nextSlot = (nextSlot + 1) % CACHE.length;
        s.key = key;
        s.tick = tick;
        s.range = range;
        s.list.clear();

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity self = mc.player;
        if (self == null || mc.world == null) {
            return s.list;
        }
        double rangeSq = range * range;
        if (!hostiles && !others) {
            for (PlayerEntity p : mc.world.getPlayers()) {
                if (p != self && p.isAlive() && !p.isSpectator() && self.squaredDistanceTo(p) <= rangeSq
                        && (teammates || !p.isTeammate(self))) {
                    s.list.add(p);
                }
            }
            return s.list;
        }
        for (LivingEntity le : mc.world.getEntitiesByClass(LivingEntity.class,
                self.getBoundingBox().expand(range), e -> true)) {
            if (le == self || !le.isAlive() || le.isSpectator() || self.squaredDistanceTo(le) > rangeSq) {
                continue;
            }
            boolean ok;
            if (le instanceof PlayerEntity p) {
                ok = players && (teammates || !p.isTeammate(self));
            } else if (le instanceof Monster) {
                ok = hostiles;
            } else {
                ok = others && !(le instanceof net.minecraft.entity.decoration.ArmorStandEntity);
            }
            if (ok) {
                s.list.add(le);
            }
        }
        return s.list;
    }

    /** Interpolated offset of {@code e} this frame, written into {@code out} (x, y, z). */
    static void lerpOffset(LivingEntity e, float tickDelta, double[] out) {
        out[0] = MathHelper.lerp(tickDelta, e.lastRenderX, e.getX()) - e.getX();
        out[1] = MathHelper.lerp(tickDelta, e.lastRenderY, e.getY()) - e.getY();
        out[2] = MathHelper.lerp(tickDelta, e.lastRenderZ, e.getZ()) - e.getZ();
    }

    /** Bounding box at the current frame's interpolated position (allocates; for one-offs). */
    static Box box(LivingEntity e, float tickDelta) {
        double x = MathHelper.lerp(tickDelta, e.lastRenderX, e.getX());
        double y = MathHelper.lerp(tickDelta, e.lastRenderY, e.getY());
        double z = MathHelper.lerp(tickDelta, e.lastRenderZ, e.getZ());
        return e.getBoundingBox().offset(x - e.getX(), y - e.getY(), z - e.getZ());
    }

    /** Health colour green to red, or a per-kind colour, written into {@code out} (r, g, b). */
    static void color(LivingEntity e, boolean byHealth, float[] out) {
        if (!byHealth) {
            if (e instanceof PlayerEntity) {
                out[0] = 0.42f; out[1] = 0.85f; out[2] = 1f;
            } else if (e instanceof Monster) {
                out[0] = 1f; out[1] = 0.35f; out[2] = 0.35f;
            } else {
                out[0] = 0.6f; out[1] = 1f; out[2] = 0.6f;
            }
            return;
        }
        float ratio = MathHelper.clamp((e.getHealth() + e.getAbsorptionAmount()) / Math.max(1f, e.getMaxHealth()), 0f, 1f);
        out[0] = Math.min(1f, 2f * (1f - ratio));
        out[1] = Math.min(1f, 2f * ratio);
        out[2] = 0.1f;
    }
}
