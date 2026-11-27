package dev.zephyr.core.settings;

import org.lwjgl.glfw.GLFW;

/** Keybind stored as a GLFW keycode; 0 = unbound. */
public class KeySetting extends Setting<Integer> {

    public KeySetting(String id, String label, int defaultKey) {
        super(id, label, "", defaultKey);
    }

    public boolean isBound() {
        return get() != GLFW.GLFW_KEY_UNKNOWN;
    }

    public String display() {
        int key = get();
        if (key <= 0) {
            return "-";
        }
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) {
            return String.valueOf((char) ('0' + (key - GLFW.GLFW_KEY_0)));
        }
        if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) {
            return String.valueOf((char) ('A' + (key - GLFW.GLFW_KEY_A)));
        }
        return "K" + key;
    }

    @Override
    protected Integer validate(Integer candidate) {
        return candidate != null && candidate >= 0 ? candidate : null;
    }
}
