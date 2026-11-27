package dev.zephyr.modules.hud;

import dev.zephyr.ZephyrClient;
import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.hud.HudModule;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.gui.render.Ui;
import net.minecraft.client.gui.DrawContext;

import java.util.Arrays;
import java.util.List;

/**
 * HUD - the Zephyr watermark (logo + name) and a smooth list of active modules, top-left,
 * sorted longest-first, with each entry sliding/fading in and out when toggled. Every entry
 * is its own card, so this element draws no shared panel behind the list.
 */
public final class ActiveHud extends HudModule {

    private static final float ROW_H = 14f;
    private static final float ROW_SIZE = 8.5f;
    private static final float WATERMARK_H = 22f;

    private final BooleanSetting watermark = bool("watermark", "Logo watermark", true);
    private final BooleanSetting showInfo = bool("info", "Show module info", true);

    /*
     * Per-module state indexed by position in ModuleManager.all() (stable after init).
     * Measuring text happens only when a module's label changes (at most 20 times a
     * second), and sorting is an in-place insertion sort: nothing is allocated per frame.
     */
    private float[] slide = new float[0];
    private float[] widths = new float[0];
    private String[] labelSeen = new String[0];
    private String[] infoOf = new String[0];
    private boolean infoFlagSeen;
    private int[] order = new int[0];
    private int drawCount;
    private float watermarkTextW = -1f;
    private final float[] size = new float[2];
    private long lastNanos;

    public ActiveHud() {
        super("HUD", ModuleCategory.RENDER, 0, 0, "TOP_LEFT");
    }

    @Override
    protected boolean drawsPanel() {
        return false;
    }

    /** Advances the slide animations and lays out the visible rows for this frame. */
    @Override
    protected float[] measure() {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1.0e9f);
        lastNanos = now;

        List<Module> all = ZephyrClient.modules().all();
        ensureCapacity(all.size());
        boolean showInfoNow = showInfo.get();
        if (showInfoNow != infoFlagSeen) {
            Arrays.fill(labelSeen, null); // info toggled: re-measure everything
            infoFlagSeen = showInfoNow;
        }
        int n = 0;
        float rowsW = 0f;
        float rowsH = 0f;
        for (int i = 0; i < all.size(); i++) {
            Module m = all.get(i);
            float target = m.isEnabled() && m != this ? 1f : 0f;
            float v = Ui.approach(slide[i], target, 12f, dt);
            slide[i] = v;
            if (v > 0.01f) {
                measureRow(i, m);
                order[n++] = i;
                rowsW = Math.max(rowsW, widths[i] + 14f);
                rowsH += 15f * v + 1f;
            }
        }
        // Longest first. Insertion sort on cached widths: n is tiny and nearly sorted
        // frame to frame, and it allocates nothing.
        for (int a = 1; a < n; a++) {
            int idx = order[a];
            int b = a - 1;
            while (b >= 0 && widths[order[b]] < widths[idx]) {
                order[b + 1] = order[b];
                b--;
            }
            order[b + 1] = idx;
        }
        drawCount = n;

        float w = rowsW;
        float h = rowsH;
        if (watermark.get()) {
            if (watermarkTextW < 0f) {
                watermarkTextW = Ui.textWidth("Zephyr", 10f, true);
            }
            w = Math.max(w, 8 + 14 + 6 + watermarkTextW + 10);
            h += WATERMARK_H + 6f;
        }
        size[0] = w;
        size[1] = h;
        return size;
    }

    @Override
    protected void renderContent(DrawContext ctx, float x, float y, float alpha) {
        List<Module> all = ZephyrClient.modules().all();
        float ly = y;
        if (watermark.get()) {
            float w = 8 + 14 + 6 + watermarkTextW + 10;
            card(ctx, x, ly, w, WATERMARK_H, 11f, alpha);
            Ui.logo(ctx, x + 6, ly + 4, 14, Ui.alpha(accent(), alpha));
            drawText(ctx, "Zephyr", x + 8 + 14 + 6, ly + 7, 10f, text(), true, alpha);
            ly += 28f;
        }
        for (int k = 0; k < drawCount; k++) {
            int i = order[k];
            Module m = all.get(i);
            float v = slide[i];
            String info = infoOf[i];
            float w = widths[i] + 14f;
            float rx = x - (1f - v) * (w + 8f);
            float a = alpha * v;
            card(ctx, rx, ly, w, ROW_H, radius(), a);
            Ui.roundRect(ctx, rx, ly + 3f, 2f, 8f, 1f, Ui.alpha(accent(), a));
            float tx = drawText(ctx, m.name(), rx + 7f, ly + 3.5f, ROW_SIZE, text(), false, a);
            if (!info.isEmpty()) {
                drawText(ctx, info, rx + 7f + tx + 4f, ly + 3.5f, ROW_SIZE, textFaint(), false, a);
            }
            ly += 15f * v + 1f;
        }
    }

    private void ensureCapacity(int count) {
        if (slide.length != count) {
            slide = Arrays.copyOf(slide, count);
            widths = new float[count];
            labelSeen = new String[count];
            infoOf = new String[count];
            order = new int[count];
        }
    }

    /**
     * Width + info suffix for module {@code i}. hudLabelCached() returns the same String
     * instance for a whole tick, so the substring + text measurement run only when the
     * label actually changes (at most 20 times a second), not every frame.
     */
    private void measureRow(int i, Module m) {
        String full = m.hudLabelCached() == null ? "" : m.hudLabelCached();
        if (full == labelSeen[i]) {
            return;
        }
        labelSeen[i] = full;
        String info = "";
        if (infoFlagSeen) {
            int cut = full.indexOf(" · ");
            info = cut < 0 ? "" : full.substring(cut + 3);
        }
        infoOf[i] = info;
        widths[i] = Ui.textWidth(m.name(), ROW_SIZE, false)
                + (info.isEmpty() ? 0 : 4 + Ui.textWidth(info, ROW_SIZE, false));
    }

    @Override
    public String hudLabel() {
        return name;
    }
}
