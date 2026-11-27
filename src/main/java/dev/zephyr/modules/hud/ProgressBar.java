package dev.zephyr.modules.hud;

import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.hud.HudModule;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.gui.render.Ui;
import net.minecraft.client.gui.DrawContext;

/**
 * ProgressBar - Donut Client's streaming overlay: three milestone tiles
 * (Overworld / Nether / End) plus a "Bases Found" counter. Milestones and the counter are
 * plain settings, so they are edited in the ClickGui (and persist in the config). Position,
 * scale and background come from the shared HUD settings.
 */
public final class ProgressBar extends HudModule {

    private static final String[] NAMES = {"Overworld", "Nether", "End"};
    /** Milestone tile colours (semantic: they identify each dimension). */
    private static final int[] COLORS = {0xFF4CAF50, 0xFFB84A3A, 0xFF8E7CC3};
    private static final float LABEL = 8f;
    private static final float TILE_TOP = 4f;
    private static final float TILE_H = 34f;
    private static final float CONTENT_H = 52f;

    private final BooleanSetting overworld = bool("overworld", "Overworld done", false);
    private final BooleanSetting nether = bool("nether", "Nether done", false);
    private final BooleanSetting end = bool("end", "End done", false);
    private final NumberSetting bases = num("bases", "Bases found", 0, 999, 1, 0);
    private final BooleanSetting outline = bool("outline", "Shiny outline", true);

    private final float[] size = new float[2];
    private float tileW = -1f;
    private int basesShown = -1;
    private String basesText = "0";

    public ProgressBar() {
        super("ProgressBar", ModuleCategory.RENDER, 0, 8, "TOP_LEFT");
    }

    @Override
    protected float[] measure() {
        if (tileW < 0f) {
            float widest = 0f;
            for (String n : NAMES) {
                widest = Math.max(widest, Ui.textWidth(n, LABEL, false));
            }
            tileW = Math.max(8f, widest + 8f); // tiles fit the widest label
        }
        size[0] = 3 * (tileW + 4f) + 4f;
        size[1] = CONTENT_H;
        return size;
    }

    @Override
    protected void drawPanel(DrawContext ctx, float w, float h, float alpha) {
        if (outline.get()) {
            float pulse = 0.6f + 0.4f * (float) Math.sin(System.currentTimeMillis() / 400.0);
            Ui.roundRect(ctx, -2f, -2f, w + 4f, h + 4f, radius() + 2f, Ui.alpha(accent(), pulse * alpha));
        }
        super.drawPanel(ctx, w, h, alpha);
    }

    @Override
    protected void renderContent(DrawContext ctx, float x, float y, float alpha) {
        for (int i = 0; i < NAMES.length; i++) {
            float tx = x + 4f + i * (tileW + 4f);
            Ui.roundRect(ctx, tx, y + TILE_TOP, tileW, TILE_H, 4f, Ui.alpha(COLORS[i], alpha));
            float lw = Ui.textWidth(NAMES[i], LABEL, false);
            drawText(ctx, NAMES[i], tx + (tileW - lw) / 2f, y + 18f, LABEL, text(), false, alpha);
            boolean done = i == 0 ? overworld.get() : i == 1 ? nether.get() : end.get();
            if (done) {
                drawText(ctx, "X", tx + tileW / 2f - 3f, y + 6f, LABEL, 0xFFFF3030, true, alpha);
            }
        }
        int count = bases.asInt();
        if (count != basesShown) {
            basesShown = count;
            basesText = String.valueOf(count);
        }
        float bw = drawText(ctx, "Bases Found: ", x + 2f, y + 44f, LABEL, text(), false, alpha);
        drawText(ctx, basesText, x + 2f + bw, y + 44f, LABEL, accent(), true, alpha);
    }

    @Override
    public String hudLabel() {
        int d = (overworld.get() ? 1 : 0) + (nether.get() ? 1 : 0) + (end.get() ? 1 : 0);
        return name + " · " + d + "/3 · " + bases.asInt() + " bases";
    }
}
