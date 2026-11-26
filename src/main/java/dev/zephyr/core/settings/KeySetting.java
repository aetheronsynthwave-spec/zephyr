package dev.zephyr.core.settings;

import org.lwjgl.glfw.GLFW;

/** Keybind stored as a GLFW keycode; 0 = unbound. */
public class KeySetting extends Setting<Integer> {

    public KeySetting(String id, String label, int defaultKey) {
        super(id, label, "", defaultKey);
    }

    public boolean isBound() {
        return get() > 0;
    }

    /** Human-readable bind names for the common non-letter keys. */
    private static final java.util.Map<Integer, String> NAMES = java.util.Map.ofEntries(
            java.util.Map.entry(GLFW.GLFW_KEY_LEFT_SHIFT, "LShift"),
            java.util.Map.entry(GLFW.GLFW_KEY_RIGHT_SHIFT, "RShift"),
            java.util.Map.entry(GLFW.GLFW_KEY_LEFT_CONTROL, "LCtrl"),
            java.util.Map.entry(GLFW.GLFW_KEY_RIGHT_CONTROL, "RCtrl"),
            java.util.Map.entry(GLFW.GLFW_KEY_LEFT_ALT, "LAlt"),
            java.util.Map.entry(GLFW.GLFW_KEY_RIGHT_ALT, "RAlt"),
            java.util.Map.entry(GLFW.GLFW_KEY_SPACE, "Space"),
            java.util.Map.entry(GLFW.GLFW_KEY_TAB, "Tab"),
            java.util.Map.entry(GLFW.GLFW_KEY_CAPS_LOCK, "Caps"),
            java.util.Map.entry(GLFW.GLFW_KEY_BACKSPACE, "Bksp"),
            java.util.Map.entry(GLFW.GLFW_KEY_F1, "F1"),
            java.util.Map.entry(GLFW.GLFW_KEY_F2, "F2"),
            java.util.Map.entry(GLFW.GLFW_KEY_F3, "F3"),
            java.util.Map.entry(GLFW.GLFW_KEY_F4, "F4"),
            java.util.Map.entry(GLFW.GLFW_KEY_F5, "F5"),
            java.util.Map.entry(GLFW.GLFW_KEY_F6, "F6"),
            java.util.Map.entry(GLFW.GLFW_KEY_F7, "F7"),
            java.util.Map.entry(GLFW.GLFW_KEY_F8, "F8"),
            java.util.Map.entry(GLFW.GLFW_KEY_F9, "F9"),
            java.util.Map.entry(GLFW.GLFW_KEY_F10, "F10"),
            java.util.Map.entry(GLFW.GLFW_KEY_F11, "F11"),
            java.util.Map.entry(GLFW.GLFW_KEY_F12, "F12"),
            java.util.Map.entry(GLFW.GLFW_KEY_UP, "Up"),
            java.util.Map.entry(GLFW.GLFW_KEY_DOWN, "Down"),
            java.util.Map.entry(GLFW.GLFW_KEY_LEFT, "Left"),
            java.util.Map.entry(GLFW.GLFW_KEY_RIGHT, "Right"),
            java.util.Map.entry(GLFW.GLFW_KEY_PAGE_UP, "PgUp"),
            java.util.Map.entry(GLFW.GLFW_KEY_PAGE_DOWN, "PgDn"),
            java.util.Map.entry(GLFW.GLFW_KEY_HOME, "Home"),
            java.util.Map.entry(GLFW.GLFW_KEY_END, "End"),
            java.util.Map.entry(GLFW.GLFW_KEY_DELETE, "Del"),
            java.util.Map.entry(GLFW.GLFW_KEY_INSERT, "Ins"));

    public String display() {
        int key = get();
        if (key <= 0) {
            return "-";
        }
        if (dev.zephyr.core.input.BindInput.isMouse(key)) {
            return dev.zephyr.core.input.BindInput.mouseName(key);
        }
        String named = NAMES.get(key);
        if (named != null) {
            return named;
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
        // Negative (GLFW_KEY_UNKNOWN, what Escape/Backspace send) means "unbind" -> 0.
        // Rejecting it - the old behaviour - is why Escape never removed a bind.
        if (candidate == null) {
            return null;
        }
        return candidate < 0 ? 0 : candidate;
    }
}
