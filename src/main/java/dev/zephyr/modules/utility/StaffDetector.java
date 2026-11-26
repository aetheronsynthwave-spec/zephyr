package dev.zephyr.modules.utility;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * StaffDetector - evidence-based, never a guarantee (same stance as Donut Client's, MIT).
 * Signals: names on the user's staff list, players the tab list reports in SPECTATOR
 * mode, and invisible empty-handed player entities nearby. Each signal alerts once.
 */
public final class StaffDetector extends Module {

    public static final Set<String> STAFF = new LinkedHashSet<>();

    private final BooleanSetting spectators = bool("spectators", "Flag spectator-mode players", true);
    private final BooleanSetting invisible = bool("invisible", "Flag nearby invisible players", true);
    private final BooleanSetting chat = bool("chat", "Chat alerts", true);

    private final Set<String> alerted = new HashSet<>();
    public final Set<String> evidence = new LinkedHashSet<>();

    public StaffDetector() {
        super("StaffDetector", ModuleCategory.UTILITY, 0);
    }

    @Override
    protected void onEnable() {
        alerted.clear();
        evidence.clear();
    }

    @Override
    public void onTick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null || mc.getNetworkHandler() == null) {
            return;
        }
        String self = mc.player.getGameProfile().name();
        for (PlayerListEntry e : mc.getNetworkHandler().getPlayerList()) {
            String name = e.getProfile().name();
            if (name == null || name.equals(self)) {
                continue;
            }
            if (STAFF.contains(name.toLowerCase())) {
                flag(mc, name, "listed staff is online");
            }
            if (spectators.get() && e.getGameMode() == GameMode.SPECTATOR) {
                flag(mc, name, "is in spectator mode");
            }
        }
        if (invisible.get()) {
            for (PlayerEntity p : mc.world.getPlayers()) { // players only: no full-world walk
                if (p != mc.player && p.isInvisible() && !p.isSpectator()
                        && p.squaredDistanceTo(mc.player) < 64 * 64
                        && p.getEquippedStack(net.minecraft.entity.EquipmentSlot.MAINHAND).isEmpty()) {
                    flag(mc, p.getGameProfile().name(), "is invisible nearby");
                }
            }
        }
    }

    private void flag(MinecraftClient mc, String name, String why) {
        String key = name + "|" + why;
        evidence.add(name + " " + why);
        if (alerted.add(key) && chat.get()) {
            mc.player.sendMessage(Text.literal("[Zephyr] Staff signal: " + name + " " + why), false);
        }
    }

    @Override
    public String hudLabel() {
        return name + (evidence.isEmpty() ? "" : " · " + evidence.size() + " signal(s)");
    }
}
