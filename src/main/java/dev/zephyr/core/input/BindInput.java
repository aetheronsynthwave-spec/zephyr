package dev.zephyr.core.input;

import java.util.ArrayDeque;

/**
 * Bind input queue. Every physical key / mouse-button PRESS is recorded the instant GLFW
 * reports it (from the keyboard and mouse input hooks), then dispatched on the next game
 * tick. The old system polled key state once per tick (50 ms), so a quick tap that went
 * down and up between two polls was simply lost - that is why trigger binds like
 * PearlCatch sometimes "did nothing".
 *
 * <p>Bind codes: keyboard keys are their GLFW key codes; mouse buttons are stored as
 * {@link #MOUSE_BASE} + button (so they never collide with key codes).
 */
public final class BindInput {

    private BindInput() {
    }

    /** Mouse button N is stored as MOUSE_BASE + N. */
    public static final int MOUSE_BASE = 10_000;

    private static final ArrayDeque<Integer> PRESSES = new ArrayDeque<>();

    public static void keyPressed(int glfwKey) {
        if (glfwKey > 0) {
            synchronized (PRESSES) {
                PRESSES.add(glfwKey);
            }
        }
    }

    public static void mousePressed(int button) {
        if (button >= 0) {
            synchronized (PRESSES) {
                PRESSES.add(MOUSE_BASE + button);
            }
        }
    }

    /** @return the next queued press, or -1 when empty. */
    public static int poll() {
        synchronized (PRESSES) {
            Integer v = PRESSES.poll();
            return v == null ? -1 : v;
        }
    }

    public static void clear() {
        synchronized (PRESSES) {
            PRESSES.clear();
        }
    }

    public static boolean isMouse(int code) {
        return code >= MOUSE_BASE;
    }

    public static String mouseName(int code) {
        int b = code - MOUSE_BASE;
        return switch (b) {
            case 0 -> "LMB";
            case 1 -> "RMB";
            case 2 -> "MMB";
            default -> "Mouse" + (b + 1);
        };
    }
}
