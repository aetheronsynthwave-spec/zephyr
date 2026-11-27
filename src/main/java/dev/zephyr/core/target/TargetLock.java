package dev.zephyr.core.target;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;

/**
 * A module-private target lock.
 *
 * <p>{@link TargetManager} keeps ONE global target that every module re-acquires each tick
 * with its own criteria. Long-running sequences (an elytra dive takes seconds) must not use
 * it: TargetHUD (16-block range) and the mace bot (64-block range) kept overwriting each
 * other, so the bot's target flipped between two players and every dive was cancelled.
 * A lock picks a target once and keeps it until it becomes invalid; the global target is
 * then pointed at it so TargetHUD/ESP show the same player.
 */
public final class TargetLock {

    /** Non-player entities this owner may also pick (null = players only). */
    public java.util.function.Predicate<LivingEntity> extra;

    private LivingEntity locked;
    private boolean inRange;

    public LivingEntity get() {
        return locked;
    }

    /**
     * Whether the lock was inside the configured range (no hysteresis) at the last
     * {@link #update}. A lock kept only by the hysteresis band may be chased, not acted on.
     */
    public boolean inRange() {
        return inRange;
    }

    public void clear() {
        locked = null;
        inRange = false;
    }

    /**
     * Keeps the current lock while it is valid (with 25% range hysteresis), otherwise picks
     * the nearest valid player. With {@code groundedOnly}, NEW picks skip players that are
     * gliding; an existing lock is kept even if the target takes off (the caller decides
     * whether to wait).
     */
    public LivingEntity update(MinecraftClient client, double range, boolean groundedOnly) {
        ClientPlayerEntity self = client.player;
        if (self == null || client.world == null) {
            clear();
            return null;
        }
        // The id check drops an entity that was replaced or belongs to a previous world.
        if (locked != null && (client.world.getEntityById(locked.getId()) != locked
                || !valid(self, locked, range * 1.25))) {
            locked = null;
        }
        if (locked == null) {
            double best = Double.MAX_VALUE;
            for (PlayerEntity p : client.world.getPlayers()) {
                if (!valid(self, p, range) || (groundedOnly && p.isGliding())) {
                    continue;
                }
                double d = self.squaredDistanceTo(p);
                if (d < best) {
                    best = d;
                    locked = p;
                }
            }
        }
        if (locked == null && extra != null) {
            double near = Double.MAX_VALUE;
            for (net.minecraft.entity.Entity e : client.world.getEntities()) {
                if (e instanceof LivingEntity l && extra.test(l) && valid(self, l, range)
                        && self.squaredDistanceTo(l) < near) {
                    near = self.squaredDistanceTo(l);
                    locked = l;
                }
            }
        }
        inRange = locked != null && self.squaredDistanceTo(locked) <= range * range;
        TargetManager.point(locked);
        return locked;
    }

    private static boolean valid(ClientPlayerEntity self, LivingEntity e, double range) {
        if (e == self || !e.isAlive() || e.isRemoved() || e.isSpectator() || self.isTeammate(e)) {
            return false;
        }
        if (e instanceof PlayerEntity p && p.isCreative()) {
            return false;
        }
        // Also false for a NaN or infinite position.
        double distSq = self.squaredDistanceTo(e);
        return Double.isFinite(distSq) && distSq <= range * range;
    }
}
