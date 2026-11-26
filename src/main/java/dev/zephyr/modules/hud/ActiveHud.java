package dev.zephyr.modules.hud;

import dev.zephyr.ZephyrClient;
import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.gui.render.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HUD - the Zephyr watermark (logo + name) and a smooth list of active modules, top-left,
 * sorted longest-first, with each entry sliding/fading in and out when toggled.
 */
public final class ActiveHud extends Module {

    private final BooleanSetting watermark = bool("watermark", "Logo watermark", true);
    private final BooleanSetting showInfo = bool("info", "Show module info", true);
    private final NumberSetting scale = num("scale", "Scale", 0.6, 2.0, 0.05, 1.0);

    /*
     * Per-module state indexed by position in ModuleManager.all() (stable after init).
     * The old version boxed a Float per module per frame in a HashMap and re-measured
     * text inside the sort comparator (O(n log n) textWidth + substring per frame).
     */
    private float[] slide = new float[0];
    private float[] widths = new float[0];
    private String[] labelSeen = new String[0];
    private String[] infoOf = new String[0];
    private boolean infoFlagSeen;
    private int[] order = new int[0];
    private long lastNanos;

    public ActiveHud() {
        super("HUD", ModuleCategory.RENDER, 0);
    }

    @Override
    public void onHudRender(DrawContext ctx, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) {
            return;
        }
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1.0e9f);
        lastNanos = now;

        float s = scale.get().floatValue();
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().scale(s, s);
        Ui.beginFrame(s);

        float x = 6, y = 6;
        if (watermark.get()) {
            float tw = Ui.textWidth("Zephyr", 10f, true);
            float w = 8 + 14 + 6 + tw + 10;
            Ui.shadow(ctx, x, y, w, 22, 11, 6, 0x66000000);
            Ui.pill(ctx, x, y, w, 22, Ui.BG_2);
            Ui.logo(ctx, x + 6, y + 4, 14, Ui.ACCENT);
            Ui.text(ctx, "Zephyr", x + 8 + 14 + 6, y + 7, 10f, Ui.TEXT, true);
            y += 28;
        }

        List<Module> all = ZephyrClient.modules().all();
        ensureCapacity(all.size());
        boolean showInfoNow = showInfo.get();
        if (showInfoNow != infoFlagSeen) {
            java.util.Arrays.fill(labelSeen, null); // info toggled: re-measure everything
            infoFlagSeen = showInfoNow;
        }
        int n = 0;
        for (int i = 0; i < all.size(); i++) {
            Module m = all.get(i);
            float target = m.isEnabled() && m != this ? 1f : 0f;
            float v = Ui.approach(slide[i], target, 12f, dt);
            slide[i] = v;
            if (v > 0.01f) {
                measure(i, m);
                order[n++] = i;
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
        for (int k = 0; k < n; k++) {
            int i = order[k];
            Module m = all.get(i);
            float v = slide[i];
            String label = m.name();
            String info = infoOf[i];
            float w = widths[i] + 14;
            float rx = x - (1f - v) * (w + 8);
            Ui.roundRect(ctx, rx, y, w, 14, 4, Ui.alpha(0xB0101319, v));
            Ui.roundRect(ctx, rx, y + 3, 2, 8, 1, Ui.alpha(Ui.ACCENT, v));
            float tx = Ui.text(ctx, label, rx + 7, y + 3.5f, 8.5f, Ui.alpha(Ui.TEXT, v), false);
            if (!info.isEmpty()) {
                Ui.text(ctx, info, rx + 7 + tx + 4, y + 3.5f, 8.5f, Ui.alpha(Ui.TEXT_FAINT, v), false);
            }
            y += 15 * v + 1;
        }
        ctx.getMatrices().popMatrix();
    }

    private void ensureCapacity(int size) {
        if (slide.length != size) {
            slide = java.util.Arrays.copyOf(slide, size);
            widths = new float[size];
            labelSeen = new String[size];
            infoOf = new String[size];
            order = new int[size];
        }
    }

    /**
     * Width + info suffix for module {@code i}. hudLabelCached() returns the same String
     * instance for a whole tick, so the substring + text measurement run only when the
     * label actually changes (at most 20 times a second), not every frame.
     */
    private void measure(int i, Module m) {
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
        widths[i] = Ui.textWidth(m.name(), 8.5f, false)
                + (info.isEmpty() ? 0 : 4 + Ui.textWidth(info, 8.5f, false));
    }

    @Override
    public String hudLabel() {
        return name;
    }
}
