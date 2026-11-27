package dev.zephyr.core.target;

import dev.zephyr.core.util.MathUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Target scoring/selection pipeline.
 *
 * <p>Each tick, candidates are gathered, filtered (alive, not self, not creative/spectator,
 * not teammates), scored on normalized features (distance, low health, crosshair alignment,
 * threat memory) and the best candidate becomes the sticky target with hysteresis so the
 * lock does not flicker between two near-equal enemies. Also tracks entity health history
 * for the damage-number renderer and keeps an "attacked me recently" threat memory.
 */
public final class TargetManager {

    private TargetManager() {
    }

    private static LivingEntity target;
    private static int heldTicks;
    private static final Map<UUID, Long> threatMemory = new HashMap<>();
    /** entityId -> last observed health (for DamageNumbers). */
    private static final Map<Integer, Float> lastHealth = new HashMap<>();
    /** entityId -> tick at which the entity was last seen (for stale cleanup). */
    private static final Map<Integer, Integer> seenTick = new HashMap<>();

    public static LivingEntity acquire(MinecraftClient client, TargetCriteria criteria) {
        ClientPlayerEntity self = client.player;
        ClientWorld world = client.world;
        if (self == null || world == null) {
            release();
            return null;
        }
        long now = System.currentTimeMillis();
        threatMemory.values().removeIf(t -> now - t > criteria.threatMemoryTicks * 50L);

        LivingEntity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        // Shared per-tick scan cache: multiple combat modules acquire a target in the
        // same tick - one box query serves them all instead of one per module.
        var candidates = dev.zephyr.core.world.EntityScan.livingAroundCached(
                world, self.getLerpedPos(1.0F), criteria.range + 1.0);
        for (Entity e : candidates) {
            if (!(e instanceof LivingEntity living)) {
                continue;
            }
            if (!isValid(self, living, criteria)) {
                continue;
            }
            double score = score(self, living, criteria);
            if (score > bestScore) {
                bestScore = score;
                best = living;
            }
        }

        // Sticky hysteresis: incumbent stays unless the challenger clearly outperforms it.
        if (target != null) {
            if (!isValid(self, target, criteria) || !target.isAlive()) {
                release();
            } else {
                double incumbentScore = score(self, target, criteria);
                if (best != null && best != target && bestScore > incumbentScore * criteria.stickiness) {
                    target = best;
                    heldTicks = 0;
                } else if (target.isAlive()) {
                    heldTicks++;
                }
            }
        }
        if (target == null && best != null) {
            target = best;
            heldTicks = 0;
        }
        return target;
    }

    private static boolean isValid(ClientPlayerEntity self, LivingEntity e, TargetCriteria criteria) {
        if (e == self || !e.isAlive() || e.isSpectator()) {
            return false;
        }
        if (e instanceof PlayerEntity p && p.isCreative()) {
            return false;
        }
        double distSq = self.squaredDistanceTo(e);
        if (distSq > criteria.range * criteria.range) {
            return false;
        }
        if (criteria.playersOnly && !(e instanceof PlayerEntity)) {
            return false;
        }
        // Team check via the vanilla teammate probe (1.21.11 dropped AbstractTeam.isFriendlyTo).
        if (self.isTeammate(e)) {
            return false;
        }
        return true;
    }

    private static double score(ClientPlayerEntity self, LivingEntity e, TargetCriteria criteria) {
        double dist = Math.sqrt(self.squaredDistanceTo(e));
        double range = criteria.range;
        double distanceScore = 1.0 - MathHelper.clamp(dist / range, 0, 1);

        double maxHealth = Math.max(1.0, e.getMaxHealth());
        double healthScore = 1.0 - MathHelper.clamp(e.getHealth() / maxHealth, 0, 1);

        Vec3d eye = self.getEyePos();
        double yawReq = MathUtil.yawTo(eye, e.getEyePos());
        double pitchReq = MathUtil.pitchTo(eye, e.getEyePos());
        double yawDelta = Math.abs(MathUtil.angleDelta(self.getYaw(), yawReq));
        double pitchDelta = Math.abs(self.getPitch() - pitchReq);
        double crosshairScore = 1.0 - MathHelper.clamp((yawDelta + pitchDelta * 0.5) / 180.0, 0, 1);

        double threatScore = threatMemory.containsKey(e.getUuid()) ? 1.0 : 0.0;

        return criteria.weightDistance * distanceScore
                + criteria.weightLowHealth * healthScore
                + criteria.weightCrosshair * crosshairScore
                + criteria.weightThreat * threatScore;
    }

    public static void noteThreat(LivingEntity attacker) {
        if (attacker != null) {
            threatMemory.put(attacker.getUuid(), System.currentTimeMillis());
        }
    }

    public static LivingEntity currentTarget() {
        return target;
    }

    public static int heldTicks() {
        return heldTicks;
    }

    public static void release() {
        target = null;
        heldTicks = 0;
    }

    // ------------------------------------------------------------------
    // Health history (consumed by DamageNumbers / TargetHud)
    // ------------------------------------------------------------------

    /** @return observed health delta this tick, or 0 when unchanged/new. */
    public static float trackHealth(LivingEntity e) {
        int id = e.getId();
        float prev = lastHealth.getOrDefault(id, e.getHealth());
        lastHealth.put(id, e.getHealth());
        seenTick.put(id, currentTick());
        return e.getHealth() - prev;
    }

    public static float lastHealthOf(LivingEntity e) {
        return lastHealth.getOrDefault(e.getId(), e.getHealth());
    }

    /** Drops entries for entities not seen for 100+ ticks. Runs at most once a second. */
    public static void cleanup() {
        if (currentTick() % 20 != 0) {
            return;
        }
        int now = currentTick();
        Iterator<Map.Entry<Integer, Integer>> it = seenTick.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Integer> entry = it.next();
            if (now - entry.getValue() > 100) {
                lastHealth.remove(entry.getKey());
                it.remove();
            }
        }
    }

    private static int currentTick() {
        ClientWorld w = net.minecraft.client.MinecraftClient.getInstance().world;
        return w != null ? (int) (w.getTime() % Integer.MAX_VALUE) : 0;
    }
}
