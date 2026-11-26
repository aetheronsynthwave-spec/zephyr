package dev.zephyr.modules.hud;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.event.ZephyrEvents;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.gui.render.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * SessionHUD - a small panel with this session's stats: time played, blocks travelled,
 * hits landed, deaths and blocks mined (from the vanilla client-side counters we can see).
 * Resets when you join a world.
 */
public final class SessionHud extends Module {

    private final NumberSetting scale = num("scale", "Scale", 0.6, 2.0, 0.05, 1.0);
    private final NumberSetting yPct = num("yPct", "Y position %", 0, 100, 0.5, 35);

    private long startMs;
    private double travelled;
    private double lastX = Double.NaN, lastZ;
    private int hits;
    private int deaths;
    private boolean wasDead;
    private Object worldSeen;
    private final String[] lines = new String[4];
    private int lastSecond = -1;

    public SessionHud() {
        super("SessionHUD", ModuleCategory.RENDER, 0);
        ZephyrEvents.ATTACK_LANDED.subscribe(e -> {
            if (isEnabled() && e.landed) {
                hits++;
            }
        });
    }

    @Override
    protected void onEnable() {
        reset();
    }

    private void reset() {
        startMs = System.currentTimeMillis();
        travelled = 0;
        lastX = Double.NaN;
        hits = 0;
        deaths = 0;
        lastSecond = -1;
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null) {
            return;
        }
        if (worldSeen != client.world) {
            if (worldSeen == null || client.getNetworkHandler() == null) {
                reset();
            }
            worldSeen = client.world;
            lastX = Double.NaN; // dimension change: no teleport distance
        }
        if (!Double.isNaN(lastX) && !p.isDead()) {
            double dx = p.getX() - lastX, dz = p.getZ() - lastZ;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < 10) { // ignore teleports
                travelled += d;
            }
        }
        lastX = p.getX();
        lastZ = p.getZ();
        if (p.isDead() && !wasDead) {
            deaths++;
        }
        wasDead = p.isDead();
        int sec = (int) ((System.currentTimeMillis() - startMs) / 1000);
        if (sec != lastSecond || lines[0] == null) { // text rebuilt once a second, not per tick
            lastSecond = sec;
            lines[0] = String.format("%d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60);
            lines[1] = travelled >= 1000 ? String.format("%.2f km", travelled / 1000) : (int) travelled + " m";
            lines[2] = Integer.toString(hits);
            lines[3] = Integer.toString(deaths);
        }
    }

    private static final String[] LABELS = {"Session", "Travelled", "Hits", "Deaths"};

    @Override
    public void onHudRender(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden || lines[0] == null) {
            return;
        }
        float s = scale.get().floatValue();
        float sh = mc.getWindow().getScaledHeight() / s;
        float w = 110, h = 6 + LABELS.length * 11 + 4;
        float x = 6, y = (sh - h) * yPct.get().floatValue() / 100f;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().scale(s, s);
        Ui.beginFrame(s);
        Ui.shadow(ctx, x, y, w, h, 6, 6, 0x55000000);
        Ui.roundRect(ctx, x, y, w, h, 6, 0xB0101319);
        float ly = y + 6;
        for (int i = 0; i < LABELS.length; i++) {
            Ui.text(ctx, LABELS[i], x + 8, ly, 8.5f, Ui.TEXT_FAINT, false);
            Ui.textRight(ctx, lines[i], x + w - 8, ly, 8.5f, Ui.TEXT, false);
            ly += 11;
        }
        ctx.getMatrices().popMatrix();
    }
}
