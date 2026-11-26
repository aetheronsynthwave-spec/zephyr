package dev.zephyr.modules.hud;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.gui.render.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * InfoHUD - a compact top-right panel: coordinates, the matching Nether/Overworld
 * coordinates, facing (with the axis you are walking along), horizontal speed, FPS and
 * ping. Values are sampled once per tick (speed from the real position delta) and the
 * panel is drawn per frame from those cached strings.
 */
public final class InfoHud extends Module {

    private final BooleanSetting coords = bool("coords", "Coordinates", true);
    private final BooleanSetting otherDim = bool("otherDim", "Nether / Overworld coords", true);
    private final BooleanSetting facing = bool("facing", "Facing", true);
    private final BooleanSetting speed = bool("speed", "Speed", true);
    private final BooleanSetting fps = bool("fps", "FPS", true);
    private final BooleanSetting ping = bool("ping", "Ping", true);
    private final NumberSetting scale = num("scale", "Scale", 0.6, 2.0, 0.05, 1.0);

    private final List<String[]> lines = new ArrayList<>();
    private double lastX = Double.NaN;
    private double lastZ;
    private double speedBps;

    public InfoHud() {
        super("InfoHUD", ModuleCategory.RENDER, 0);
    }

    @Override
    protected void onEnable() {
        lastX = Double.NaN;
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null) {
            return;
        }
        double x = p.getX(), y = p.getY(), z = p.getZ();
        if (!Double.isNaN(lastX)) {
            double dx = x - lastX, dz = z - lastZ;
            double instant = Math.sqrt(dx * dx + dz * dz) * 20.0; // blocks per second
            speedBps = speedBps * 0.6 + instant * 0.4; // light smoothing
        }
        lastX = x;
        lastZ = z;

        lines.clear();
        if (coords.get()) {
            lines.add(new String[]{"XYZ", String.format("%.0f  %.0f  %.0f", Math.floor(x), Math.floor(y), Math.floor(z))});
        }
        if (otherDim.get()) {
            var dim = client.world.getRegistryKey();
            if (dim == World.OVERWORLD) {
                lines.add(new String[]{"Nether", String.format("%.0f  %.0f", Math.floor(x / 8.0), Math.floor(z / 8.0))});
            } else if (dim == World.NETHER) {
                lines.add(new String[]{"Overworld", String.format("%.0f  %.0f", Math.floor(x * 8.0), Math.floor(z * 8.0))});
            }
        }
        if (facing.get()) {
            lines.add(new String[]{"Facing", facingText(p.getYaw())});
        }
        if (speed.get()) {
            lines.add(new String[]{"Speed", String.format("%.2f b/s", speedBps)});
        }
        if (fps.get()) {
            lines.add(new String[]{"FPS", Integer.toString(client.getCurrentFps())});
        }
        if (ping.get() && client.getNetworkHandler() != null) {
            PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(p.getUuid());
            lines.add(new String[]{"Ping", entry == null ? "-" : entry.getLatency() + " ms"});
        }
    }

    private static String facingText(float yaw) {
        float y = ((yaw % 360f) + 360f) % 360f;
        int i = Math.round(y / 90f) & 3; // 0 = south (+Z) in Minecraft yaw
        return switch (i) {
            case 0 -> "South (+Z)";
            case 1 -> "West (-X)";
            case 2 -> "North (-Z)";
            default -> "East (+X)";
        };
    }

    @Override
    public void onHudRender(DrawContext ctx, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden || lines.isEmpty()) {
            return;
        }
        float s = scale.get().floatValue();
        float screenW = client.getWindow().getScaledWidth() / s;
        final float size = 8.5f;
        float labelW = 0, valueW = 0;
        for (String[] l : lines) {
            labelW = Math.max(labelW, Ui.textWidth(l[0], size, false));
            valueW = Math.max(valueW, Ui.textWidth(l[1], size, false));
        }
        float w = 8 + labelW + 10 + valueW + 8;
        float h = 6 + lines.size() * 11 + 4;
        float x = screenW - w - 6;
        float y = 6;

        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().scale(s, s);
        Ui.beginFrame(s);
        Ui.shadow(ctx, x, y, w, h, 6, 6, 0x55000000);
        Ui.roundRect(ctx, x, y, w, h, 6, 0xB0101319);
        float ly = y + 6;
        for (String[] l : lines) {
            Ui.text(ctx, l[0], x + 8, ly, size, Ui.TEXT_FAINT, false);
            Ui.textRight(ctx, l[1], x + w - 8, ly, size, Ui.TEXT, false);
            ly += 11;
        }
        ctx.getMatrices().popMatrix();
    }
}
