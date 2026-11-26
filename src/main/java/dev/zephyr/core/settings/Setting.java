package dev.zephyr.core.settings;

import java.util.function.Consumer;

/**
 * Base of every module setting. A setting is an identified, optionally-validated value
 * with a change listener so modules can react to configuration edits immediately.
 */
public abstract class Setting<T> {

    private final String id;
    private final String label;
    private final String description;
    private T value;
    private final T defaultValue;
    private Consumer<T> onSet;

    protected Setting(String id, String label, String description, T defaultValue) {
        this.id = id;
        this.label = label;
        this.description = description;
        this.value = defaultValue;
        this.defaultValue = defaultValue;
    }

    public T defaultValue() {
        return defaultValue;
    }

    /** Back to the built-in default (used by "New config"). */
    public void reset() {
        loadRaw(defaultValue);
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public T get() {
        return value;
    }

    /** Sets the value through the validation path; invalid values are silently ignored. */
    public void set(T newValue) {
        T validated = validate(newValue);
        if (validated == null) {
            return;
        }
        this.value = validated;
        if (onSet != null) {
            onSet.accept(validated);
        }
    }

    /** Raw assignment used only by the config loader, bypassing user validation. */
    public void loadRaw(T raw) {
        this.value = raw;
    }

    public Setting<T> onChange(Consumer<T> listener) {
        this.onSet = listener;
        return this;
    }

    /** @return a clamped/normalized value, or null to reject. */
    protected abstract T validate(T candidate);

    /** @return value serialized for the JSON config. */
    public Object serialize() {
        return value;
    }
}
