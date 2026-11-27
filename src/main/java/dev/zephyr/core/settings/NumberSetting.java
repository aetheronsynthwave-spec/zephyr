package dev.zephyr.core.settings;

public class NumberSetting extends Setting<Double> {

    private final double min;
    private final double max;
    private final double step;

    public NumberSetting(String id, String label, double min, double max, double step, double defaultValue) {
        super(id, label, "", defaultValue);
        this.min = min;
        this.max = max;
        this.step = step;
    }

    public NumberSetting(String id, String label, String description, double min, double max, double step, double defaultValue) {
        super(id, label, description, defaultValue);
        this.min = min;
        this.max = max;
        this.step = step;
    }

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    public double step() {
        return step;
    }

    public int asInt() {
        return (int) Math.round(get());
    }

    @Override
    protected Double validate(Double candidate) {
        if (candidate == null || candidate.isNaN()) {
            return null;
        }
        return Math.min(max, Math.max(min, candidate));
    }
}
