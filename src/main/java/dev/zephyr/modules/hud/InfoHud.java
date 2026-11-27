package dev.zephyr.modules.hud;

import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.hud.HudModule;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.gui.render.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * InfoHUD - a compact panel (top-right by default): coordinates, the matching Nether/Overworld
 * coordinates, facing (with the axis you are walking along), horizontal speed, FPS and
 * ping. Values are sampled once per tick (speed from the real position delta) and the
 * panel is drawn per frame from those cached strings.
 */
public final class InfoHud extends HudModule {

    private static final float SIZE = 8.5f;
    private static final float ROW = 11f;

    private final BooleanSetting coords = bool("coords", "Coordinates", true);
    private final BooleanSetting otherDim = bool("otherDim", "Nether / Overworld coords", true);
    private final BooleanSetting facing = bool("facing", "Facing", true);
    private final BooleanSetting speed = bool("speed", "Speed", true);
    private final BooleanSetting fps = bool("fps", "FPS", true);
    private final BooleanSetting ping = bool("ping", "Ping", true);

    private final List<String[]> lines = new ArrayList<>();
    private final float[] size = new float[2];
    private float labelW;
    private float valueW;
    private float contentW;
    private boolean linesDirty = true;
    private double lastX = Double.NaN;
    private double lastZ;
    private double speedBps;

    public InfoHud() {
        super("InfoHUD", ModuleCategory.RENDER, 100, 0, "TOP_RIGHT");
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
        linesDirty = true;
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
    protected float[] measure() {
        if (linesDirty) {
            labelW = 0f;
            valueW = 0f;
            for (String[] l : lines) {
                labelW = Math.max(labelW, Ui.textWidth(l[0], SIZE, false));
                valueW = Math.max(valueW, Ui.textWidth(l[1], SIZE, false));
            }
            linesDirty = false;
        }
        if (lines.isEmpty()) {
            size[0] = 0f;
            size[1] = 0f;
            return size;
        }
        contentW = labelW + 12f + valueW;
        size[0] = contentW;
        size[1] = lines.size() * ROW - 3f;
        return size;
    }

    @Override
    protected void renderContent(DrawContext ctx, float x, float y, float alpha) {
        float ly = y;
        for (String[] l : lines) {
            drawText(ctx, l[0], x, ly, SIZE, textFaint(), false, alpha);
            drawTextRight(ctx, l[1], x + contentW, ly, SIZE, text(), false, alpha);
            ly += ROW;
        }
    }
}
