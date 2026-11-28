package dev.zephyr.gui;

import dev.zephyr.ZephyrClient;
import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.KeySetting;
import dev.zephyr.core.settings.ModeSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.settings.Setting;
import dev.zephyr.gui.render.Ui;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Zephyr module manager - floating rounded glass panels, one per category, drawn with the
 * smooth {@link Ui} toolkit (anti-aliased shapes and text from cached linear-filtered
 * textures; no shaders, no per-frame allocation of GPU resources).
 *
 * <p>Controls: left-click a module to toggle it, right-click to open its settings; drag a
 * panel by its header; wheel scrolls a panel; Ctrl+wheel resizes the whole menu. While
 * binding a key, Escape (or Backspace/Delete) REMOVES the bind.
 */
public final class ClickGuiScreen extends Screen {

    private static float renderScale = 0.5f;

    public static float currentScale() {
        return renderScale;
    }

    public static void setRenderScale(float scale) {
        renderScale = Math.max(0.15f, Math.min(1.5f, scale));
    }

    // Geometry in unscaled gui units.
    private static final int COL_W = 150;
    private static final int GAP = 10;
    private static final int HEADER_H = 26;
    private static final int ROW_H = 22;
    private static final int SETTING_H = 20;
    private static final int SLIDER_H = 26;
    private static final int TOP = 74;
    private static final float RADIUS = 9f;
    private static final float TEXT = 9.5f;
    private static final float TEXT_SMALL = 8.5f;

    private static final Map<String, int[]> LAYOUT = new HashMap<>();
    private static final Map<Module, Boolean> EXPANDED = new HashMap<>();
    /** Modules whose Advanced section is open. */
    private static final Map<Module, Boolean> ADVANCED = new HashMap<>();
    /** Animated values (0..1) per module: toggle knob, hover, expand. */
    private static final Map<Object, Float> ANIM = new HashMap<>();

    private final List<Column> columns = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();

    private Column dragColumn;
    private int dragOffX;
    private int dragOffY;
    private NumberSetting dragSlider;
    private KeySetting capturing;
    private long openedAt;
    /** Live search: typing anywhere filters modules (and their setting names). */
    private static String search = "";
    private float configsBtnX, configsBtnY, configsBtnW, configsBtnH;
    private float hudBtnX, hudBtnY, hudBtnW, hudBtnH;
    private long lastFrame;
    private float dt;

    public ClickGuiScreen() {
        super(Text.literal("Zephyr"));
    }

    private float guiWidth() {
        return this.width / renderScale;
    }

    private float guiHeight() {
        return this.height / renderScale;
    }

    @Override
    protected void init() {
        openedAt = System.nanoTime();
        lastFrame = openedAt;
        rebuildColumns();
    }

    private void rebuildColumns() {
        columns.clear();
        Map<ModuleCategory, List<Module>> grouped = new HashMap<>();
        for (Module m : ZephyrClient.modules().all()) {
            if (m.category() != ModuleCategory.GUI) {
                grouped.computeIfAbsent(m.category(), c -> new ArrayList<>()).add(m);
            }
        }
        List<ModuleCategory> cats = new ArrayList<>();
        for (ModuleCategory cat : ModuleCategory.values()) {
            if (grouped.containsKey(cat)) {
                cats.add(cat);
            }
        }
        // As many columns as fit side by side (closing the gaps first); the rest go under
        // whichever column in the row is shortest.
        int width = (int) guiWidth() - 8;
        int across = Math.max(1, Math.min(cats.size(), (width + 2) / (COL_W + 2)));
        int gap = across > 1 ? Math.max(2, Math.min(GAP, (width - across * COL_W) / (across - 1))) : GAP;
        int[] bottom = new int[across];
        int total = across * COL_W + (across - 1) * gap;
        int startX = Math.max(10, ((int) guiWidth() - total) / 2);
        int i = 0;
        for (ModuleCategory cat : cats) {
            List<Module> mods = grouped.get(cat);
            mods.sort(Comparator.comparing(Module::name));
            Column col = new Column(cat, mods);
            int[] pos = LAYOUT.get(cat.name());
            if (pos != null) {
                col.x = pos[0];
                col.y = pos[1];
            } else {
                int slot = i;
                if (i >= across) {
                    slot = 0;
                    for (int k = 1; k < across; k++) {
                        slot = bottom[k] < bottom[slot] ? k : slot;
                    }
                }
                col.x = startX + slot * (COL_W + gap);
                col.y = i < across ? TOP : bottom[slot];
                bottom[slot] = col.y + HEADER_H + mods.size() * ROW_H + 14;
            }
            columns.add(col);
            i++;
        }
        clampColumns();
    }

    private void clampColumns() {
        int maxX = Math.max(4, (int) guiWidth() - COL_W - 4);
        int maxY = Math.max(4, (int) guiHeight() - HEADER_H - 4);
        for (Column col : columns) {
            col.x = Math.max(4, Math.min(col.x, maxX));
            col.y = Math.max(70, Math.min(col.y, maxY)); // below the bar + search field
            col.scroll = Math.max(0, col.scroll);
        }
    }

    private static final class Column {
        final ModuleCategory category;
        final List<Module> modules;
        int x;
        int y;
        int scroll;

        Column(ModuleCategory category, List<Module> modules) {
            this.category = category;
            this.modules = modules;
        }
    }

    /** {@code advanced} marks the row that opens a module's Advanced section. */
    private record Row(Column column, Module module, Setting<?> setting,
                       int x, int y, int w, int h, boolean header, boolean advanced) {
        boolean inside(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private static int settingHeight(Setting<?> s) {
        return s instanceof NumberSetting ? SLIDER_H : SETTING_H;
    }

    private static boolean advancedOpen(Module m) {
        if (Boolean.TRUE.equals(ADVANCED.get(m))) {
            return true;
        }
        // A search for an advanced setting shows it.
        String q = search.toLowerCase();
        return !q.isEmpty() && m.advancedSettings().stream().anyMatch(s -> s.label().toLowerCase().contains(q));
    }

    /** The rows under an open module: basic settings, the Advanced row (null), then the rest. */
    private static List<Setting<?>> settingRows(Module m) {
        List<Setting<?>> out = new ArrayList<>(m.basicSettings());
        out.add(null);
        if (advancedOpen(m)) {
            out.addAll(m.advancedSettings());
        }
        return out;
    }

    private static int settingsHeight(Module m) {
        int h = 0;
        for (Setting<?> s : settingRows(m)) {
            h += settingHeight(s);
        }
        return h;
    }

    private void buildLayout() {
        rows.clear();
        for (Column col : columns) {
            int y = col.y;
            rows.add(new Row(col, null, null, col.x, y, COL_W, HEADER_H, true, false));
            y += HEADER_H - col.scroll;
            if (visible(col).isEmpty()) {
                continue;
            }
            for (Module m : visible(col)) {
                rows.add(new Row(col, m, null, col.x, y, COL_W, ROW_H, false, false));
                y += ROW_H;
                if (isExpanded(m)) {
                    for (Setting<?> s : settingRows(m)) {
                        int h = settingHeight(s);
                        rows.add(new Row(col, m, s, col.x, y, COL_W, h, false, s == null));
                        y += h;
                    }
                    y += 4;
                }
            }
        }
    }

    private static boolean matches(Module m) {
        if (search.isEmpty()) {
            return true;
        }
        String q = search.toLowerCase();
        if (m.name().toLowerCase().contains(q) || m.category().label().toLowerCase().contains(q)
                || m.description().toLowerCase().contains(q)) {
            return true;
        }
        for (Setting<?> st : m.settings()) {
            if (st.label().toLowerCase().contains(q)) {
                return true;
            }
        }
        return false;
    }

    private static List<Module> visible(Column col) {
        List<Module> out = new ArrayList<>();
        for (Module m : col.modules) {
            if (matches(m)) {
                out.add(m);
            }
        }
        return out;
    }

    /** Test harness: opens a module's settings list as a right-click would. */
    public static void expandForTest(String name) {
        Module m = ZephyrClient.modules().byName(name);
        if (m != null) {
            EXPANDED.put(m, true);
        }
    }

    private static boolean isExpanded(Module m) {
        return Boolean.TRUE.equals(EXPANDED.get(m));
    }

    private int contentHeight(Column col) {
        int h = 0;
        for (Module m : visible(col)) {
            h += ROW_H;
            if (isExpanded(m)) {
                h += settingsHeight(m) + 4;
            }
        }
        return h + 6;
    }

    /** Visible body height of a column (it never runs off the bottom of the screen). */
    private int bodyHeight(Column col) {
        return Math.min(contentHeight(col), (int) guiHeight() - col.y - HEADER_H - 30);
    }

    private float anim(Object key, float target, float speed) {
        float v = ANIM.getOrDefault(key, target);
        v = Ui.approach(v, target, speed, dt);
        ANIM.put(key, v);
        return v;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        var clickGui = ZephyrClient.modules().get(dev.zephyr.modules.gui.ClickGui.class);
        if (clickGui != null) {
            clickGui.syncScale();
        }
        long now = System.nanoTime();
        dt = Math.min(0.1f, (now - lastFrame) / 1.0e9f);
        lastFrame = now;
        float open = Math.min(1f, (now - openedAt) / 1.8e8f);
        float ease = 1f - (1f - open) * (1f - open) * (1f - open);

        lastMouseGuiX = mouseX / renderScale;
        lastMouseGuiY = mouseY / renderScale;
        buildLayout();
        clampColumns();

        ctx.fill(0, 0, this.width, this.height, Ui.alpha(0x8C06080C, ease));

        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().scale(renderScale, renderScale);
        ctx.getMatrices().translate(0, (1f - ease) * 10f);
        Ui.beginFrame(renderScale);
        try {
            renderTopBar(ctx, ease);
            renderSearch(ctx, ease);
            for (Column col : columns) {
                if (!visible(col).isEmpty()) {
                    renderColumn(ctx, col, ease);
                }
            }
            renderDescriptionTooltip(ctx, ease);
            String hint = capturing != null
                    ? "Press a key or mouse button to bind  ·  Esc removes the bind  ·  Left-click cancels"
                    : "Left-click toggle  ·  Right-click settings  ·  Drag headers  ·  Ctrl + wheel resize";
            float hintW = Ui.textWidth(hint, TEXT_SMALL, false) + 20;
            Ui.surface(ctx, (guiWidth() - hintW) / 2f, guiHeight() - 21, hintW, 17, 8.5f, Ui.BG, ease);
            Ui.textCentered(ctx, hint, guiWidth() / 2f, guiHeight() - 16, TEXT_SMALL,
                    Ui.alpha(capturing != null ? Ui.ACCENT : Ui.TEXT_DIM, ease), false);
        } finally {
            ctx.getMatrices().popMatrix();
        }
    }

    private float sizeX;
    private float sizeY;
    private float sizeW;
    private float sizeH;

    /** "- 40% +": the size of this menu (the HUD has its own editor). */
    private void renderSizeControl(DrawContext ctx, float ease, float x, float y, float h) {
        sizeW = 84;
        sizeH = h;
        sizeX = x;
        sizeY = y;
        Ui.panel(ctx, x, y, sizeW, h, h / 2f, Ui.BG_2, ease);
        boolean over = lastMouseGuiY >= y && lastMouseGuiY < y + h && lastMouseGuiX >= x && lastMouseGuiX < x + sizeW;
        boolean left = over && lastMouseGuiX < x + 26;
        boolean right = over && lastMouseGuiX >= x + sizeW - 26;
        Ui.textCentered(ctx, "-", x + 13, y + 9.5f, TEXT_SMALL, Ui.alpha(left ? Ui.ACCENT : Ui.TEXT_DIM, ease), true);
        Ui.textCentered(ctx, "+", x + sizeW - 13, y + 9.5f, TEXT_SMALL,
                Ui.alpha(right ? Ui.ACCENT : Ui.TEXT_DIM, ease), true);
        Ui.textCentered(ctx, Math.round(renderScale * 100) + "%", x + sizeW / 2f, y + 9.5f, TEXT_SMALL,
                Ui.alpha(Ui.ACCENT, ease), true);
    }

    private void renderTopBar(DrawContext ctx, float ease) {
        String stats = String.format("%d modules  ·  %d active  ·  %.0f fps",
                ZephyrClient.modules().all().size() - 1, countEnabled(),
                (double) net.minecraft.client.MinecraftClient.getInstance().getCurrentFps());
        float statsW = Ui.textWidth(stats, TEXT_SMALL, false);
        float h = 28;
        float markH = 24;
        float markW = markH * Ui.WORDMARK_ASPECT;
        float w = 12 + markW + 16 + statsW + 14;
        float x = (guiWidth() - w) / 2f;
        float y = 10;
        Ui.panel(ctx, x, y, w, h, h / 2f, Ui.BG_2, ease);
        Ui.wordmark(ctx, x + 12, y + 2, markH, Ui.alpha(Ui.ACCENT, ease));
        float sx = x + 12 + markW + 8;
        Ui.rect(ctx, sx, y + 9, 1, h - 18, Ui.alpha(0x33FFFFFF, ease));
        Ui.text(ctx, stats, sx + 8, y + 10, TEXT_SMALL, Ui.alpha(Ui.TEXT_DIM, ease), false);
        // Configs button (right of the bar).
        configsBtnW = Ui.textWidth("Configs", TEXT_SMALL, true) + 20;
        configsBtnH = h;
        configsBtnX = x + w + 8;
        configsBtnY = y;
        boolean hov = lastMouseGuiX >= configsBtnX && lastMouseGuiX < configsBtnX + configsBtnW
                && lastMouseGuiY >= configsBtnY && lastMouseGuiY < configsBtnY + configsBtnH;
        Ui.panel(ctx, configsBtnX, configsBtnY, configsBtnW, h, h / 2f, hov ? Ui.ACCENT : Ui.BG_2, ease);
        Ui.textCentered(ctx, "Configs", configsBtnX + configsBtnW / 2f, y + 9.5f, TEXT_SMALL,
                Ui.alpha(hov ? 0xFF000000 : Ui.ACCENT, ease), true);
        // Edit HUD button (right of Configs): opens the drag-to-move HUD editor.
        hudBtnW = Ui.textWidth("Edit HUD", TEXT_SMALL, true) + 20;
        hudBtnH = h;
        hudBtnX = configsBtnX + configsBtnW + 6;
        hudBtnY = y;
        boolean hudHov = lastMouseGuiX >= hudBtnX && lastMouseGuiX < hudBtnX + hudBtnW
                && lastMouseGuiY >= hudBtnY && lastMouseGuiY < hudBtnY + hudBtnH;
        Ui.panel(ctx, hudBtnX, hudBtnY, hudBtnW, h, h / 2f, hudHov ? Ui.ACCENT : Ui.BG_2, ease);
        Ui.textCentered(ctx, "Edit HUD", hudBtnX + hudBtnW / 2f, y + 9.5f, TEXT_SMALL,
                Ui.alpha(hudHov ? 0xFF000000 : Ui.ACCENT, ease), true);
        renderSizeControl(ctx, ease, hudBtnX + hudBtnW + 6, y, h);
    }

    private void renderSearch(DrawContext ctx, float ease) {
        float w = 240, h = 18;
        float x = (guiWidth() - w) / 2f, y = 46;
        boolean empty = search.isEmpty();
        Ui.panel(ctx, x, y, w, h, 9, Ui.BG_2, ease);
        if (!empty) {
            Ui.outline(ctx, x, y, w, h, 9, Ui.alpha(0xB0FFFFFF, ease));
        }
        String shown = empty ? "Type to search modules and settings" : search;
        // magnifier: ring + handle
        int ic = Ui.alpha(Ui.TEXT_FAINT, ease);
        Ui.circle(ctx, x + 11, y + 8, 3.6f, ic);
        Ui.circle(ctx, x + 11, y + 8, 2.4f, Ui.alpha(Ui.BG_2, ease));
        for (int i = 0; i < 3; i++) {
            Ui.roundRect(ctx, x + 13.2f + i * 0.9f, y + 10.2f + i * 0.9f, 1.6f, 1.6f, 0.8f, ic);
        }
        float tx = Ui.text(ctx, shown, x + 20, y + 5, 8.5f, Ui.alpha(empty ? Ui.TEXT_FAINT : Ui.TEXT, ease), false);
        if (!empty && (System.currentTimeMillis() / 500) % 2 == 0) {
            Ui.rect(ctx, x + 21 + tx, y + 4, 1, 10, Ui.alpha(Ui.ACCENT, ease)); // caret
        }
        if (!empty) {
            Ui.textRight(ctx, "Esc clears", x + w - 9, y + 5, 7.5f, Ui.alpha(Ui.TEXT_FAINT, ease), false);
        }
    }

    /** Hover a module row to see what it does (drawn last so it sits above every panel). */
    private void renderDescriptionTooltip(DrawContext ctx, float ease) {
        if (dragColumn != null || capturing != null) {
            return;
        }
        Module hovered = null;
        for (Row row : rows) {
            if (row.header || row.advanced || row.setting != null || row.module == null
                    || !row.inside(lastMouseGuiX, lastMouseGuiY)) {
                continue;
            }
            Column col = row.column;
            float bodyTop = col.y + HEADER_H;
            if (lastMouseGuiY < bodyTop || lastMouseGuiY > bodyTop + bodyHeight(col)) {
                continue; // row scrolled out of its panel
            }
            hovered = row.module;
            break;
        }
        float show = anim("tip", hovered != null ? 1f : 0f, 14f);
        if (hovered == null) {
            return;
        }
        String text = hovered.description();
        if (text.isEmpty() || show < 0.05f) {
            return;
        }
        final float size = 8f;
        final float maxW = 190f;
        List<String> lines = wrap(text, size, maxW);
        float w = 0;
        for (String line : lines) {
            w = Math.max(w, Ui.textWidth(line, size, false));
        }
        String title = hovered.name() + "  ·  " + hovered.category().label();
        w = Math.max(w, Ui.textWidth(title, size, true)) + 16;
        float h = 10 + 11 + lines.size() * 10 + 6;
        float mx = (float) lastMouseGuiX;
        float my = (float) lastMouseGuiY;
        float x = mx + 12;
        float y = my + 10;
        if (x + w > guiWidth() - 4) {
            x = mx - 12 - w;
        }
        if (y + h > guiHeight() - 4) {
            y = guiHeight() - 4 - h;
        }
        float a = show * ease;
        Ui.panel(ctx, x, y, w, h, 7, Ui.BG_2, a);
        Ui.roundRect(ctx, x + 6, y + 7, 2, 8, 1, Ui.alpha(Ui.ACCENT, a));
        Ui.text(ctx, title, x + 12, y + 7, size, Ui.alpha(Ui.TEXT, a), true);
        float ly = y + 7 + 13;
        for (String line : lines) {
            Ui.text(ctx, line, x + 8, ly, size, Ui.alpha(Ui.TEXT_DIM, a), false);
            ly += 10;
        }
    }

    /** Greedy word wrap at {@code maxW} gui pixels. */
    private static List<String> wrap(String text, float size, float maxW) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && Ui.textWidth(candidate, size, false) > maxW) {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(candidate);
            }
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    private static int countEnabled() {
        int n = 0;
        for (Module m : ZephyrClient.modules().all()) {
            if (m.isEnabled()) {
                n++;
            }
        }
        return n;
    }

    private void renderColumn(DrawContext ctx, Column col, float ease) {
        int body = bodyHeight(col);
        float x = col.x;
        float y = col.y;
        float h = HEADER_H + body;
        Ui.panel(ctx, x, y, COL_W, h, RADIUS, Ui.BG, ease);

        // Header: category name, active count and an accent underline.
        int active = 0;
        for (Module m : col.modules) {
            if (m.isEnabled()) {
                active++;
            }
        }
        Ui.icon(ctx, col.category.name().toLowerCase(java.util.Locale.ROOT), x + 11, y + 7.5f, 11,
                Ui.alpha(Ui.TEXT, ease));
        Ui.text(ctx, col.category.label(), x + 27, y + 9, TEXT, Ui.alpha(Ui.TEXT, ease), true);
        String count = active + "/" + col.modules.size();
        float cw = Ui.textWidth(count, TEXT_SMALL, false) + 10;
        Ui.pill(ctx, x + COL_W - 10 - cw, y + 7, cw, 12,
                Ui.alpha(active > 0 ? Ui.alpha(Ui.ACCENT, 0.18f) : 0x14FFFFFF, ease));
        Ui.textCentered(ctx, count, x + COL_W - 10 - cw / 2f, y + 9.5f, TEXT_SMALL,
                Ui.alpha(active > 0 ? Ui.ACCENT : Ui.TEXT_FAINT, ease), false);
        Ui.rect(ctx, x + 1, y + HEADER_H - 1, COL_W - 2, 1, Ui.alpha(0x24FFFFFF, ease));

        // Body (clipped to the panel so scrolled rows never spill out).
        // enableScissor is transformed by the current matrix, so plain gui-space coords.
        ctx.enableScissor(col.x, col.y + HEADER_H, col.x + COL_W, col.y + HEADER_H + body);
        try {
            for (Row row : rows) {
                if (row.column != col || row.header) {
                    continue;
                }
                if (row.y + row.h < y + HEADER_H || row.y > y + HEADER_H + body) {
                    continue;
                }
                if (row.advanced) {
                    renderAdvancedRow(ctx, row, ease);
                } else if (row.setting == null) {
                    renderModuleRow(ctx, row, ease);
                } else {
                    renderSettingRow(ctx, row, ease);
                }
            }
        } finally {
            ctx.disableScissor();
        }
    }

    private void renderModuleRow(DrawContext ctx, Row row, float ease) {
        Module m = row.module;
        boolean hover = row.inside(lastMouseGuiX, lastMouseGuiY) && dragColumn == null;
        float hov = anim("h" + System.identityHashCode(m), hover ? 1f : 0f, 18f);
        float on = anim("t" + System.identityHashCode(m), m.isEnabled() ? 1f : 0f, 16f);
        float x = row.x + 5;
        float w = row.w - 10;
        if (on > 0.01f) {
            Ui.roundRect(ctx, x, row.y + 1, w, row.h - 2, 6, Ui.alpha(0x17FFFFFF, on * ease));
            Ui.roundRect(ctx, x + 2, row.y + 6, 2, row.h - 12, 1, Ui.alpha(Ui.ACCENT, on * ease));
        }
        if (hov > 0.01f) {
            Ui.roundRect(ctx, x, row.y + 1, w, row.h - 2, 6, Ui.alpha(0x12FFFFFF, hov * ease));
        }
        if (isExpanded(m)) {
            Ui.surface(ctx, x, row.y + row.h - 2, w, settingsHeight(m) + 4, 6, 0x50000000, ease);
        }
        int textColor = Ui.mix(Ui.TEXT_DIM, Ui.TEXT, Math.max(on, hov * 0.6f));
        Ui.text(ctx, m.name(), x + 8 + 3 * on, row.y + 7, TEXT, Ui.alpha(textColor, ease), on > 0.5f);

        // Toggle switch.
        float tw = 20, th = 11;
        float tx = x + w - 7 - tw;
        float ty = row.y + (row.h - th) / 2f;
        Ui.pill(ctx, tx, ty, tw, th, Ui.alpha(Ui.mix(0xFF2A2A2A, Ui.ACCENT, on), ease));
        float knob = th - 4;
        Ui.circle(ctx, tx + 2 + knob / 2f + (tw - 4 - knob) * on, ty + th / 2f, knob / 2f,
                Ui.alpha(Ui.mix(0xFF9A9A9A, 0xFF000000, on), ease));

        if (m.bind().isBound()) {
            String k = m.bind().display();
            float kw = Ui.textWidth(k, TEXT_SMALL - 1, false) + 7;
            Ui.outline(ctx, tx - 6 - kw, row.y + 6, kw, 11, 3, Ui.alpha(0x40FFFFFF, ease));
            Ui.textCentered(ctx, k, tx - 6 - kw / 2f, row.y + 8, TEXT_SMALL - 1, Ui.alpha(Ui.TEXT_DIM, ease), false);
        } else if (!m.settings().isEmpty() && hov > 0.01f) {
            for (int dot = 0; dot < 3; dot++) { // right-click for settings
                Ui.circle(ctx, tx - 14 + dot * 4, row.y + row.h / 2f, 1f, Ui.alpha(Ui.TEXT_DIM, hov * ease));
            }
        }
    }

    private void renderAdvancedRow(DrawContext ctx, Row row, float ease) {
        boolean open = advancedOpen(row.module);
        boolean hover = row.inside(lastMouseGuiX, lastMouseGuiY);
        float x = row.x + 14;
        float right = row.x + row.w - 12;
        Ui.rect(ctx, x, row.y + 2, right - x, 1, Ui.alpha(0x24FFFFFF, ease));
        int color = Ui.alpha(hover ? Ui.TEXT : Ui.TEXT_FAINT, ease);
        Ui.text(ctx, "Advanced", x, row.y + 7, TEXT_SMALL, color, true);
        Ui.textRight(ctx, open ? "hide" : "show " + row.module.advancedSettings().size(), right, row.y + 7,
                TEXT_SMALL, color, false);
    }

    private void renderSettingRow(DrawContext ctx, Row row, float ease) {
        Setting<?> s = row.setting;
        float x = row.x + 14;
        float right = row.x + row.w - 12;
        int label = Ui.alpha(Ui.TEXT_DIM, ease);
        if (s instanceof BooleanSetting b) {
            Ui.text(ctx, s.label(), x, row.y + 6, TEXT_SMALL, label, false);
            float on = anim(b, b.get() ? 1f : 0f, 16f);
            float tw = 16, th = 9;
            float tx = right - tw;
            float ty = row.y + (row.h - th) / 2f;
            Ui.pill(ctx, tx, ty, tw, th, Ui.alpha(Ui.mix(0xFF2A2A2A, Ui.ACCENT, on), ease));
            float knob = th - 4;
            Ui.circle(ctx, tx + 2 + knob / 2f + (tw - 4 - knob) * on, ty + th / 2f, knob / 2f,
                    Ui.alpha(0xFF000000, ease));
        } else if (s instanceof ModeSetting mode) {
            Ui.text(ctx, s.label(), x, row.y + 6, TEXT_SMALL, label, false);
            String v = pretty(mode.get());
            float vw = Ui.textWidth(v, TEXT_SMALL, false) + 12;
            Ui.pill(ctx, right - vw, row.y + 4, vw, 12, Ui.alpha(Ui.alpha(Ui.ACCENT_2, 0.22f), ease));
            Ui.textCentered(ctx, v, right - vw / 2f, row.y + 6.5f, TEXT_SMALL, Ui.alpha(0xFFE6E6E6, ease), false);
        } else if (s instanceof NumberSetting num) {
            Ui.text(ctx, s.label(), x, row.y + 4, TEXT_SMALL, label, false);
            Ui.textRight(ctx, formatValue(num.get()), right, row.y + 4, TEXT_SMALL, Ui.alpha(Ui.TEXT, ease), false);
            double frac = num.max() > num.min() ? (num.get() - num.min()) / (num.max() - num.min()) : 0.0;
            float f = anim(num, (float) Math.max(0.0, Math.min(1.0, frac)), 22f);
            float trackX = x;
            float trackW = right - x;
            float ty = row.y + 17;
            Ui.pill(ctx, trackX, ty, trackW, 3, Ui.alpha(0xFF2A2A2A, ease));
            Ui.pill(ctx, trackX, ty, Math.max(3, trackW * f), 3, Ui.alpha(Ui.ACCENT, ease));
            Ui.circle(ctx, trackX + trackW * f, ty + 1.5f, 3.5f, Ui.alpha(0xFFFFFFFF, ease));
        } else if (s instanceof KeySetting key) {
            Ui.text(ctx, s.label(), x, row.y + 6, TEXT_SMALL, label, false);
            boolean cap = capturing == key;
            String v = cap ? "press a key…" : (key.isBound() ? key.display() : "none");
            float vw = Ui.textWidth(v, TEXT_SMALL, false) + 12;
            Ui.pill(ctx, right - vw, row.y + 4, vw, 12,
                    Ui.alpha(cap ? Ui.alpha(Ui.ACCENT, 0.3f) : 0x18FFFFFF, ease));
            Ui.textCentered(ctx, v, right - vw / 2f, row.y + 6.5f, TEXT_SMALL,
                    Ui.alpha(cap ? Ui.ACCENT : Ui.TEXT_DIM, ease), false);
        }
    }

    private static String pretty(String mode) {
        String s = mode.replace('_', ' ').toLowerCase();
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String formatValue(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) {
            return String.valueOf((long) v);
        }
        return String.format("%.2f", v);
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    private double lastMouseGuiX;
    private double lastMouseGuiY;

    private double guiX(double screenX) {
        return screenX / renderScale;
    }

    private double guiY(double screenY) {
        return screenY / renderScale;
    }

    /** True when the point is inside the visible body of its column (not scrolled away). */
    private boolean visible(Row row, double my) {
        Column col = row.column;
        return row.header || (my >= col.y + HEADER_H && my < col.y + HEADER_H + bodyHeight(col));
    }

    public boolean isCapturing() {
        return capturing != null;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        double mx = guiX(click.x());
        double my = guiY(click.y());
        int button = click.button();
        if (capturing == null && button == 0 && mx >= configsBtnX && mx < configsBtnX + configsBtnW
                && my >= configsBtnY && my < configsBtnY + configsBtnH) {
            this.client.setScreen(new ConfigScreen(this));
            return true;
        }
        if (capturing == null && button == 0 && mx >= sizeX && mx < sizeX + sizeW
                && my >= sizeY && my < sizeY + sizeH && (mx < sizeX + 26 || mx >= sizeX + sizeW - 26)) {
            var gui = ZephyrClient.modules().get(dev.zephyr.modules.gui.ClickGui.class);
            if (gui != null) {
                gui.adjustScale(mx < sizeX + 26 ? -0.05 : 0.05);
                ZephyrClient.config().save();
            }
            return true;
        }
        if (capturing == null && button == 0 && mx >= hudBtnX && mx < hudBtnX + hudBtnW
                && my >= hudBtnY && my < hudBtnY + hudBtnH) {
            this.client.setScreen(new HudEditorScreen());
            return true;
        }
        if (capturing != null) {
            // Binding: any mouse button except left binds it (side buttons, middle,
            // right); left click cancels so you can still click away.
            if (button != 0) {
                capturing.set(dev.zephyr.core.input.BindInput.MOUSE_BASE + button);
                ZephyrClient.config().save();
            }
            capturing = null;
            return true;
        }
        buildLayout();
        for (Row row : rows) {
            if (!row.inside(mx, my) || !visible(row, my)) {
                continue;
            }
            if (row.header && button == 0) {
                dragColumn = row.column;
                dragOffX = (int) mx - row.column.x;
                dragOffY = (int) my - row.column.y;
                return true;
            }
            if (row.advanced) {
                if (button == 0) {
                    ADVANCED.put(row.module, !Boolean.TRUE.equals(ADVANCED.get(row.module)));
                }
                return true;
            }
            if (row.setting != null) {
                return handleSettingClick(row, mx, button);
            }
            if (row.module != null) {
                if (button == 0) {
                    row.module.toggle();
                    ZephyrClient.config().save();
                } else if (button == 1) {
                    EXPANDED.put(row.module, !isExpanded(row.module));
                }
                return true;
            }
        }
        return super.mouseClicked(click, doubleClick);
    }

    private boolean handleSettingClick(Row row, double mx, int button) {
        Setting<?> s = row.setting;
        if (button != 0) {
            return false;
        }
        if (s instanceof BooleanSetting b) {
            b.set(!b.get());
        } else if (s instanceof ModeSetting mode) {
            mode.cycle();
        } else if (s instanceof NumberSetting num) {
            dragSlider = num;
            applySlider(row, num, mx);
            return true;
        } else if (s instanceof KeySetting key) {
            capturing = capturing == key ? null : key;
            return true;
        } else {
            return false;
        }
        ZephyrClient.config().save();
        return true;
    }

    private void applySlider(Row row, NumberSetting num, double mx) {
        double x0 = row.x + 14;
        double x1 = row.x + row.w - 12;
        double frac = Math.max(0.0, Math.min(1.0, (mx - x0) / (x1 - x0)));
        double raw = num.min() + (num.max() - num.min()) * frac;
        double step = num.step();
        if (step >= 0.01) {
            raw = num.min() + Math.round((raw - num.min()) / step) * step;
        }
        num.set(Math.max(num.min(), Math.min(num.max(), raw)));
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        double mx = guiX(click.x());
        double my = guiY(click.y());
        if (dragColumn != null) {
            dragColumn.x = (int) mx - dragOffX;
            dragColumn.y = (int) my - dragOffY;
            clampColumns();
            LAYOUT.put(dragColumn.category.name(), new int[]{dragColumn.x, dragColumn.y});
            return true;
        }
        if (dragSlider != null) {
            buildLayout();
            for (Row row : rows) {
                if (row.setting == dragSlider) {
                    applySlider(row, dragSlider, mx);
                    return true;
                }
            }
        }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (dragColumn != null) {
            dragColumn = null;
            return true;
        }
        if (dragSlider != null) {
            dragSlider = null;
            ZephyrClient.config().save();
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        long win = net.minecraft.client.MinecraftClient.getInstance().getWindow().getHandle();
        if (GLFW.glfwGetKey(win, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(win, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS) {
            var cg = ZephyrClient.modules().get(dev.zephyr.modules.gui.ClickGui.class);
            if (cg != null) {
                cg.adjustScale(vertical > 0 ? 0.05 : -0.05);
            }
            return true;
        }
        double gx = guiX(mx);
        double gy = guiY(my);
        for (Column col : columns) {
            if (gx >= col.x && gx < col.x + COL_W && gy >= col.y && gy < col.y + HEADER_H + bodyHeight(col)) {
                int max = Math.max(0, contentHeight(col) - bodyHeight(col));
                col.scroll = Math.max(0, Math.min(col.scroll - (int) (vertical * 22), max));
                return true;
            }
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int keyCode = input.getKeycode();
        if (capturing != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_BACKSPACE
                    || keyCode == GLFW.GLFW_KEY_DELETE) {
                capturing.set(GLFW.GLFW_KEY_UNKNOWN); // Escape removes the current bind
            } else {
                capturing.set(keyCode);
            }
            capturing = null;
            ZephyrClient.config().save();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) {
            boolean ctrl = (input.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
            search = ctrl ? "" : search.substring(0, search.length() - 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (!search.isEmpty()) {
                search = ""; // first Escape clears the search, the next one closes
                return true;
            }
            this.close();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharInput input) {
        if (capturing != null || !input.isValidChar()) {
            return false;
        }
        String c = input.asString();
        if (search.length() < 32 && !c.isBlank() || (!search.isEmpty() && c.equals(" "))) {
            search += c;
            for (Column col : columns) {
                col.scroll = 0;
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void removed() {
        ZephyrClient.config().save();
        super.removed();
    }
}
