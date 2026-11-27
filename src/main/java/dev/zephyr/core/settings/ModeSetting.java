package dev.zephyr.core.settings;

import java.util.List;
import java.util.Objects;

/** Enum-style mode selector, e.g. AutoCrystal: DOUBLE_TAP / INFINITE / HIT_ONLY. */
public class ModeSetting extends Setting<String> {

    private final List<String> modes;

    public ModeSetting(String id, String label, List<String> modes, String defaultValue) {
        super(id, label, "", defaultValue);
        this.modes = List.copyOf(modes);
    }

    public List<String> modes() {
        return modes;
    }

    public boolean is(String mode) {
        return Objects.equals(get(), mode);
    }

    public String cycle() {
        int idx = modes.indexOf(get());
        String next = modes.get(Math.floorMod(idx + 1, modes.size()));
        set(next);
        return next;
    }

    @Override
    protected String validate(String candidate) {
        return modes.contains(candidate) ? candidate : null;
    }
}
