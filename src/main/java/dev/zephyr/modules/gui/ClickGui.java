package dev.zephyr.modules.gui;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.gui.ClickGuiScreen;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

/**
 * ClickGui - opens the module manager screen. Not a toggleable feature: pressing the
 * bind (default RIGHT SHIFT) opens the GUI, pressing it again closes it.
 *
 * <p>The screen is fully self-contained ({@link ClickGuiScreen}) and never pauses the
 * game; every change made inside it writes through the normal setting objects, so the
 * next config save persists it.
 */
public final class ClickGui extends Module {

    public ClickGui() {
        super("ClickGui", ModuleCategory.GUI, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    /** Called from the keybind poller on a rising edge of the bound key. */
    public void openOrClose(MinecraftClient client) {
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
        return name + " · " + key;
    }
}
