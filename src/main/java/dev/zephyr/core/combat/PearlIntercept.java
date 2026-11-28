package dev.zephyr.core.combat;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Where to throw a wind charge so it meets our ender pearl in the air.
 *
 * <p>Vanilla 1.21.11 per-tick physics: the pearl does {@code pos += v; v *= 0.99; v.y -= 0.03};
 * a wind charge flies straight at {@code 1.5 * direction + shooter movement} from the eyes (the
 * vertical part of the movement only while airborne). They meet at the first tick k where the
 * pearl's distance from the wind charge's origin equals {@code 1.5 k}, interpolated between ticks.
 */
public final class PearlIntercept {

    private static final double DRAG = 0.99;
    private static final double GRAVITY = 0.03;
    private static final double WIND_SPEED = 1.5;
    private static final int HORIZON = 80;

    private PearlIntercept() {
    }

    /**
     * @param lead ticks the server's pearl is ahead of the one on screen plus the throw delay
     * @return {yaw, pitch} for the wind charge, or null when it can no longer catch the pearl
     */
    public static float[] solve(ClientPlayerEntity self, EnderPearlEntity pearl, int lead) {
        double[] px = new double[HORIZON + 2];
        double[] py = new double[HORIZON + 2];
        double[] pz = new double[HORIZON + 2];
        Vec3d p = pearl.getEntityPos();
        Vec3d v = pearl.getVelocity();
        double x = p.x, y = p.y, z = p.z, vx = v.x, vy = v.y, vz = v.z;
        for (int m = 0; m <= HORIZON + 1; m++) {
            px[m] = x;
            py[m] = y;
            pz[m] = z;
            x += vx;
            y += vy;
            z += vz;
            vx *= DRAG;
            vy = vy * DRAG - GRAVITY;
            vz *= DRAG;
        }
        Vec3d eye = self.getEyePos();
        Vec3d mv = self.getVelocity();
        double mx = mv.x, my = self.isOnGround() ? 0.0 : mv.y, mz = mv.z;
        double prevF = Double.NaN;
        for (int k = 0; k + lead <= HORIZON + 1; k++) {
            int i = k + lead;
            double rx = px[i] - eye.x - mx * k, ry = py[i] - eye.y - my * k, rz = pz[i] - eye.z - mz * k;
            double f = Math.sqrt(rx * rx + ry * ry + rz * rz) - WIND_SPEED * k;
            if (k > 0 && prevF > 0 && f <= 0) {
                double t = prevF / (prevF - f); // fraction between tick k-1 and k
                double kk = k - 1 + t;
                int j = i - 1;
                double ax = px[j] + (px[i] - px[j]) * t - eye.x - mx * kk;
                double ay = py[j] + (py[i] - py[j]) * t - eye.y - my * kk;
                double az = pz[j] + (pz[i] - pz[j]) * t - eye.z - mz * kk;
                double h = Math.sqrt(ax * ax + az * az);
                float yaw = (float) (MathHelper.atan2(az, ax) * MathHelper.DEGREES_PER_RADIAN) - 90f;
                float pitch = (float) -(MathHelper.atan2(ay, h) * MathHelper.DEGREES_PER_RADIAN);
                return new float[]{yaw, pitch};
            }
            prevF = f;
        }
        return null;
    }
}
