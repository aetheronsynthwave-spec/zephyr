package dev.zephyr.core.hud;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.ModeSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.gui.render.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/**
 * Base class of every on-screen HUD element. It owns the shared look and placement, so all
 * HUDs match the ClickGui and can be recoloured and moved the same way:
 *
 * <ul>
 *   <li><b>Placement:</b> the point (xPct, yPct) of the screen is the element's anchor
 *       ({@code anchor} says which corner or the centre of the element sits there). The
 *       element is always clamped fully on-screen.</li>
 *   <li><b>Look:</b> {@code scale}, {@code opacity}, {@code bgAlpha}, {@code radius},
 *       {@code shadow}, {@code accentBar}, {@code padding}
 *       and the text colour ({@code textBrightness},
 *       {@code textShadow}).</li>
 * </ul>
 *
 * <p>Subclasses implement {@link #measure()} (content size in unscaled units, cheap, called
 * once per frame) and {@link #renderContent} (draws at local coordinates). Colours must come
 * from {@link #accent()}, {@link #text()}, {@link #textDim()} and {@link #textFaint()} so the
 * user's settings apply everywhere.
 */
public abstract class HudModule extends Module {

    private static final List<String> ANCHORS =
            List.of("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT", "CENTER");

    /** True while the HUD editor screen is open: empty elements show a placeholder box. */
    private static boolean editing;

    public static boolean isEditing() {
        return editing;
    }

    public static void setEditing(boolean on) {
        editing = on;
    }

    private final NumberSetting xPct;
    private final NumberSetting yPct;
    private final ModeSetting anchorMode;
    private final NumberSetting scaleSetting;
    private final NumberSetting opacitySetting;
    private final NumberSetting bgSetting;
    private final NumberSetting radiusSetting;
    private final BooleanSetting shadowSetting;
    private final BooleanSetting accentBarSetting;
    private final NumberSetting brightnessSetting;
    private final BooleanSetting textShadowSetting;
    private final NumberSetting paddingSetting;


    private boolean boundsValid;
    private float bx;
    private float by;
    private float bw;
    private float bh;
    private float screenW;
    private float screenH;

    /**
     * @param title         module name (also the editor label)
     * @param defaultX      default anchor point, percent of screen width
     * @param defaultY      default anchor point, percent of screen height
     * @param defaultAnchor default {@code anchor} value (TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT,
     *                      BOTTOM_RIGHT or CENTER)
     */
    protected HudModule(String title, ModuleCategory category, double defaultX, double defaultY,
                        String defaultAnchor) {
        super(title, category, 0);
        xPct = num("xPct", "X position %", 0, 100, 0.1, defaultX);
        yPct = num("yPct", "Y position %", 0, 100, 0.1, defaultY);
        anchorMode = s(new ModeSetting("anchor", "Anchor", ANCHORS, defaultAnchor));
        scaleSetting = num("scale", "Scale", 0.5, 3.0, 0.05, 1.0);
        opacitySetting = num("opacity", "Opacity", 0, 1, 0.05, 1.0);
        bgSetting = num("bgAlpha", "Background opacity", 0, 1, 0.05, 0.75);
        radiusSetting = num("radius", "Corner radius", 0, 12, 0.5, 6);
        shadowSetting = bool("shadow", "Shadow", true);
        accentBarSetting = bool("accentBar", "Accent bar", false);
        brightnessSetting = num("textBrightness", "Text brightness", 0.5, 1.0, 0.05, 1.0);
        textShadowSetting = bool("textShadow", "Text shadow", false);
        paddingSetting = num("padding", "Padding", 2, 12, 1, 6);
    }

    // ------------------------------------------------------------------
    // Subclass contract
    // ------------------------------------------------------------------

    /**
     * Content size in unscaled GUI units, excluding padding. Called once per frame before
     * {@link #renderContent}; it may update per-frame state (animations). Return {0, 0} to
     * hide the element this frame. Must not return null.
     */
    protected abstract float[] measure();

    /**
     * Draws the content with its top-left at local (x, y), which already includes the
     * padding. {@code alpha} is the overall opacity (0..1) to apply to every colour.
     */
    protected abstract void renderContent(DrawContext ctx, float x, float y, float alpha);

    /** Whether the shared background panel is drawn behind the content. */
    protected boolean drawsPanel() {
        return true;
    }

    /** Extra opacity multiplier (for elements with their own fade in/out). */
    protected float alphaMul() {
        return 1f;
    }

    /** Extra vertical offset in scaled GUI px (for elements with a slide animation). */
    protected float offsetY() {
        return 0f;
    }

    /** The shared background panel: shadow, rounded box, optional accent bar. */
    protected void drawPanel(DrawContext ctx, float w, float h, float alpha) {
        card(ctx, 0f, 0f, w, h, radius(), alpha);
        if (accentBarSetting.get()) {
            Ui.roundRect(ctx, 3f, 4f, 2f, Math.max(0f, h - 8f), 1f, Ui.alpha(accent(), alpha));
        }
    }

    // ------------------------------------------------------------------
    // Shared drawing helpers
    // ------------------------------------------------------------------

    /** Rounded background box in the shared style (shadow optional). Local coordinates. */
    protected final void card(DrawContext ctx, float x, float y, float w, float h, float radius, float alpha) {
        if (shadowSetting.get()) {
            Ui.shadow(ctx, x, y, w, h, radius, 6f, Ui.alpha(0xFF000000, 0.55f * alpha));
        }
        Ui.roundRect(ctx, x, y, w, h, radius, Ui.alpha(Ui.BG, bgAlpha() * alpha));
        Ui.outline(ctx, x, y, w, h, radius, Ui.alpha(0x38FFFFFF, bgAlpha() * alpha));
    }

    /** Draws text in the Ui font with the shared shadow option. Returns the advance width. */
    protected final float drawText(DrawContext ctx, String s, float x, float y, float size, int color,
                                   boolean bold, float alpha) {
        if (textShadowSetting.get()) {
            Ui.text(ctx, s, x, y + 1f, size, Ui.alpha(0xFF000000, 0.6f * alpha), bold);
        }
        return Ui.text(ctx, s, x, y, size, Ui.alpha(color, alpha), bold);
    }

    /** As {@link #drawText} with the text's right edge at {@code rightX}. */
    protected final float drawTextRight(DrawContext ctx, String s, float rightX, float y, float size, int color,
                                        boolean bold, float alpha) {
        return drawText(ctx, s, rightX - Ui.textWidth(s, size, bold), y, size, color, bold, alpha);
    }

    /** As {@link #drawText} centred on {@code cx}. */
    protected final float drawTextCentered(DrawContext ctx, String s, float cx, float y, float size, int color,
                                           boolean bold, float alpha) {
        return drawText(ctx, s, cx - Ui.textWidth(s, size, bold) / 2f, y, size, color, bold, alpha);
    }

    /** Accent colour: always white in the black and white theme. */
    public final int accent() {
        return Ui.ACCENT;
    }

    /** Primary text colour (opaque), scaled by textBrightness. */
    protected final int text() {
        return tint(Ui.TEXT);
    }

    /** Secondary text colour (opaque), scaled by textBrightness. */
    protected final int textDim() {
        return tint(Ui.TEXT_DIM);
    }

    /** Tertiary text colour (opaque), scaled by textBrightness. */
    protected final int textFaint() {
        return tint(Ui.TEXT_FAINT);
    }

    /** Corner radius in GUI px. */
    protected final float radius() {
        return (float) radiusSetting.get().doubleValue();
    }

    /** Padding around the content in GUI px. */
    protected final float padding() {
        return (float) paddingSetting.get().doubleValue();
    }

    /** The shared text-shadow option, for elements that draw vanilla text themselves. */
    protected final boolean textShadow() {
        return textShadowSetting.get();
    }

    /** The shared background opacity, 0..1. */
    protected final float bgAlpha() {
        return (float) bgSetting.get().doubleValue();
    }

    /** The element's own scale setting. */
    protected final float hudScale() {
        return (float) scaleSetting.get().doubleValue();
    }

    private int tint(int argb) {
        float k = (float) brightnessSetting.get().doubleValue();
        int r = (int) (((argb >> 16) & 0xFF) * k);
        int g = (int) (((argb >> 8) & 0xFF) * k);
        int b = (int) ((argb & 0xFF) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ------------------------------------------------------------------
    // Layout and rendering
    // ------------------------------------------------------------------

    @Override
    public final void onHudRender(DrawContext ctx, float tickDelta) {
        boundsValid = false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || (mc.options.hudHidden && !editing)) {
            return;
        }
        screenW = ctx.getScaledWindowWidth();
        screenH = ctx.getScaledWindowHeight();

        float[] content = measure();
        float cw = content[0];
        float ch = content[1];
        boolean placeholder = false;
        if (cw <= 0f || ch <= 0f) {
            if (!editing) {
                return;
            }
            // The HUD editor shows an empty element as a labelled box so it can be placed.
            placeholder = true;
            cw = Ui.textWidth(name(), 8.5f, true) + 4f;
            ch = 12f;
        }

        float pad = padding();
        float s = (float) scaleSetting.get().doubleValue();
        float boxW = cw + pad * 2f;
        float boxH = ch + pad * 2f;
        float w = boxW * s;
        float h = boxH * s;
        float x = clampTo(screenW * (float) xPct.get().doubleValue() / 100f - anchorX() * w, screenW - w);
        float y = clampTo(screenH * (float) yPct.get().doubleValue() / 100f - anchorY() * h, screenH - h)
                + offsetY();
        bx = x;
        by = y;
        bw = w;
        bh = h;
        boundsValid = true;

        float alpha = (float) opacitySetting.get().doubleValue() * alphaMul();
        if (alpha <= 0.01f) {
            return;
        }
        ctx.getMatrices().pushMatrix();
        try {
            ctx.getMatrices().translate(x, y);
            ctx.getMatrices().scale(s, s);
            Ui.beginFrame(s);
            if (placeholder || drawsPanel()) {
                drawPanel(ctx, boxW, boxH, alpha);
            }
            if (placeholder) {
                drawText(ctx, name(), pad, pad + 1.5f, 8.5f, textDim(), true, alpha);
            } else {
                renderContent(ctx, pad, pad, alpha);
            }
        } finally {
            ctx.getMatrices().popMatrix();
        }
    }

    private float anchorX() {
        String a = anchorMode.get();
        return a.endsWith("RIGHT") ? 1f : a.equals("CENTER") ? 0.5f : 0f;
    }

    private float anchorY() {
        String a = anchorMode.get();
        return a.startsWith("BOTTOM") ? 1f : a.equals("CENTER") ? 0.5f : 0f;
    }

    private static float clampTo(float value, float max) {
        return Math.max(0f, Math.min(value, max));
    }

    // ------------------------------------------------------------------
    // Editor support
    // ------------------------------------------------------------------

    /**
     * Last frame's box in scaled GUI px: {x, y, w, h}, or null if the element was not
     * placed last frame (no content, HUD hidden, no world).
     */
    public final float[] screenBounds() {
        return boundsValid ? new float[]{bx, by, bw, bh} : null;
    }

    /**
     * Moves the element so its top-left corner is at ({@code screenX}, {@code screenY}) in
     * scaled GUI px, storing the position as xPct/yPct for the current anchor.
     */
    public final void moveTo(float screenX, float screenY) {
        if (!boundsValid || screenW <= 0f || screenH <= 0f) {
            return;
        }
        float anchorPx = screenX + anchorX() * bw;
        float anchorPy = screenY - offsetY() + anchorY() * bh; // offsetY is a transient slide
        xPct.set((double) (anchorPx / screenW * 100f));
        yPct.set((double) (anchorPy / screenH * 100f));
    }

    /** Changes the scale by {@code delta} in 0.05 steps (clamped by the setting). */
    public final void adjustScale(double delta) {
        scaleSetting.set(Math.round((scaleSetting.get() + delta) * 20.0) / 20.0);
    }
}
