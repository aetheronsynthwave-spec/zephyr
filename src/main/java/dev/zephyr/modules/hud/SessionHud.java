package dev.zephyr.modules.hud;

import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.event.ZephyrEvents;
import dev.zephyr.core.hud.HudModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * SessionHUD - a small panel with this session's stats: time played, blocks travelled,
 * hits landed, deaths and blocks mined (from the vanilla client-side counters we can see).
 * Resets when you join a world.
 */
public final class SessionHud extends HudModule {

    private static final String[] LABELS = {"Session", "Travelled", "Hits", "Deaths"};
    private static final float SIZE = 8.5f;
    private static final float ROW = 11f;
    private static final float CONTENT_W = 98f;

    private long startMs;
    private double travelled;
    private double lastX = Double.NaN, lastZ;
    private int hits;
    private int deaths;
    private boolean wasDead;
    private Object worldSeen;
    private final String[] lines = new String[4];
    private final float[] size = new float[2];
    private int lastSecond = -1;

    public SessionHud() {
        super("SessionHUD", ModuleCategory.RENDER, 0, 50, "CENTER");
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

    @Override
    protected float[] measure() {
        if (lines[0] == null) {
            size[0] = 0f;
            size[1] = 0f;
            return size;
        }
        size[0] = CONTENT_W;
        size[1] = LABELS.length * ROW - 3f;
        return size;
    }

    @Override
    protected void renderContent(DrawContext ctx, float x, float y, float alpha) {
        float ly = y;
        for (int i = 0; i < LABELS.length; i++) {
            drawText(ctx, LABELS[i], x, ly, SIZE, textFaint(), false, alpha);
            drawTextRight(ctx, lines[i], x + CONTENT_W, ly, SIZE, text(), false, alpha);
            ly += ROW;
        }
    }
}
