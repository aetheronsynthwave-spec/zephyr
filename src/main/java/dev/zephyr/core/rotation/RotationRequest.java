package dev.zephyr.core.rotation;

/**
 * Immutable request describing a desired view rotation and how fast it may be reached.
 * Consumers build these per-tick; {@link RotationManager} owns the single highest-priority one.
 */
public final class RotationRequest {

    public final double targetYaw;
    public final double targetPitch;
    /** Angular speed bounds, degrees per tick. Actual per-tick step is sampled inside these bounds. */
    public final double minSpeedDeg;
    public final double maxSpeedDeg;
    /** Max random micro-jitter added to the smoothed step, degrees. */
    public final double jitterDeg;
    /** Silent rotations are only written into movement packets; the visible camera stays untouched. */
    public final boolean silent;
    /** Higher priority supersedes lower active requests of the same frame. */
    public final int priority;
    /** Validity probe evaluated each tick; when it fails the request dies instantly. */
    public final java.util.function.BooleanSupplier validity;
    /** Lifetime guard so abandoned requests never stick. */
    public final int maxTicks;

    private RotationRequest(double targetYaw, double targetPitch, double minSpeedDeg, double maxSpeedDeg,
                            double jitterDeg, boolean silent, int priority,
                            java.util.function.BooleanSupplier validity, int maxTicks) {
        this.targetYaw = targetYaw;
        this.targetPitch = targetPitch;
        this.minSpeedDeg = minSpeedDeg;
        this.maxSpeedDeg = maxSpeedDeg;
        this.jitterDeg = jitterDeg;
        this.silent = silent;
        this.priority = priority;
        this.validity = validity;
        this.maxTicks = maxTicks;
    }

    public static Builder to(double targetYaw, double targetPitch) {
        return new Builder(targetYaw, targetPitch);
    }

    public static final class Builder {
        private final double yaw;
        private final double pitch;
        private double minSpeed = 10.0;
        private double maxSpeed = 40.0;
        private double jitter = 0.35;
        private boolean silent = true;
        private int priority = 0;
        private java.util.function.BooleanSupplier validity = () -> true;
        private int maxTicks = 20;

        private Builder(double yaw, double pitch) {
            this.yaw = yaw;
            this.pitch = pitch;
        }

        public Builder speed(double minDegPerTick, double maxDegPerTick) {
            this.minSpeed = minDegPerTick;
            this.maxSpeed = maxDegPerTick;
            return this;
        }

        public Builder jitter(double deg) {
            this.jitter = deg;
            return this;
        }

        public Builder silent(boolean silent) {
            this.silent = silent;
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public Builder validWhile(java.util.function.BooleanSupplier validity) {
            this.validity = validity;
            return this;
        }

        public Builder expiresAfter(int ticks) {
            this.maxTicks = ticks;
            return this;
        }

        public RotationRequest build() {
            double maxSpeed = Math.max(this.minSpeed, this.maxSpeed);
            return new RotationRequest(yaw, pitch, minSpeed, maxSpeed, jitter, silent, priority, validity, maxTicks);
        }
    }
}
