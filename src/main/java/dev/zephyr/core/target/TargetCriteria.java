package dev.zephyr.core.target;

/**
 * Tunable weights for the target scoring pipeline. Normalized 0..1 feature scores are
 * combined linearly; a lower total distance-to-target-player means higher rank.
 */
public final class TargetCriteria {

    public double range = 4.5;
    public boolean playersOnly = true;
    /** Weight of closeness (0..1) in the final score. */
    public double weightDistance = 3.0;
    /** Weight of low target health (finish-off preference). */
    public double weightLowHealth = 1.5;
    /** Weight of crosshair alignment. */
    public double weightCrosshair = 1.0;
    /** Weight of threat memory (recently damaged us). */
    public double weightThreat = 2.0;
    /** Sticky-lock hysteresis: current target keeps its seat unless beaten by this ratio. */
    public double stickiness = 1.15;
    /** Max ticks to remember an entity as "attacked me" for the threat feature. */
    public int threatMemoryTicks = 100;

    public TargetCriteria range(double r) {
        this.range = r;
        return this;
    }

    public TargetCriteria playersOnly(boolean po) {
        this.playersOnly = po;
        return this;
    }
}
