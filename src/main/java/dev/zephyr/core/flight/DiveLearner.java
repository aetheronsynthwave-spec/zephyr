package dev.zephyr.core.flight;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Random;

/**
 * The practice bot's "AI mode": a small trial-and-error learner, not a neural network. For
 * each kind of target it keeps a running score for a few values of three timing choices,
 * mostly uses the best one, sometimes tries another, and remembers the scores on disk.
 */
public final class DiveLearner {

    /** Choices per knob; the first of each is the hand-tuned default. */
    private static final double[][] CHOICES = {
            {1.0, 1.5, 0.6, 0.2},  // rocket down once the rise speed is under this
            {2, 3, 1},             // elytra off this many ticks before impact
            {1.0, 1.4, 0.6, 1.8},  // how far ahead of a moving target to aim
    };
    public static final int DIVE_AT = 0, COMMIT = 1, LEAD = 2;

    private static final Path FILE = Path.of("config", "zephyr", "elytra-memory.properties");

    private final Properties memory = new Properties();
    private final Random random = new Random();
    private final int[] picked = new int[CHOICES.length];
    private String context = "still";
    private int sinceSave;

    public DiveLearner() {
        if (Files.exists(FILE)) {
            try (Reader in = Files.newBufferedReader(FILE)) {
                memory.load(in);
            } catch (IOException | IllegalArgumentException e) {
                memory.clear(); // unreadable memory: start learning again
            }
        }
    }

    /** Picks the settings for the next attempt against a target of this kind. */
    public void choose(String kind) {
        context = kind;
        for (int knob = 0; knob < CHOICES.length; knob++) {
            int best = 0;
            for (int i = 1; i < CHOICES[knob].length; i++) {
                if (score(knob, i) > score(knob, best)) {
                    best = i;
                }
            }
            // Explore one time in eight; otherwise use what has worked.
            picked[knob] = random.nextInt(8) == 0 ? random.nextInt(CHOICES[knob].length) : best;
        }
    }

    public double value(int knob) {
        return CHOICES[knob][picked[knob]];
    }

    /** @param reward 0 for a miss, up to 1 for a quick confirmed hit */
    public void learn(double reward) {
        for (int knob = 0; knob < CHOICES.length; knob++) {
            double old = score(knob, picked[knob]);
            memory.setProperty(key(knob, picked[knob]), Double.toString(old + (reward - old) * 0.15));
        }
        if (++sinceSave >= 10) {
            save();
        }
    }

    public void save() {
        sinceSave = 0;
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer out = Files.newBufferedWriter(FILE)) {
                memory.store(out, "ElytraMaceBot AI mode memory - delete this file to start over");
            }
        } catch (IOException e) {
            System.err.println("[Zephyr] could not save ElytraMaceBot memory: " + e);
        }
    }

    private double score(int knob, int i) {
        // Untried choices start promising so each gets a turn; the default starts slightly ahead.
        String stored = memory.getProperty(key(knob, i));
        if (stored != null) {
            try {
                return Double.parseDouble(stored);
            } catch (NumberFormatException ignored) {
                // fall through to the prior
            }
        }
        return i == 0 ? 0.75 : 0.65;
    }

    private String key(int knob, int i) {
        return context + "." + knob + "." + CHOICES[knob][i];
    }
}
