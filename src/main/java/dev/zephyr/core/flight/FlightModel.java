package dev.zephyr.core.flight;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Pure client-side forecast of vanilla movement, used by the practice elytra bot to choose a
 * dive line and the moment to take the elytra off. No world access and no side effects.
 */
public final class FlightModel {

    private static final double GRAVITY = 0.08;
    private static final double STANDING_EYE = 1.62;

    private FlightModel() {
    }

    /** One tick of elytra flight (vanilla {@code LivingEntity#calcGlidingVelocity}). */
    public static Vec3d glideStep(Vec3d v, double yawDeg, double pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double cosPitch = Math.cos(pitch);
        double lx = -Math.sin(yaw) * cosPitch;
        double lz = Math.cos(yaw) * cosPitch;
        double horizLook = Math.sqrt(lx * lx + lz * lz);
        double horizVel = Math.sqrt(v.x * v.x + v.z * v.z);
        double lift = cosPitch * cosPitch;

        double x = v.x, y = v.y + GRAVITY * (-1.0 + lift * 0.75), z = v.z;
        if (y < 0.0 && horizLook > 0.0) {
            double i = y * -0.1 * lift;
            x += lx * i / horizLook;
            y += i;
            z += lz * i / horizLook;
        }
        if (pitch < 0.0 && horizLook > 0.0) {
            double i = horizVel * -Math.sin(pitch) * 0.04;
            x -= lx * i / horizLook;
            y += i * 3.2;
            z -= lz * i / horizLook;
        }
        if (horizLook > 0.0) {
            x += (lx / horizLook * horizVel - x) * 0.1;
            z += (lz / horizLook * horizVel - z) * 0.1;
        }
        return new Vec3d(x * 0.99, y * 0.98, z * 0.99);
    }

    /** The pull of a burning firework rocket, applied on top of a gliding tick. */
    public static Vec3d boostStep(Vec3d v, double yawDeg, double pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double lx = -Math.sin(yaw) * Math.cos(pitch);
        double ly = -Math.sin(pitch);
        double lz = Math.cos(yaw) * Math.cos(pitch);
        return new Vec3d(v.x + lx * 0.1 + (lx * 1.5 - v.x) * 0.5,
                v.y + ly * 0.1 + (ly * 1.5 - v.y) * 0.5,
                v.z + lz * 0.1 + (lz * 1.5 - v.z) * 0.5);
    }

    /** One tick of un-steered airborne fall: the velocity to use on the following tick. */
    public static Vec3d fallStep(Vec3d v) {
        return new Vec3d(v.x * 0.91, (v.y - GRAVITY) * 0.98, v.z * 0.91);
    }

    /** Where the target will be {@code t} ticks from now (lead is capped: players change direction). */
    public static Box predict(Box box, Vec3d velocity, int t) {
        double lead = Math.min(t, 8);
        return box.offset(velocity.x * lead, velocity.y * lead, velocity.z * lead);
    }

    /**
     * Would taking the elytra off now lead to a smash? Models {@code lag} more gliding ticks
     * (the swap has to reach the server), then free fall.
     *
     * @return ticks until the target is in reach with enough fall banked, or -1
     */
    public static int ticksToSmash(Vec3d pos, Vec3d vel, double yaw, double pitch, double fallen, int lag,
                                   Box target, Vec3d targetVel, double reach, double minFall, int horizon,
                                   boolean boosted) {
        Vec3d p = pos, v = vel;
        double fall = fallen;
        for (int t = 1; t <= horizon; t++) {
            boolean gliding = t <= lag;
            if (gliding) {
                v = glideStep(v, yaw, pitch);
                if (boosted) {
                    v = boostStep(v, yaw, pitch);
                }
            }
            Vec3d next = p.add(v);
            fall += Math.max(0.0, p.y - next.y);
            Box box = predict(target, targetVel, t);
            if (next.y < box.minY - 0.5) {
                return -1; // below the target's feet: passed it or hit its floor
            }
            if (!gliding && fall >= minFall && box.squaredMagnitude(next.add(0, STANDING_EYE, 0)) <= reach * reach) {
                return t;
            }
            p = next;
            if (!gliding) {
                v = fallStep(v);
            }
        }
        return -1;
    }

    /** A chosen heading and how closely it is forecast to pass the point above the target. */
    public record Line(double yaw, double pitch, double miss) {
    }

    /**
     * Tries headings around the target bearing, flies each one forward, and keeps the one that
     * passes closest to a point just above the target's head.
     */
    public static Line bestDive(Vec3d pos, Vec3d vel, double bearing, Box target, Vec3d targetVel,
                                boolean boosted) {
        Line best = new Line(bearing, 60.0, Double.MAX_VALUE);
        for (int dy = -24; dy <= 24; dy += 8) {
            for (int pitch = 0; pitch <= 90; pitch += 5) {
                Vec3d p = pos, v = vel;
                double closest = Double.MAX_VALUE;
                for (int t = 1; t <= 70; t++) {
                    // Hold the heading, but never keep flying away once past the target.
                    v = glideStep(v, bearing + dy, pitch);
                    if (boosted && t <= 12) {
                        v = boostStep(v, bearing + dy, pitch);
                    }
                    p = p.add(v);
                    Box box = predict(target, targetVel, t);
                    double mx = (box.minX + box.maxX) * 0.5 - p.x;
                    double my = box.maxY + 0.8 - p.y;
                    double mz = (box.minZ + box.maxZ) * 0.5 - p.z;
                    closest = Math.min(closest, Math.sqrt(mx * mx + my * my + mz * mz));
                    if (p.y < box.minY) {
                        break;
                    }
                }
                // Prefer steeper lines among near-equal ones: more height lost = harder smash.
                double score = closest - pitch * 0.004;
                if (score < best.miss) {
                    best = new Line(bearing + dy, pitch, score);
                }
            }
        }
        return best;
    }
}
