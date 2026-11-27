package dev.zephyr.core.util;

import net.minecraft.entity.Entity;
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
}
