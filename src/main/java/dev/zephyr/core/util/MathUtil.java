package dev.zephyr.core.util;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Rotation and interpolation math shared by the targeting and rotation pipelines.
 */
public final class MathUtil {

    private MathUtil() {
    }

    /** Vanilla-style yaw needed to face a point. */
    public static double yawTo(Vec3d from, Vec3d to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    /** Vanilla-style pitch needed to face a point (down = +90 in our request space). */
    public static double pitchTo(Vec3d from, Vec3d to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return -Math.toDegrees(Math.atan2(dy, horizontal));
    }

    /** Shortest signed angle difference in degrees (-180..180). */
    public static double angleDelta(double from, double to) {
        return MathHelper.wrapDegrees(to - from);
    }

    public static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(max, v);
    }

    /** Moves {@code current} toward {@code target} by at most {@code maxStep} degrees along the shortest arc. */
    public static double approachAngle(double current, double target, double maxStep) {
        double delta = angleDelta(current, target);
        double step = clamp(delta, -maxStep, maxStep);
        return MathHelper.wrapDegrees(current + step);
    }

    public static Vec3d eyePos(Entity e) {
        return e.getEyePos();
    }

    /** Center of an entity's upper box region - used as aim point for players. */
    public static Vec3d aimPoint(Entity target) {
        return target.getBoundingBox().getCenter().add(0, target.getBoundingBox().getLengthY() * 0.15, 0);
    }

    /**
     * Line-of-sight probe to a whole block cell: true when any sampled point of the
     * cell is visible from {@code eye}. Used for placement legality (a placement is
     * only legal if the server's raycast to that block can clear too).
     */
    public static boolean blockVisible(Vec3d eye, BlockPos pos) {
        double minX = pos.getX(), minY = pos.getY(), minZ = pos.getZ();
        Vec3d[] probes = {
                new Vec3d(minX + 0.5, minY + 0.5, minZ + 0.5),
                new Vec3d(minX + 0.15, minY + 0.15, minZ + 0.15),
                new Vec3d(minX + 0.85, minY + 0.15, minZ + 0.85),
                new Vec3d(minX + 0.15, minY + 0.85, minZ + 0.85),
                new Vec3d(minX + 0.85, minY + 0.85, minZ + 0.15),
                new Vec3d(minX + 0.5, minY + 1.0, minZ + 0.5)
        };
        for (Vec3d p : probes) {
            if (canSeeFrom(eye, p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean canSeeFrom(Vec3d eye, Vec3d point) {
        var world = net.minecraft.client.MinecraftClient.getInstance().world;
        if (world == null) {
            return false;
        }
        var result = world.raycast(new net.minecraft.world.RaycastContext(eye, point,
                net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                net.minecraft.world.RaycastContext.FluidHandling.NONE,
                net.minecraft.client.MinecraftClient.getInstance().player));
        return result.getType() == net.minecraft.util.hit.HitResult.Type.MISS;
    }

    /**
     * Optimal melee aim point: sample a 3x3x3 grid over the target hitbox and return
     * the visible point with the smallest angular error to the CURRENT view. This is
     * what makes a bounded-smoothness aura still land hits at the edge of reach - it
     * aims at the hitbox sliver that is actually visible and closest to the crosshair,
     * exactly where a human tracking with a low-sens drag would click.
     */
    public static Vec3d bestAimPoint(net.minecraft.client.network.ClientPlayerEntity self,
                                     Entity target, double currentYaw, double currentPitch) {
        Box box = target.getBoundingBox();
        Vec3d eye = self.getEyePos();
        Vec3d best = null;
        double bestErr = Double.MAX_VALUE;
        double[] xs = {box.minX + 0.1, box.getCenter().x, box.maxX - 0.1};
        double[] ys = {box.minY + 0.2, box.getCenter().y, box.maxY - 0.2};
        double[] zs = {box.minZ + 0.1, box.getCenter().z, box.maxZ - 0.1};
        for (double x : xs) {
            for (double y : ys) {
                for (double z : zs) {
                    Vec3d p = new Vec3d(x, y, z);
                    if (!canSeeFrom(eye, p)) {
                        continue;
                    }
                    double dy = Math.abs(angleDelta(currentYaw, yawTo(eye, p)));
                    double dp = Math.abs(currentPitch - pitchTo(eye, p));
                    double err = dy + dp * 0.5 + eye.distanceTo(p) * 0.1;
                    if (err < bestErr) {
                        bestErr = err;
                        best = p;
                    }
                }
            }
        }
        return best != null ? best : aimPoint(target);
    }
}
