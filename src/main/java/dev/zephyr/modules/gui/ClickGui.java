package dev.zephyr.modules.gui;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.gui.ClickGuiScreen;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

/**
 * ClickGui - opens the module manager screen. Not a toggleable feature: pressing the
 * bind (default RIGHT SHIFT) opens the GUI, pressing it again closes it.
 *
 * <p>Owns the persisted "GUI scale" setting (default 0.5 = half-size panels, a quarter
 * of the screen area - the readable interpretation of "3x smaller"). The screen reads
 * it every frame, so dragging the slider in the GUI column rescales everything live.
 */
public final class ClickGui extends Module {

    private final NumberSetting scale = num("scale", "GUI scale", 0.15, 1.5, 0.05, 0.4);

    public ClickGui() {
        super("ClickGui", ModuleCategory.GUI, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    /** Pushes the setting into the screen renderer (called every GUI frame). */
    public void syncScale() {
        ClickGuiScreen.setRenderScale(scale.get().floatValue());
    }

    /** Ctrl+wheel in the menu: nudge the persisted scale (clamped by the setting). */
    public void adjustScale(double delta) {
        scale.set(Math.round((scale.get() + delta) * 100.0) / 100.0);
        syncScale();
    }

    /** Called from the keybind poller on a rising edge of the bound key. */
    public void openOrClose(MinecraftClient client) {
        syncScale();
        if (client.currentScreen instanceof ClickGuiScreen) {
            client.setScreen(null);
        } else {
            client.setScreen(new ClickGuiScreen());
        }
    }

    @Override
    public String hudLabel() {
        int bind = bind().get();
        String key = bind == GLFW.GLFW_KEY_RIGHT_SHIFT ? "RShift" : "K" + bind;
        return name + " · " + key + " · x" + String.format("%.2f", scale.get());
    }
}
