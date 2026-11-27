package dev.zephyr.modules.hud;

import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.hud.HudModule;
import dev.zephyr.core.settings.BooleanSetting;
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
 * and the jump bar. Keys light up in the accent colour while held. Purely visual; it only
 * reads key state.
 *
 * <p>CPS counts press edges seen each frame over a sliding one-second window.
 */
public final class Keystrokes extends HudModule {

    private static final float K = 22f;
    private static final float GAP = 2f;
    private static final float KEY_SIZE = 8f;
    private static final float JUMP_H = 10f;

    private final BooleanSetting mouse = bool("mouse", "Mouse buttons + CPS", true);
    private final BooleanSetting jump = bool("jump", "Jump bar", true);

    private final ArrayDeque<Long> leftClicks = new ArrayDeque<>();
    private final ArrayDeque<Long> rightClicks = new ArrayDeque<>();
    private boolean leftWas;
    private boolean rightWas;
    private final float[] glow = new float[7];
    private final float[] size = new float[2];
    private long lastNanos;

    // Key labels are read from the bindings once per tick, not per frame.
    private String forwardLabel = "";
    private String leftLabel = "";
    private String backLabel = "";
    private String rightLabel = "";

    // CPS strings are rebuilt only when the click count changes.
    private int lmbShown = -1;
    private int rmbShown = -1;
    private String lmbText = "";
    private String rmbText = "";

    public Keystrokes() {
        super("Keystrokes", ModuleCategory.RENDER, 100, 100, "BOTTOM_RIGHT");
    }

    @Override
    public void onTick(MinecraftClient client) {
        GameOptions o = client.options;
        forwardLabel = label(o.forwardKey);
        leftLabel = label(o.leftKey);
        backLabel = label(o.backKey);
        rightLabel = label(o.rightKey);
    }

    @Override
    protected boolean drawsPanel() {
        return false; // every key is its own card
    }

    @Override
    protected float[] measure() {
        float gridH = K * 2 + GAP
                + (mouse.get() ? GAP + K : 0f)
                + (jump.get() ? GAP + JUMP_H : 0f);
        size[0] = K * 3 + GAP * 2;
        size[1] = gridH;
        return size;
    }

    @Override
    protected void renderContent(DrawContext ctx, float x, float y, float alpha) {
        MinecraftClient client = MinecraftClient.getInstance();
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

        float gridW = size[0];
        float y0 = y;
        float ly = y0;
        key(ctx, 0, forwardLabel, x + K + GAP, ly, K, K, o.forwardKey.isPressed(), dt, alpha);
        ly += K + GAP;
        key(ctx, 1, leftLabel, x, ly, K, K, o.leftKey.isPressed(), dt, alpha);
        key(ctx, 2, backLabel, x + K + GAP, ly, K, K, o.backKey.isPressed(), dt, alpha);
        key(ctx, 3, rightLabel, x + (K + GAP) * 2, ly, K, K, o.rightKey.isPressed(), dt, alpha);
        ly += K + GAP;
        if (mouse.get()) {
            float mw = (gridW - GAP) / 2f;
            int lc = leftClicks.size();
            if (lc != lmbShown) {
                lmbShown = lc;
                lmbText = "LMB  " + lc;
            }
            int rc = rightClicks.size();
            if (rc != rmbShown) {
                rmbShown = rc;
                rmbText = "RMB  " + rc;
            }
            key(ctx, 4, lmbText, x, ly, mw, K, left, dt, alpha);
            key(ctx, 5, rmbText, x + mw + GAP, ly, mw, K, right, dt, alpha);
            ly += K + GAP;
        }
        if (jump.get()) {
            float a = glow[6] = Ui.approach(glow[6], o.jumpKey.isPressed() ? 1f : 0f, 20f, dt);
            card(ctx, x, ly, gridW, JUMP_H, radius(), alpha);
            if (a > 0.01f) {
                Ui.roundRect(ctx, x, ly, gridW, JUMP_H, radius(), Ui.alpha(accent(), 0.85f * a * alpha));
            }
            Ui.roundRect(ctx, x + gridW / 2f - 14f, ly + 4f, 28f, 2f, 1f,
                    Ui.alpha(Ui.mix(textDim(), 0xFF101319, a), alpha));
        }
    }

    /** One keystroke tile: a card that fills with the accent colour while held. */
    private void key(DrawContext ctx, int idx, String textValue, float x, float y, float w, float h,
                     boolean down, float dt, float alpha) {
        float a = glow[idx] = Ui.approach(glow[idx], down ? 1f : 0f, 20f, dt);
        card(ctx, x, y, w, h, radius(), alpha);
        if (a > 0.01f) {
            Ui.roundRect(ctx, x, y, w, h, radius(), Ui.alpha(accent(), 0.85f * a * alpha));
        }
        drawTextCentered(ctx, textValue, x + w / 2f, y + h / 2f - 4f, KEY_SIZE,
                Ui.mix(text(), 0xFF101319, a), true, alpha);
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
