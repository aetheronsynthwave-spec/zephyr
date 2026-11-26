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

    private final Map<Module, Float> slide = new HashMap<>();
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

        List<Module> list = new ArrayList<>();
        for (Module m : ZephyrClient.modules().all()) {
            float target = m.isEnabled() && m != this ? 1f : 0f;
            float v = Ui.approach(slide.getOrDefault(m, 0f), target, 12f, dt);
            slide.put(m, v);
            if (v > 0.01f) {
                list.add(m);
            }
        }
        list.sort((a, b) -> Float.compare(width(b), width(a)));
        for (Module m : list) {
            float v = slide.get(m);
            String label = m.name();
            String info = info(m);
            float w = width(m) + 14;
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

    private String info(Module m) {
        if (!showInfo.get()) {
            return "";
        }
        String full = m.hudLabelCached() == null ? "" : m.hudLabelCached();
        int i = full.indexOf(" · ");
        return i < 0 ? "" : full.substring(i + 3);
    }

    private float width(Module m) {
        String info = info(m);
        return Ui.textWidth(m.name(), 8.5f, false) + (info.isEmpty() ? 0 : 4 + Ui.textWidth(info, 8.5f, false));
    }

    @Override
    public String hudLabel() {
        return name;
    }
}
