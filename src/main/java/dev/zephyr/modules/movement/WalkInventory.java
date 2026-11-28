package dev.zephyr.modules.movement;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.PlayerInput;
import org.lwjgl.glfw.GLFW;

/**
 * WalkInventory - keep walking, sprinting and jumping with the inventory or a chest open.
 *
 * <p>Vanilla releases every key when a screen opens, so while one is up the movement keys are
 * read from the keyboard directly. Never while a text box has the cursor (creative search,
 * anvil names), and never in chat, signs or books.
 */
public final class WalkInventory extends Module {

    private final BooleanSetting sprint = bool("sprint", "Keep sprinting", true);
    private final BooleanSetting jump = bool("jump", "Allow jumping", true);
    private final BooleanSetting sneak = bool("sneak", "Allow sneaking", false);

    public WalkInventory() {
        super("WalkInventory", ModuleCategory.MOVEMENT, 0);
    }

    /** The keyboard as it is, or null when movement should be left to vanilla. */
    public PlayerInput input() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!isEnabled() || client.player == null || !allows(client.currentScreen)) {
            return null;
        }
        boolean forward = held(client, client.options.forwardKey);
        return new PlayerInput(forward, held(client, client.options.backKey),
                held(client, client.options.leftKey), held(client, client.options.rightKey),
                jump.get() && held(client, client.options.jumpKey),
                sneak.get() && held(client, client.options.sneakKey),
                sprint.get() && forward && (held(client, client.options.sprintKey) || client.player.isSprinting()));
    }

    private static boolean allows(Screen screen) {
        // Not the Zephyr menu: typing there searches, so movement keys must stay letters.
        if (!(screen instanceof HandledScreen<?>)) {
            return false;
        }
        return !(screen.getFocused() instanceof TextFieldWidget field && field.isActive());
    }

    private static boolean held(MinecraftClient client, KeyBinding binding) {
        InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(binding);
        if (key.getCategory() == InputUtil.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(client.getWindow().getHandle(), key.getCode()) == GLFW.GLFW_PRESS;
        }
        return key.getCode() > 0 && InputUtil.isKeyPressed(client.getWindow(), key.getCode());
    }
}
