package dev.zephyr.core.world;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Entity iteration helper. 1.21.11 removed the no-arg {@code world.getEntities()}
 * listing from the entity view API, so every "scan everything near me" call goes
 * through here and iterates the section-backed query with a local box - which is
 * also cheaper than a full-world walk ever was.
 */
public final class EntityScan {

    private EntityScan() {
    }

    // ------------------------------------------------------------------
    // Shared per-tick living-entity scan cache
    // ------------------------------------------------------------------
    // Several modules (TargetManager.acquire per combat module, DamageNumbers, ...)
    // each ran their own living-entity box query per tick over the same region. The
    // cache below keeps ONE list per tick + range bucket, reused by every caller in
    // that tick; it is invalidated when the query center moves more than 2 blocks or
    // the world changes. The returned list must NOT be mutated by callers.

    private static int cacheTick = -1;
    private static double cacheRange;
    private static ClientWorld cacheWorld;
    private static final net.minecraft.util.math.Vec3d cacheCenter =
            net.minecraft.util.math.Vec3d.ZERO;
    private static Vec3d cacheCenterPos = cacheCenter;
    private static final List<net.minecraft.entity.LivingEntity> LIVING_CACHE =
            new java.util.ArrayList<>(64);

    /** All living entities within {@code range} of {@code center} - shared per tick. */
    public static List<net.minecraft.entity.LivingEntity> livingAroundCached(ClientWorld world,
                                                                             Vec3d center, double range) {
        double bucket = Math.max(1.0, Math.ceil(range));
        if (cacheWorld != world || cacheTick != world.getTime()
                || cacheRange != bucket
                || cacheCenterPos.squaredDistanceTo(center) > 4.0) {
            LIVING_CACHE.clear();
            LIVING_CACHE.addAll(world.getEntitiesByClass(net.minecraft.entity.LivingEntity.class,
                    new Box(center, center).expand(bucket), e -> true));
            cacheTick = (int) world.getTime();
            cacheRange = bucket;
            cacheWorld = world;
            cacheCenterPos = center;
        }
        return LIVING_CACHE;
    }

    /** All entities within {@code range} of {@code center}. */
    public static List<Entity> around(ClientWorld world, Vec3d center, double range) {
        return world.getOtherEntities(null, new Box(center, center).expand(range), e -> true);
    }

    /** All entities within {@code range} of {@code center} matching the filter. */
    public static List<Entity> around(ClientWorld world, Vec3d center, double range,
                                      java.util.function.Predicate<Entity> filter) {
        return world.getOtherEntities(null, new Box(center, center).expand(range), filter);
    }

    /** All living entities within {@code range} of {@code center}. */
    public static List<net.minecraft.entity.LivingEntity> livingAround(ClientWorld world,
                                                                       Vec3d center, double range) {
        return world.getEntitiesByClass(net.minecraft.entity.LivingEntity.class,
                new Box(center, center).expand(range), e -> true);
    }
}
