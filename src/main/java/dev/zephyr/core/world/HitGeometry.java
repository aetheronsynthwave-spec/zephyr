package dev.zephyr.core.world;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Pure spatial checks for a melee hit: finite input, eye-to-hitbox distance and whether a
 * look ray crosses the hitbox. No world access and no side effects. World space, y up,
 * angles in degrees with vanilla's convention (yaw 0 = +z, positive pitch = down).
 */
public final class HitGeometry {

    private HitGeometry() {
    }

    public static boolean finite(Vec3d v) {
        return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    public static boolean finite(Box b) {
        return b != null && Double.isFinite(b.minX) && Double.isFinite(b.minY) && Double.isFinite(b.minZ)
                && Double.isFinite(b.maxX) && Double.isFinite(b.maxY) && Double.isFinite(b.maxZ);
    }

    /** The point of {@code box} nearest to {@code p} ({@code p} itself when inside). */
    public static Vec3d closestPoint(Box box, Vec3d p) {
        return new Vec3d(Math.max(box.minX, Math.min(p.x, box.maxX)),
                Math.max(box.minY, Math.min(p.y, box.maxY)),
                Math.max(box.minZ, Math.min(p.z, box.maxZ)));
    }

    public static boolean withinReach(Vec3d eye, Vec3d point, double reach) {
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        return reach >= 0.0 && dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    public static boolean withinReach(Vec3d eye, Box box, double reach) {
        return finite(eye) && finite(box) && withinReach(eye, closestPoint(box, eye), reach);
    }

    /**
     * In reach of the hitbox where it is shown now AND where one more tick of its latest
     * movement puts it. The client shows a remote entity behind the server, so a target
     * that is only in reach at the older position is refused.
     */
    public static boolean withinReachThroughStep(Vec3d eye, Box box, Vec3d step, double reach) {
        return finite(step) && withinReach(eye, box, reach) && withinReach(eye, box.offset(step), reach);
    }

    /** Whether the view ray from {@code eye} crosses {@code box}, grown by {@code margin}, within {@code reach}. */
    public static boolean lookCrosses(Vec3d eye, double yawDeg, double pitchDeg, Box box, double reach,
                                      double margin) {
        if (!finite(eye) || !finite(box) || !Double.isFinite(yawDeg) || !Double.isFinite(pitchDeg)) {
            return false;
        }
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double[] origin = {eye.x, eye.y, eye.z};
        double[] dir = {-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)};
        double[] min = {box.minX - margin, box.minY - margin, box.minZ - margin};
        double[] max = {box.maxX + margin, box.maxY + margin, box.maxZ + margin};
        double near = 0.0;
        double far = reach;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(dir[axis]) < 1.0e-9) {
                if (origin[axis] < min[axis] || origin[axis] > max[axis]) {
                    return false;
                }
                continue;
            }
            double a = (min[axis] - origin[axis]) / dir[axis];
            double b = (max[axis] - origin[axis]) / dir[axis];
            near = Math.max(near, Math.min(a, b));
            far = Math.min(far, Math.max(a, b));
            if (near > far) {
                return false;
            }
        }
        return true;
    }
}
