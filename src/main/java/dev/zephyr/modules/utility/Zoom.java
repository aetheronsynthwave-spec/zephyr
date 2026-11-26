package dev.zephyr.modules.utility;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.input.BindInput;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.gui.render.Ui;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

/**
 * Zoom - spyglass-style zoom on a key. Purely visual: the world FOV is divided by the zoom
 * level in {@code GameRenderer#getFov} (MixinGameRenderer, world pass only - the hand keeps
 * its normal FOV) and look deltas are scaled down while zoomed (MixinEntity) so aiming
 * stays controllable. No packets, no saved options are touched.
 */
public final class Zoom extends Module {

    private final BooleanSetting hold = bool("hold", "Hold key (release to stop)", true);
    private final NumberSetting level = num("level", "Zoom level", 1.5, 10.0, 0.5, 4.0);
    private final BooleanSetting smooth = bool("smooth", "Smooth transition", true);
    private final NumberSetting speed = num("speed", "Transition speed", 4, 30, 1, 14);
    private final BooleanSetting scaleSensitivity = bool("sens", "Lower mouse sensitivity", true);

    /** Current FOV multiplier (1 = no zoom), animated per frame. */
    private float current = 1f;
    private long lastNanos;

    public Zoom() {
        super("Zoom", ModuleCategory.UTILITY, GLFW.GLFW_KEY_C);
    }

    @Override
    public void onTick(MinecraftClient client) {
        if (!hold.get() || bind().get() <= 0 || client.getWindow() == null) {
            return;
        }
        int code = bind().get();
        long w = client.getWindow().getHandle();
        boolean down = BindInput.isMouse(code)
                ? GLFW.glfwGetMouseButton(w, code - BindInput.MOUSE_BASE) == GLFW.GLFW_PRESS
                : GLFW.glfwGetKey(w, code) == GLFW.GLFW_PRESS;
        if (!down) {
            setEnabled(false); // hold mode: released -> zoom back out
        }
    }

    /**
     * Called from the world-FOV hook every frame (even while disabled, so the zoom eases
     * back out). Time-based, so repeated calls within one frame do not speed it up.
     */
    public float fovMultiplier() {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1.0e9f);
        lastNanos = now;
        float target = isEnabled() ? (float) (1.0 / level.get()) : 1f;
        if (!smooth.get()) {
            current = target;
        } else {
            current = Ui.approach(current, target, speed.get().floatValue(), dt);
            if (Math.abs(current - target) < 0.001f) {
                current = target;
            }
        }
        return current;
    }

    /** Multiplier for mouse look deltas (1 when not zoomed or the option is off). */
    public double sensitivityScale() {
        return scaleSensitivity.get() ? current : 1.0;
    }

    @Override
    public String hudLabel() {
        return String.format("%s · %.1fx", name, level.get());
    }
}
