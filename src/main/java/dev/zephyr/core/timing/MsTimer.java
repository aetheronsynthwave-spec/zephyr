package dev.zephyr.core.timing;

/**
 * Millisecond-precision deadline timer used by every FSM module.
 * Reused object; restarting re-rolls a fresh randomized deadline.
 */
public final class MsTimer {

    private long deadline = Long.MIN_VALUE;
    private boolean started;

    /** Arms the timer with a gaussian delay drawn from [minMs, maxMs]. */
    public MsTimer restart(double minMs, double maxMs) {
        this.deadline = System.nanoTime() + RandomController.delayMs(minMs, maxMs) * 1_000_000L;
        this.started = true;
        return this;
    }

    /** Arms the timer with an exact delay (rare paths like 0-tick mace). */
    public MsTimer restartExact(double ms) {
        this.deadline = System.nanoTime() + (long) (ms * 1_000_000.0);
        this.started = true;
        return this;
    }

    /** Arms from a CPS rate with jitter spread. */
    public MsTimer restartCps(double cps, double jitterPct) {
        this.deadline = System.nanoTime() + RandomController.cpsIntervalMs(cps, jitterPct) * 1_000_000L;
        this.started = true;
        return this;
    }

    public boolean isDue() {
        return started && System.nanoTime() >= deadline;
    }

    public boolean started() {
        return started;
    }

    public long remainingMs() {
        if (!started) {
            return 0;
        }
        return Math.max(0, (deadline - System.nanoTime()) / 1_000_000L);
    }

    public void invalidate() {
        started = false;
        deadline = Long.MIN_VALUE;
    }
}
