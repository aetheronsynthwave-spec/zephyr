package dev.zephyr.modules.utility;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.NumberSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.List;

/**
 * SoundDetector - alerts on configured server sounds (explosions, elytra, totems, anvils,
 * chests...) with distance and compass direction. Fed by MixinClientPlayNetworkHandler.
 * Simplified from Donut Client's Sound Detector (no rule-editor GUI).
 */
public final class SoundDetector extends Module {

    public static volatile int received;
    private static final List<String> KEYWORDS = List.of("explode", "elytra", "totem", "anvil",
            "chest", "ender_pearl", "wind_charge", "respawn_anchor");

    private final NumberSetting minDistance = num("minDist", "Min distance", 0, 64, 1, 8);
    private final NumberSetting cooldown = num("cooldown", "Cooldown ticks", 0, 200, 5, 40);
    private long lastAlert;

    public SoundDetector() {
        super("SoundDetector", ModuleCategory.UTILITY, 0);
    }

    public void onSound(String path, double x, double y, double z) {
        received++;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!isEnabled() || mc.player == null) {
            return;
        }
        boolean hit = false;
        for (String k : KEYWORDS) {
            if (path.contains(k)) {
                hit = true;
                break;
            }
        }
        if (!hit) {
            return;
        }
        double dx = x - mc.player.getX(), dz = z - mc.player.getZ(), dy = y - mc.player.getY();
        double dist = Math.sqrt(dx * dx + dz * dz + dy * dy);
        long now = mc.world == null ? 0 : mc.world.getTime();
        if (dist < minDistance.get() || now - lastAlert < cooldown.asInt()) {
            return;
        }
        lastAlert = now;
        String[] dirs = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
        double ang = Math.toDegrees(Math.atan2(-dx, dz)); // MC yaw convention: 0 = +Z (south)
        String dir = dirs[Math.floorMod((int) Math.round(ang / 45.0), 8)];
        mc.player.sendMessage(Text.literal(String.format("[Zephyr] sound %s %.0fm %s", path, dist, dir)), true);
    }
}
