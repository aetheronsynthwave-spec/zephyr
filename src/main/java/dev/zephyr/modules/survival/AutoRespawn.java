package dev.zephyr.modules.survival;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * AutoRespawn - presses Respawn on the death screen after a short delay, through the same
 * call the vanilla button uses ({@code ClientPlayerEntity#requestRespawn}). Optionally
 * prints your death coordinates in chat (only you see it) so you can go back for your
 * items. Never acts in hardcore.
 */
public final class AutoRespawn extends Module {

    private final NumberSetting delayTicks = num("delay", "Delay (ticks)", 0, 100, 1, 10);
    private final BooleanSetting deathCoords = bool("coords", "Print death coordinates", true);

    private int deadTicks = -1;
    private boolean requested;

    public AutoRespawn() {
        super("AutoRespawn", ModuleCategory.SURVIVAL, 0);
    }

    @Override
    protected void onEnable() {
        deadTicks = -1;
        requested = false;
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null) {
            return;
        }
        if (!(client.currentScreen instanceof DeathScreen) || !p.isDead()) {
            deadTicks = -1;
            requested = false;
            return;
        }
        if (client.world.getLevelProperties().isHardcore()) {
            return;
        }
        if (deadTicks < 0) {
            deadTicks = 0;
            if (deathCoords.get()) {
                p.sendMessage(Text.literal(String.format("[Zephyr] You died at %d %d %d (%s)",
                                p.getBlockX(), p.getBlockY(), p.getBlockZ(),
                                client.world.getRegistryKey().getValue().getPath()))
                        .formatted(Formatting.GRAY), false);
            }
        }
        if (requested || deadTicks++ < delayTicks.get()) {
            return;
        }
        requested = true;
        p.requestRespawn();
        client.setScreen(null);
    }
}
