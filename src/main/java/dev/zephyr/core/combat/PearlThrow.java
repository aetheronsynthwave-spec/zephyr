package dev.zephyr.core.combat;

import net.minecraft.util.math.Vec3d;

import java.util.function.BiPredicate;

/**
 * The flat, low throw that puts an ender pearl on a spot on the ground. Free of game state.
 *
 * <p>Per tick a pearl does {@code pos += v; v *= 0.99; v.y -= 0.03}, leaving at 1.5 plus the
 * thrower's own movement. Only throws at or near level or downwards are considered: a lob
 * takes far longer to land and leaves the thrower exposed.
 */
public final class PearlThrow {

    private static final double SPEED = 1.5;
    /** The furthest above level a throw may point, in degrees. */
    private static final double MAX_UP = 5.0;

    /**
     * @param yaw   degrees, as the game measures it
     * @param pitch degrees, positive is downwards
     * @param ticks flight time until it reaches the spot's height
     */
    public record Throw(float yaw, float pitch, int ticks) {
    }

    private PearlThrow() {
    }

    /**
     * @param eye      the thrower's eyes
     * @param carried  the thrower's movement, which the pearl keeps
     * @param spot     where it should come down
     * @param within   how far off, in blocks, is still acceptable
     * @param clear    whether the straight piece of flight between two points hits nothing
     * @return the throw, or null when no flat, unobstructed throw gets there
     */
    public static Throw solve(Vec3d eye, Vec3d carried, Vec3d spot, double within, BiPredicate<Vec3d, Vec3d> clear) {
        Vec3d from = eye.add(0.0, -0.1, 0.0);
        double dx = spot.x - from.x;
        double dz = spot.z - from.z;
        double wanted = Math.hypot(dx, dz);
        if (wanted < 0.5) {
            return null;
        }
        double yaw = Math.atan2(-dx, dz);
        Throw best = null;
        double bestError = within;
        for (double pitch = -MAX_UP; pitch <= 70.0; pitch += 0.5) {
            double down = Math.toRadians(pitch);
            double vx = -Math.sin(yaw) * Math.cos(down) * SPEED + carried.x;
            double vy = -Math.sin(down) * SPEED + carried.y;
            double vz = Math.cos(yaw) * Math.cos(down) * SPEED + carried.z;
            double x = from.x;
            double y = from.y;
            double z = from.z;
            for (int tick = 1; tick <= 60; tick++) {
                double nx = x + vx;
                double ny = y + vy;
                double nz = z + vz;
                if (ny > spot.y && !clear.test(new Vec3d(x, y, z), new Vec3d(nx, ny, nz))) {
                    break; // this pitch flies into something on the way
                }
                if (ny <= spot.y) {
                    // The point of the last step where it passes the spot's height.
                    double part = y == ny ? 0.0 : (y - spot.y) / (y - ny);
                    double hitX = x + vx * part;
                    double hitZ = z + vz * part;
                    double error = Math.hypot(hitX - spot.x, hitZ - spot.z);
                    if (error < bestError) {
                        bestError = error;
                        best = new Throw((float) Math.toDegrees(yaw), (float) pitch, tick);
                    }
                    break;
                }
                x = nx;
                y = ny;
                z = nz;
                vx *= 0.99;
                vy = vy * 0.99 - 0.03;
                vz *= 0.99;
            }
        }
        return best;
    }
}
