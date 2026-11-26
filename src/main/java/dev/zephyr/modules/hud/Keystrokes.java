package dev.zephyr.modules.hud;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.gui.render.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Keystrokes - the classic streaming overlay in the bottom-right corner: forward/left/
 * back/right (labelled with your actual binds), both mouse buttons with clicks-per-second,
 * and the jump bar. Keys light up while held. Purely visual; it only reads key state.
 *
 * <p>CPS counts press edges seen each frame over a sliding one-second window.
 */
public final class Keystrokes extends Module {

    private final BooleanSetting mouse = bool("mouse", "Mouse buttons + CPS", true);
    private final BooleanSetting jump = bool("jump", "Jump bar", true);
    private final NumberSetting scale = num("scale", "Scale", 0.6, 2.0, 0.05, 1.0);

    private final ArrayDeque<Long> leftClicks = new ArrayDeque<>();
    private final ArrayDeque<Long> rightClicks = new ArrayDeque<>();
    private boolean leftWas;
    private boolean rightWas;
    private final float[] glow = new float[7];
    private long lastNanos;

    public Keystrokes() {
        super("Keystrokes", ModuleCategory.RENDER, 0);
    }

    @Override
    public void onHudRender(DrawContext ctx, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) {
            return;
        }
        GameOptions o = client.options;
        long now = System.currentTimeMillis();
        long nanos = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (nanos - lastNanos) / 1.0e9f);
        lastNanos = nanos;

        boolean left = o.attackKey.isPressed();
        boolean right = o.useKey.isPressed();
        if (left && !leftWas && client.currentScreen == null) {
            leftClicks.addLast(now);
        }
        if (right && !rightWas && client.currentScreen == null) {
            rightClicks.addLast(now);
        }
        leftWas = left;
        rightWas = right;
        trim(leftClicks, now);
        trim(rightClicks, now);

        float s = scale.get().floatValue();
        float sw = client.getWindow().getScaledWidth() / s;
        float sh = client.getWindow().getScaledHeight() / s;
        final float k = 22, gap = 2;
        float gridW = k * 3 + gap * 2;
        float gridH = k * 2 + gap
                + (mouse.get() ? gap + k : 0)
                + (jump.get() ? gap + 10 : 0);
        float x0 = sw - gridW - 6;
        float y0 = sh - gridH - 6;

        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().scale(s, s);
        Ui.beginFrame(s);
        float y = y0;
        key(ctx, 0, label(o.forwardKey), x0 + k + gap, y, k, k, o.forwardKey.isPressed(), dt);
        y += k + gap;
        key(ctx, 1, label(o.leftKey), x0, y, k, k, o.leftKey.isPressed(), dt);
        key(ctx, 2, label(o.backKey), x0 + k + gap, y, k, k, o.backKey.isPressed(), dt);
        key(ctx, 3, label(o.rightKey), x0 + (k + gap) * 2, y, k, k, o.rightKey.isPressed(), dt);
        y += k + gap;
        if (mouse.get()) {
            float mw = (gridW - gap) / 2f;
            key(ctx, 4, "LMB  " + leftClicks.size(), x0, y, mw, k, left, dt);
            key(ctx, 5, "RMB  " + rightClicks.size(), x0 + mw + gap, y, mw, k, right, dt);
            y += k + gap;
        }
        if (jump.get()) {
            float a = glow[6] = Ui.approach(glow[6], o.jumpKey.isPressed() ? 1f : 0f, 20f, dt);
            Ui.roundRect(ctx, x0, y, gridW, 10, 4, Ui.mix(0xB0101319, 0xE0FFFFFF, a));
            Ui.roundRect(ctx, x0 + gridW / 2f - 14, y + 4, 28, 2, 1, Ui.mix(Ui.TEXT_DIM, 0xFF101319, a));
        }
        ctx.getMatrices().popMatrix();
    }

    private void key(DrawContext ctx, int idx, String text, float x, float y, float w, float h,
                     boolean down, float dt) {
        float a = glow[idx] = Ui.approach(glow[idx], down ? 1f : 0f, 20f, dt);
        Ui.roundRect(ctx, x, y, w, h, 4, Ui.mix(0xB0101319, 0xE0FFFFFF, a));
        Ui.textCentered(ctx, text, x + w / 2f, y + h / 2f - 4f, 8f, Ui.mix(Ui.TEXT, 0xFF101319, a), true);
    }

    private static String label(KeyBinding binding) {
        String t = binding.getBoundKeyLocalizedText().getString().toUpperCase(Locale.ROOT);
        return t.length() > 3 ? t.substring(0, 3) : t;
    }

    private static void trim(ArrayDeque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > 1000) {
            q.pollFirst();
        }
    }
}
