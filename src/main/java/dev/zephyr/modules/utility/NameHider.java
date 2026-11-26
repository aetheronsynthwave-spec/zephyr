package dev.zephyr.modules.utility;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * NameHider - Donut Client's streaming privacy module. Rewrites your own account name to
 * an alias in chat/game messages received by this client (display only; the server and
 * other players still see the real name).
 */
public final class NameHider extends Module {

    private static NameHider instance;
    private static boolean registered;

    public NameHider() {
        super("NameHider", ModuleCategory.UTILITY, 0);
        instance = this;
        if (!registered) {
            registered = true;
            ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> instance.rewrite(message));
            // Player chat cannot be modified in place, so a message that mentions you is
            // suppressed and re-added with the alias (display only, as for game messages).
            ClientReceiveMessageEvents.ALLOW_CHAT.register((m, sm, sender, params, ts) -> {
                Text rewritten = instance.rewrite(m);
                if (rewritten == m) {
                    return true;
                }
                MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(rewritten);
                return false;
            });
        }
    }

    private Text rewrite(Text message) {
        if (!isEnabled()) {
            return message;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            return message;
        }
        String real = mc.player.getName().getString();
        String flat = message.getString();
        if (!flat.contains(real)) {
            return message;
        }
        return Text.literal(flat.replace(real, "Hidden"));
    }

    @Override
    public String hudLabel() {
        return name;
    }
}
