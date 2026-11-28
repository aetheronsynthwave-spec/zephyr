package dev.zephyr.gui.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * Zephyr's smooth 2D toolkit: anti-aliased rounded rectangles, pills, circles, soft
 * shadows, the logo and a crisp proportional font.
 *
 * <h2>How it stays smooth AND cheap</h2>
 * <ul>
 *   <li>Every curve and glyph is rasterised ONCE with Java2D anti-aliasing into a
 *       texture sampled with linear filtering ({@link SmoothTexture}).</li>
 *   <li>Each asset exists in a few pixel-size buckets; the bucket closest to (and not
 *       below) the real on-screen pixel size is used, so nothing is magnified into blur
 *       or shrunk into shimmer, whatever the GUI scale or menu size.</li>
 *   <li>Drawing is plain textured quads + solid fills (vanilla GUI pipelines, batched
 *       by the game), positioned at quarter-pixel precision via a 1/4 matrix scale.</li>
 * </ul>
 * Assets are built lazily on first use (a few ms, once); a frame costs about the same
 * as drawing vanilla buttons.
 */
public final class Ui {

    private Ui() {
    }

    /** Sub-pixel precision: geometry is placed in 1/Q gui-pixel steps. */
    private static final int Q = 4;

    /** On-screen physical pixels per gui unit for the current frame. */
    private static float pixelScale = 2f;

    /** Call once per frame with the extra matrix scale the caller draws under (1 if none). */
    public static void beginFrame(float extraScale) {
        pixelScale = (float) MinecraftClient.getInstance().getWindow().getScaleFactor() * extraScale;
    }

    // ------------------------------------------------------------------
    // Colours
    // ------------------------------------------------------------------

    public static final int ACCENT = 0xFFFFFFFF;
    public static final int ACCENT_2 = 0xFFC4C4C4;
    public static final int BG = 0xE6080808;
    public static final int BG_2 = 0xF0111111;
    public static final int ROW = 0xFF1A1A1A;
    public static final int ROW_HOVER = 0xFF262626;
    public static final int TEXT = 0xFFF5F5F5;
    public static final int TEXT_DIM = 0xFF9E9E9E;
    public static final int TEXT_FAINT = 0xFF666666;

    public static int alpha(int argb, float a) {
        int base = (argb >>> 24) & 0xFF;
        return ((int) (base * Math.max(0f, Math.min(1f, a))) << 24) | (argb & 0xFFFFFF);
    }

    public static int mix(int c1, int c2, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = (int) (((c1 >>> 24) & 0xFF) + ((((c2 >>> 24) & 0xFF) - ((c1 >>> 24) & 0xFF)) * t));
        int r = (int) (((c1 >>> 16) & 0xFF) + ((((c2 >>> 16) & 0xFF) - ((c1 >>> 16) & 0xFF)) * t));
        int g = (int) (((c1 >>> 8) & 0xFF) + ((((c2 >>> 8) & 0xFF) - ((c1 >>> 8) & 0xFF)) * t));
        int b = (int) ((c1 & 0xFF) + (((c2 & 0xFF) - (c1 & 0xFF)) * t));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Frame-rate independent exponential approach (for hover/toggle animations). */
    public static float approach(float current, float target, float speedPerSecond, float dtSeconds) {
        return current + (target - current) * (1f - (float) Math.exp(-speedPerSecond * dtSeconds));
    }

    // ------------------------------------------------------------------
    // Low level: sub-pixel quads
    // ------------------------------------------------------------------

    private static void push(DrawContext ctx) {
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().scale(1f / Q, 1f / Q);
    }

    private static void pop(DrawContext ctx) {
        ctx.getMatrices().popMatrix();
    }

    private static int q(float v) {
        return Math.round(v * Q);
    }

    /** Solid rectangle at fractional coordinates. */
    public static void rect(DrawContext ctx, float x, float y, float w, float h, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        push(ctx);
        ctx.fill(q(x), q(y), q(x + w), q(y + h), color);
        pop(ctx);
    }

    private static void tex(DrawContext ctx, Identifier id, float x, float y, float w, float h,
                            float u, float v, int rw, int rh, int tw, int th, int color) {
        int x0 = q(x), y0 = q(y), x1 = q(x + w), y1 = q(y + h);
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, id, x0, y0, u, v, x1 - x0, y1 - y0, rw, rh, tw, th, color);
    }

    // ------------------------------------------------------------------
    // Shapes
    // ------------------------------------------------------------------

    private static final int[] SHAPE_BUCKETS = {8, 16, 32, 64, 128};
    private static final Map<Integer, Identifier> CIRCLES = new HashMap<>();
    private static final Map<Integer, Identifier> GLOWS = new HashMap<>();

    private static int bucket(int[] buckets, float px) {
        for (int b : buckets) {
            if (b >= px) {
                return b;
            }
        }
        return buckets[buckets.length - 1];
    }

    /** Anti-aliased filled circle of diameter {@code d} px (white mask). */
    private static final Identifier[] CIRCLE_BY_BUCKET = new Identifier[129];
    private static final Identifier[] GLOW_BY_BUCKET = new Identifier[129];

    private static Identifier circle(int d) {
        Identifier hit = CIRCLE_BY_BUCKET[d];
        if (hit != null) {
            return hit;
        }
        return CIRCLE_BY_BUCKET[d] = circleBuild(d);
    }

    private static Identifier glow(int d) {
        Identifier hit = GLOW_BY_BUCKET[d];
        if (hit != null) {
            return hit;
        }
        return GLOW_BY_BUCKET[d] = glowBuild(d);
    }

    private static Identifier circleBuild(int d) {
        return CIRCLES.computeIfAbsent(d, size -> {
            int s = size * 2; // diameter in texels; radius == bucket
            BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            hints(g);
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Float(0, 0, s, s));
            g.dispose();
            SmoothTexture.whiten(img);
            return SmoothTexture.register("ui/circle_" + size, img);
        });
    }

    /** Radial soft falloff (for shadows/glows): alpha 1 at the centre to 0 at the rim. */
    private static Identifier glowBuild(int d) {
        return GLOWS.computeIfAbsent(d, size -> {
            int s = size * 2;
            BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            float c = s / 2f;
            for (int y = 0; y < s; y++) {
                for (int x = 0; x < s; x++) {
                    float dist = (float) Math.hypot(x + 0.5f - c, y + 0.5f - c) / c;
                    float a = dist >= 1f ? 0f : (1f - dist);
                    a = a * a * (3f - 2f * a); // smoothstep falloff
                    img.setRGB(x, y, ((int) (a * 255) << 24) | 0xFFFFFF);
                }
            }
            return SmoothTexture.register("ui/glow_" + size, img);
        });
    }

    /** Draws a 9-slice rounded box from a radial mask texture (corners from the mask, rest solid). */
    private static void nineSlice(DrawContext ctx, Identifier id, int texRadius,
                                  float x, float y, float w, float h, float r, int color) {
        int ts = texRadius * 2;
        push(ctx);
        // corners
        tex(ctx, id, x, y, r, r, 0, 0, texRadius, texRadius, ts, ts, color);
        tex(ctx, id, x + w - r, y, r, r, texRadius, 0, texRadius, texRadius, ts, ts, color);
        tex(ctx, id, x, y + h - r, r, r, 0, texRadius, texRadius, texRadius, ts, ts, color);
        tex(ctx, id, x + w - r, y + h - r, r, r, texRadius, texRadius, texRadius, texRadius, ts, ts, color);
        // edges + centre: stretch the 1-texel centre lines of the mask
        tex(ctx, id, x + r, y, w - 2 * r, r, texRadius - 0.5f, 0, 1, texRadius, ts, ts, color);
        tex(ctx, id, x + r, y + h - r, w - 2 * r, r, texRadius - 0.5f, texRadius, 1, texRadius, ts, ts, color);
        tex(ctx, id, x, y + r, r, h - 2 * r, 0, texRadius - 0.5f, texRadius, 1, ts, ts, color);
        tex(ctx, id, x + w - r, y + r, r, h - 2 * r, texRadius, texRadius - 0.5f, texRadius, 1, ts, ts, color);
        tex(ctx, id, x + r, y + r, w - 2 * r, h - 2 * r, texRadius - 0.5f, texRadius - 0.5f, 1, 1, ts, ts, color);
        pop(ctx);
    }

    /** Anti-aliased rounded rectangle. */
    public static void roundRect(DrawContext ctx, float x, float y, float w, float h, float radius, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        float r = Math.max(0.5f, Math.min(radius, Math.min(w, h) / 2f));
        int b = bucket(SHAPE_BUCKETS, r * pixelScale);
        nineSlice(ctx, circle(b), b, x, y, w, h, r, color);
    }

    /** Fully rounded pill. */
    public static void pill(DrawContext ctx, float x, float y, float w, float h, int color) {
        roundRect(ctx, x, y, w, h, h / 2f, color);
    }

    public static void circle(DrawContext ctx, float cx, float cy, float radius, int color) {
        roundRect(ctx, cx - radius, cy - radius, radius * 2, radius * 2, radius, color);
    }

    /** Soft drop shadow / glow around a rounded box ({@code spread} gui px of falloff). */
    public static void shadow(DrawContext ctx, float x, float y, float w, float h, float radius,
                              float spread, int color) {
        float r = radius + spread;
        int b = bucket(SHAPE_BUCKETS, r * pixelScale);
        nineSlice(ctx, glow(b), b, x - spread, y - spread, w + spread * 2, h + spread * 2, r, color);
    }

    /** Vertical outline-only rounded rect: a thin ring made of two rounded rects. */
    public static void outline(DrawContext ctx, float x, float y, float w, float h, float radius,
                               float thickness, int color, int inner) {
        roundRect(ctx, x, y, w, h, radius, color);
        roundRect(ctx, x + thickness, y + thickness, w - thickness * 2, h - thickness * 2,
                Math.max(0.5f, radius - thickness), inner);
    }

    // ------------------------------------------------------------------
    // Logo
    // ------------------------------------------------------------------

    private static final int[] LOGO_BUCKETS = {32, 64, 128, 256};
    private static final Map<Integer, Identifier> LOGOS = new HashMap<>();

    /** The Zephyr mark: a sharp brush-cut "Z" with bladed tips (white mask, tinted at draw time). */
    private static final float[][] MARK = {
        {2, 30}, {26, 10}, {98, 8}, {42, 72}, {70, 72}, {98, 66}, {76, 90}, {2, 92}, {58, 28}, {34, 28}
    };

    private static Identifier logoTexture(int size) {
        return LOGOS.computeIfAbsent(size, s -> {
            BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            hints(g);
            g.setColor(Color.WHITE);
            float u = s / 100f;
            GeneralPath z = new GeneralPath();
            z.moveTo(MARK[0][0] * u, MARK[0][1] * u);
            for (int i = 1; i < MARK.length; i++) {
                z.lineTo(MARK[i][0] * u, MARK[i][1] * u);
            }
            z.closePath();
            g.fill(z);
            g.dispose();
            SmoothTexture.whiten(img);
            return SmoothTexture.register("ui/logo_" + s, img);
        });
    }

    public static void logo(DrawContext ctx, float x, float y, float size, int color) {
        int b = bucket(LOGO_BUCKETS, size * pixelScale);
        push(ctx);
        tex(ctx, logoTexture(b), x, y, size, size, 0, 0, b, b, b, b, color);
        pop(ctx);
    }

    /** Width / height of {@code assets/zephyr/textures/gui/wordmark.png} (see brand/make_brand.py). */
    public static final float WORDMARK_ASPECT = 1180f / 262f;

    private static final int[] WORDMARK_BUCKETS = {48, 64, 96, 131, 262};
    private static final Map<Integer, Identifier> WORDMARKS = new HashMap<>();

    private static Identifier wordmarkTexture(int height) {
        return WORDMARKS.computeIfAbsent(height, h -> {
            int w = Math.round(h * WORDMARK_ASPECT);
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            try (var in = Ui.class.getResourceAsStream("/assets/zephyr/textures/gui/wordmark.png")) {
                Graphics2D g = img.createGraphics();
                g.drawImage(javax.imageio.ImageIO.read(in).getScaledInstance(w, h, java.awt.Image.SCALE_AREA_AVERAGING),
                        0, 0, null);
                g.dispose();
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            SmoothTexture.whiten(img);
            return SmoothTexture.register("ui/wordmark_" + h, img);
        });
    }

    /** The landscape ZEPHYR wordmark, {@code height} tall and {@link #WORDMARK_ASPECT} times as wide. */
    public static void wordmark(DrawContext ctx, float x, float y, float height, int color) {
        int b = bucket(WORDMARK_BUCKETS, height * pixelScale);
        int w = Math.round(b * WORDMARK_ASPECT);
        push(ctx);
        tex(ctx, wordmarkTexture(b), x, y, height * WORDMARK_ASPECT, height, 0, 0, w, b, w, b, color);
        pop(ctx);
    }

    // ------------------------------------------------------------------
    // Surfaces: the one panel style of the whole client
    // ------------------------------------------------------------------

    private static final Map<Integer, Identifier> RINGS = new HashMap<>();

    /** Hairline rounded outline, about {@code 0.8} gui px wide. */
    public static void outline(DrawContext ctx, float x, float y, float w, float h, float radius, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        float r = Math.max(1f, Math.min(radius, Math.min(w, h) / 2f));
        int b = bucket(SHAPE_BUCKETS, r * pixelScale);
        int stroke = Math.max(1, Math.min(b, Math.round(0.8f / r * b)));
        Identifier ring = RINGS.computeIfAbsent(b * 1000 + stroke, key -> {
            int s = b * 2;
            BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            hints(g);
            java.awt.geom.Area area = new java.awt.geom.Area(new Ellipse2D.Float(0, 0, s, s));
            area.subtract(new java.awt.geom.Area(new Ellipse2D.Float(stroke, stroke, s - 2f * stroke, s - 2f * stroke)));
            g.setColor(Color.WHITE);
            g.fill(area);
            g.dispose();
            SmoothTexture.whiten(img);
            return SmoothTexture.register("ui/ring_" + b + "_" + stroke, img);
        });
        nineSlice(ctx, ring, b, x, y, w, h, r, color);
    }

    /** A flat surface with its hairline edge. */
    public static void surface(DrawContext ctx, float x, float y, float w, float h, float radius, int fill,
                               float alpha) {
        roundRect(ctx, x, y, w, h, radius, alpha(fill, alpha));
        outline(ctx, x, y, w, h, radius, alpha(0x2EFFFFFF, alpha));
    }

    /** A raised surface: soft shadow, fill and edge. */
    public static void panel(DrawContext ctx, float x, float y, float w, float h, float radius, int fill,
                             float alpha) {
        shadow(ctx, x, y, w, h, radius, 12, alpha(0x90000000, alpha));
        surface(ctx, x, y, w, h, radius, fill, alpha);
    }

    // ------------------------------------------------------------------
    // Icons (drawn once as white vector masks, tinted when shown)
    // ------------------------------------------------------------------

    private static final Map<String, Identifier> ICONS = new HashMap<>();

    /** One of: combat, crystal, survival, utility, movement, render, gui. */
    public static void icon(DrawContext ctx, String name, float x, float y, float size, int color) {
        int b = bucket(LOGO_BUCKETS, size * pixelScale * 1.5f);
        Identifier id = ICONS.computeIfAbsent(name + "_" + b, key -> iconTexture(name, b));
        push(ctx);
        tex(ctx, id, x, y, size, size, 0, 0, b, b, b, b, color);
        pop(ctx);
    }

    private static Identifier iconTexture(String name, int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        hints(g);
        g.setColor(Color.WHITE);
        g.scale(size / 100.0, size / 100.0);
        g.setStroke(new java.awt.BasicStroke(9f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        GeneralPath p = new GeneralPath();
        switch (name) {
            case "combat" -> { // sword
                p.moveTo(92, 8);
                p.lineTo(88, 30);
                p.lineTo(46, 72);
                p.lineTo(28, 54);
                p.lineTo(70, 12);
                p.closePath();
                g.fill(p);
                g.draw(new java.awt.geom.Line2D.Float(18, 52, 48, 82));
                g.draw(new java.awt.geom.Line2D.Float(30, 70, 10, 90));
            }
            case "crystal" -> { // faceted gem
                p.moveTo(50, 6);
                p.lineTo(86, 50);
                p.lineTo(50, 94);
                p.lineTo(14, 50);
                p.closePath();
                g.draw(p);
                g.draw(new java.awt.geom.Line2D.Float(14, 50, 86, 50));
                g.draw(new java.awt.geom.Line2D.Float(50, 6, 50, 50));
            }
            case "survival" -> { // heart
                p.moveTo(50, 90);
                p.curveTo(10, 62, 6, 44, 8, 32);
                p.curveTo(12, 8, 42, 8, 50, 30);
                p.curveTo(58, 8, 88, 8, 92, 32);
                p.curveTo(94, 44, 90, 62, 50, 90);
                p.closePath();
                g.fill(p);
            }
            case "utility" -> { // sliders
                for (int i = 0; i < 3; i++) {
                    int row = 20 + i * 30;
                    g.draw(new java.awt.geom.Line2D.Float(10, row, 90, row));
                    int knob = i == 0 ? 64 : i == 1 ? 32 : 56;
                    g.fill(new Ellipse2D.Float(knob - 12, row - 12, 24, 24));
                }
            }
            case "movement" -> { // double chevron
                for (int dx : new int[]{0, 40}) {
                    p.moveTo(14 + dx, 18);
                    p.lineTo(46 + dx, 50);
                    p.lineTo(14 + dx, 82);
                }
                g.draw(p);
            }
            case "render" -> { // eye
                p.moveTo(6, 50);
                p.curveTo(28, 14, 72, 14, 94, 50);
                p.curveTo(72, 86, 28, 86, 6, 50);
                p.closePath();
                g.draw(p);
                g.fill(new Ellipse2D.Float(36, 36, 28, 28));
            }
            case "donut" -> { // ring with sprinkles
                g.setStroke(new java.awt.BasicStroke(24f));
                g.draw(new Ellipse2D.Float(20, 20, 60, 60));
                g.setStroke(new java.awt.BasicStroke(7f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
                g.setComposite(java.awt.AlphaComposite.Clear);
                g.draw(new java.awt.geom.Line2D.Float(36, 20, 44, 14));
                g.draw(new java.awt.geom.Line2D.Float(76, 36, 82, 46));
                g.draw(new java.awt.geom.Line2D.Float(24, 62, 20, 52));
                g.draw(new java.awt.geom.Line2D.Float(60, 82, 70, 76));
                g.setComposite(java.awt.AlphaComposite.SrcOver);
            }
            default -> g.fill(new Ellipse2D.Float(20, 20, 60, 60));
        }
        g.dispose();
        SmoothTexture.whiten(img);
        return SmoothTexture.register("ui/icon_" + name + "_" + size, img);
    }

    // ------------------------------------------------------------------
    // Font
    // ------------------------------------------------------------------

    private static final int[] FONT_BUCKETS = {12, 16, 20, 26, 34, 44, 56};
    private static final Atlas[] ATLAS_REGULAR = new Atlas[FONT_BUCKETS.length];
    private static final Atlas[] ATLAS_BOLD = new Atlas[FONT_BUCKETS.length];
    private static final String CHARS;

    static {
        StringBuilder sb = new StringBuilder();
        for (char c = 32; c < 127; c++) {
            sb.append(c);
        }
        sb.append("·•…°×✓▸←→↑↓");
        CHARS = sb.toString();
    }

    private static final class Atlas {
        Identifier id;
        int texW;
        int texH;
        int px;
        float ascent;
        final Map<Character, float[]> glyphs = new HashMap<>(); // u, v, w, h, advance, xOff
        final float[][] low = new float[256][];

        float[] glyph(char c) {
            return c < 256 ? low[c] : glyphs.get(c);
        }
    }

    private static final Map<Integer, Atlas> ATLASES = new HashMap<>();
    private static String family;

    private static Font baseFont(boolean bold, int px) {
        if (family == null) {
            family = "SansSerif";
            for (String f : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
                if (f.equals("Segoe UI") || f.equals("Inter") || f.equals("Helvetica Neue")) {
                    family = f;
                    if (f.equals("Inter")) {
                        break;
                    }
                }
            }
        }
        return new Font(family, bold ? Font.BOLD : Font.PLAIN, px);
    }

    private static Atlas atlas(boolean bold, int px) {
        int key = (bold ? 1000 : 0) + px;
        return ATLASES.computeIfAbsent(key, k -> {
            Atlas a = new Atlas();
            a.px = px;
            Font font = baseFont(bold, px);
            BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D pg = probe.createGraphics();
            hints(pg);
            pg.setFont(font);
            FontMetrics fm = pg.getFontMetrics();
            pg.dispose();
            int pad = Math.max(2, px / 8);
            int cellH = fm.getAscent() + fm.getDescent() + pad * 2;
            int texW = 1024;
            int x = 0, y = 0;
            for (char c : CHARS.toCharArray()) {
                int cw = fm.charWidth(c) + pad * 2;
                if (x + cw > texW) {
                    x = 0;
                    y += cellH;
                }
                x += cw;
            }
            int texH = Integer.highestOneBit(y + cellH) << 1;
            BufferedImage img = new BufferedImage(texW, texH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            hints(g);
            g.setFont(font);
            g.setColor(Color.WHITE);
            x = 0;
            y = 0;
            for (char c : CHARS.toCharArray()) {
                int adv = fm.charWidth(c);
                int cw = adv + pad * 2;
                if (x + cw > texW) {
                    x = 0;
                    y += cellH;
                }
                g.drawString(String.valueOf(c), x + pad, y + pad + fm.getAscent());
                float[] gl = new float[]{x, y, cw, cellH, adv, pad};
                a.glyphs.put(c, gl);
                if (c < 256) {
                    a.low[c] = gl;
                }
                x += cw;
            }
            g.dispose();
            SmoothTexture.whiten(img);
            a.texW = texW;
            a.texH = texH;
            a.ascent = fm.getAscent() + pad;
            a.id = SmoothTexture.register("ui/font_" + (bold ? "b" : "r") + px, img);
            return a;
        });
    }

    private static Atlas atlasFor(float size, boolean bold) {
        int px = bucket(FONT_BUCKETS, size * pixelScale * 1.25f);
        int slot = 0;
        while (FONT_BUCKETS[slot] != px) {
            slot++;
        }
        Atlas[] row = bold ? ATLAS_BOLD : ATLAS_REGULAR;
        Atlas hit = row[slot];
        return hit != null ? hit : (row[slot] = atlas(bold, px));
    }

    /** Text width in gui units at {@code size} (cap height ~ 0.7 size). */
    public static float textWidth(String s, float size, boolean bold) {
        Atlas a = atlasFor(size, bold);
        float k = size / a.px;
        float w = 0;
        for (int i = 0; i < s.length(); i++) {
            float[] gl = a.glyph(s.charAt(i));
            w += (gl != null ? gl[4] : a.px * 0.5f) * k;
        }
        return w;
    }

    /**
     * Draws {@code s} with its top at {@code y}. {@code size} is the font em size in gui
     * units (8 matches vanilla text height closely).
     */
    public static float text(DrawContext ctx, String s, float x, float y, float size, int color, boolean bold) {
        Atlas a = atlasFor(size, bold);
        float k = size / a.px;
        float cx = x;
        float top = y - (a.ascent - a.px * 0.92f) * k; // align visual top to y
        push(ctx);
        for (int i = 0; i < s.length(); i++) {
            float[] gl = a.glyph(s.charAt(i));
            if (gl == null) {
                cx += a.px * 0.5f * k;
                continue;
            }
            if (s.charAt(i) != ' ') {
                tex(ctx, a.id, cx - gl[5] * k, top, gl[2] * k, gl[3] * k,
                        gl[0], gl[1], (int) gl[2], (int) gl[3], a.texW, a.texH, color);
            }
            cx += gl[4] * k;
        }
        pop(ctx);
        return cx - x;
    }

    public static void textCentered(DrawContext ctx, String s, float cx, float y, float size, int color, boolean bold) {
        text(ctx, s, cx - textWidth(s, size, bold) / 2f, y, size, color, bold);
    }

    public static void textRight(DrawContext ctx, String s, float rightX, float y, float size, int color, boolean bold) {
        text(ctx, s, rightX - textWidth(s, size, bold), y, size, color, bold);
    }

    private static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }
}
