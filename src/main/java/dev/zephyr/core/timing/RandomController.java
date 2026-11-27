package dev.zephyr.core.timing;

import java.util.Random;

/**
 * Human-like randomized timing source.
 *
 * <p>All delays are drawn from a center-weighted gaussian distribution clipped into the
 * configured in, max] band: the distribution mass concentrates around the midpoint with
 * mild sigma, so sequences look organic instead of the mechanical uniform "min..max roll"
 * pattern that rigid schedulers produce.
 *
 * <p>Perf: the v1.2 version allocated a fresh {@code new Random()} on EVERY roll (the
 * hot path includes per-tick rotation steps and CPS timers). Random construction is
 * cheap but not free, and the seed source serializes under contention; a shared
 * per-thread instance removes the allocation entirely.
 */
public final class RandomController {

    private RandomController() {
    }

    private static final ThreadLocal<Random> RNG = ThreadLocal.withInitial(Random::new);

    /**
     * Gaussian-centered roll clipped to in, max], in milliseconds.
     * Sigma is proportional to the half-band so the roll mass sits near the midpoint.
     */
    public static int delayMs(double min, double max) {
        double mid = (min + max) / 2.0;
        double halfBand = Math.max(1.0, (max - min) / 2.0);
        double sigma = halfBand * 0.45;
        Random rng = RNG.get();
        double sample = mid + (rng.nextGaussian() * sigma);
        // Clip outliers back into the band (max two retries keeps mass gaussian-dominated).
        for (int i = 0; i < 2 && (sample < min || sample > max); i++) {
            sample = mid + (rng.nextGaussian() * sigma);
        }
        sample = Math.max(min, Math.min(max, sample));
        return (int) Math.round(sample);
    }

    /**
     * Interval between CPS-paced actions: 1000/cps with +-jitter% gaussian spread.
     */
    public static long cpsIntervalMs(double cps, double jitterPct) {
        double base = 1000.0 / Math.max(0.1, cps);
        double jitter = base * (jitterPct / 100.0);
        double sample = base + (RNG.get().nextGaussian() * jitter * 0.5);
        return Math.max(10L, Math.round(sample));
    }

    public static boolean chance(double probability) {
        return RNG.get().nextDouble() < probability;
    }

    public static double uniform(double min, double max) {
        return min + RNG.get().nextDouble() * (max - min);
    }

    public static int uniformInt(int min, int max) {
        return min + RNG.get().nextInt(Math.max(1, max - min + 1));
    }
}
