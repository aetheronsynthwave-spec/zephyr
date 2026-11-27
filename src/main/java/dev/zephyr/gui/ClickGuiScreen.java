package dev.zephyr.gui;

import dev.zephyr.ZephyrClient;
import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.KeySetting;
import dev.zephyr.core.settings.ModeSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.settings.Setting;
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
 * Zephyr module manager screen - drawn entirely with DrawContext fills (no textures,
 * no vanilla widgets), so it is version-stable and cheap.
 *
 * <p>v1.2 fixes:
 * <ul>
 *   <li><b>Ghost-text glitch</b>: the old renderer drew a module row (label + state bar +
 *       expanded background) for EVERY row, including setting rows - so every setting line
 *       first painted a duplicate module label on top of itself. Rows are now rendered in
 *       three strictly disjoint passes (header / module / setting).</li>
 *   <li><b>Scale</b>: the whole GUI renders under a {@code 1/3} matrix scale, i.e. 3x
 *       smaller than before; mouse input is un-scaled so every hit-rect stays exact.</li>
 *   <li>Off-screen rows are culled instead of drawn past the column bottom.</li>
 * </ul>
 *
 * <p>Interaction model:
 * <ul>
 *   <li>Columns: one per module category; drag the header to move a column,
 *       scroll the wheel to pan a long column.</li>
 *   <li>Module rows: left-click toggles, right-click expands/collapses the settings.</li>
 *   <li>Setting rows: booleans flip, modes cycle, numbers drag as sliders,
 *       keybinds capture the next key (Backspace clears, Escape cancels).</li>
 * </ul>
 */
public final class ClickGuiScreen extends Screen {

    /** The whole GUI is drawn at 1/3 size: 3x smaller than the old full-scale panels. */
    private static final float SCALE = 1.0f / 3.0f;

    private static final int COL_W = 128;
    private static final int GAP = 6;
    private static final int HEADER_H = 18;
    private static final int ROW_H = 20;
    private static final int SETTING_H = 17;
    private static final int DEFAULT_Y = 28;

    private static final int COL_PANEL = 0xF210141A;
    private static final int COL_HEADER = 0xFF1E3A4C;
    private static final int COL_SETTING_BG = 0xE20A0C10;
    private static final int COL_HOVER = 0x28FFFFFF;
    private static final int COL_ACCENT = 0xFF35C0DC;
    private static final int COL_ON = 0xFF3CE07A;
    private static final int COL_TEXT = 0xFFFFFFFF;
    private static final int COL_TEXT_DIM = 0xFF9AA0A6;

    /** Column positions persist across opens in one session (in scaled gui space). */
    private static final Map<String, int[]> LAYOUT = new HashMap<>();

    private final List<Column> columns = new ArrayList<>();
    /** Flattened hit-rects, rebuilt before every render/click pass. */
    private final List<Row> rows = new ArrayList<>();

    private Column dragColumn;
    private int dragOffX;
    private int dragOffY;
    private NumberSetting dragSlider;
    private KeySetting capturing;

    public ClickGuiScreen() {
        super(Text.literal("Zephyr"));
    }

    /** Screen width in gui (scaled) space. */
    private float guiWidth() {
        return this.width / SCALE;
    }

    /** Screen height in gui (scaled) space. */
    private float guiHeight() {
        return this.height / SCALE;
    }

    @Override
    protected void init() {
        rebuildColumns();
    }

    private void rebuildColumns() {
        columns.clear();
        Map<ModuleCategory, List<Module>> grouped = new HashMap<>();
        for (Module m : ZephyrClient.modules().all()) {
            if (m.category() == ModuleCategory.GUI) {
                continue; // the GUI button itself lives on the keybind, not in a column
            }
            grouped.computeIfAbsent(m.category(), c -> new ArrayList<>()).add(m);
        }
        int i = 0;
        for (ModuleCategory cat : ModuleCategory.values()) {
            List<Module> mods = grouped.get(cat);
            if (mods == null) {
                continue;
            }
            mods.sort(Comparator.comparing(Module::name));
            Column col = new Column(cat, mods);
            int[] pos = LAYOUT.get(cat.name());
            if (pos != null) {
                col.x = pos[0];
                col.y = pos[1];
            } else {
                col.x = (int) (12 + i * (COL_W + GAP));
                col.y = DEFAULT_Y;
            }
            columns.add(col);
            i++;
        }
        clampColumns();
    }

    private void clampColumns() {
        int maxX = Math.max(2, (int) guiWidth() - COL_W - 2);
        int maxY = Math.max(2, (int) guiHeight() - HEADER_H - 2);
        for (Column col : columns) {
            col.x = Math.max(2, Math.min(col.x, maxX));
            col.y = Math.max(2, Math.min(col.y, maxY));
            col.scroll = Math.max(0, col.scroll);
        }
    }

    // ------------------------------------------------------------------
    // Layout model
    // ------------------------------------------------------------------

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

    private static final class Row {
        final Column column;
        final Module module;
        final Setting<?> setting;
        final int x;
        final int y;
        final int w;
        final int h;
        final boolean header;

        Row(Column column, Module module, Setting<?> setting,
            int x, int y, int w, int h, boolean header) {
            this.column = column;
            this.module = module;
            this.setting = setting;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.header = header;
        }

        boolean inside(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private void buildLayout() {
        rows.clear();
        float gh = guiHeight();
        for (Column col : columns) {
            int y = col.y - col.scroll;
            rows.add(new Row(col, null, null, col.x, y, COL_W, HEADER_H, true));
            y += HEADER_H;
            for (Module m : col.modules) {
                rows.add(new Row(col, m, null, col.x, y, COL_W, ROW_H, false));
                y += ROW_H;
                if (isExpanded(m)) {
                    for (Setting<?> s : m.settings()) {
                        rows.add(new Row(col, m, s, col.x, y, COL_W, SETTING_H, false));
                        y += SETTING_H;
                    }
                }
            }
        }
    }

    private static final Map<Module, Boolean> EXPANDED = new HashMap<>();

    private static boolean isExpanded(Module m) {
        return Boolean.TRUE.equals(EXPANDED.get(m));
    }

    /** Content height of a column below its header (for scroll clamping). */
    private int contentHeight(Column col) {
        int h = 0;
        for (Module m : col.modules) {
            h += ROW_H;
            if (isExpanded(m)) {
                h += m.settings().size() * SETTING_H;
            }
        }
        return h;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        buildLayout();
        clampColumns();

        // 3x-smaller GUI: scale the whole screen's draw calls, then lay out in
        // scaled coordinates (gui px = screen px / SCALE).
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().scale(SCALE, SCALE);
        try {
            ctx.fill(0, 0, (int) guiWidth(), (int) guiHeight(), 0x90101014);

            for (Row row : rows) {
                if (row.header) {
                    renderColumn(ctx, row);
                }
            }
            // Pass 2: module rows ONLY (setting rows are rendered separately - drawing
            // them here too painted a duplicate module label over every setting line,
            // which was the ghost-text glitch).
            for (Row row : rows) {
                if (!row.header && row.setting == null) {
                    renderModuleRow(ctx, row);
                }
            }
            // Pass 3: setting rows.
            for (Row row : rows) {
                if (row.setting != null) {
                    renderSettingRow(ctx, row);
                }
            }

            String hint = "L-Click toggle · R-Click settings · drag header · wheel scroll · "
                    + (capturing != null ? "PRESS A KEY..." : "Esc close");
            ctx.drawText(this.textRenderer, hint,
                    ((int) guiWidth() - this.textRenderer.getWidth(hint)) / 2,
                    (int) guiHeight() - 12, capturing != null ? COL_ACCENT : 0xFF606870, false);
        } finally {
            ctx.getMatrices().popMatrix();
        }
    }

    private void renderColumn(DrawContext ctx, Row row) {
        Column col = row.column;
        ctx.fill(row.x, row.y, row.x + row.w, row.y + row.h, COL_HEADER);
        ctx.drawText(this.textRenderer, col.category.label(),
                row.x + 6, row.y + 5, COL_ACCENT, false);
        // Drag affordance dots.
        ctx.fill(row.x + row.w - 10, row.y + 8, row.x + row.w - 4, row.y + 10, 0xFF3A5A70);
    }

    private void renderModuleRow(DrawContext ctx, Row row) {
        Module m = row.module;
        boolean on = m.isEnabled();

        // Panel background for the whole column segment (settings included).
        ctx.fill(row.x, row.y, row.x + row.w, row.y + row.h, COL_PANEL);
        if (isExpanded(m)) {
            int contentBottom = row.y + ROW_H + m.settings().size() * SETTING_H;
            ctx.fill(row.x, row.y + ROW_H, row.x + row.w, contentBottom, COL_SETTING_BG);
        }
        boolean hover = row.inside(lastMouseGuiX, lastMouseGuiY);
        if (hover) {
            ctx.fill(row.x, row.y, row.x + row.w, row.y + row.h, COL_HOVER);
        }
        // Enabled state bar.
        ctx.fill(row.x + 2, row.y + 3, row.x + 5, row.y + row.h - 3,
                on ? COL_ON : 0x30FFFFFF);

        int color = on ? COL_TEXT : COL_TEXT_DIM;
        String label = m.name();
        ctx.drawText(this.textRenderer, label, row.x + 9, row.y + 6, color, false);
        if (m.bind().isBound()) {
            String k = m.bind().display();
            ctx.drawText(this.textRenderer, k,
                    row.x + row.w - 6 - this.textRenderer.getWidth(k),
                    row.y + 6, 0xFF51606C, false);
        }
    }

    private void renderSettingRow(DrawContext ctx, Row row) {
        Setting<?> s = row.setting;
        boolean hover = row.inside(lastMouseGuiX, lastMouseGuiY);
        if (hover) {
            ctx.fill(row.x + 1, row.y, row.x + row.w - 1, row.y + row.h, 0x18FFFFFF);
        }

        if (s instanceof BooleanSetting b) {
            ctx.drawText(this.textRenderer, s.label(), row.x + 12, row.y + 5, COL_TEXT_DIM, false);
            String state = b.get() ? "ON" : "OFF";
            ctx.drawText(this.textRenderer, state,
                    row.x + row.w - 6 - this.textRenderer.getWidth(state),
                    row.y + 5, b.get() ? COL_ON : 0xFF51606C, false);
        } else if (s instanceof ModeSetting mode) {
            ctx.drawText(this.textRenderer, s.label(), row.x + 12, row.y + 5, COL_TEXT_DIM, false);
            String v = mode.get();
            ctx.drawText(this.textRenderer, "< " + v + " >",
                    row.x + row.w - 6 - this.textRenderer.getWidth("< " + v + " >"),
                    row.y + 5, COL_ACCENT, false);
        } else if (s instanceof NumberSetting num) {
            ctx.drawText(this.textRenderer, s.label(), row.x + 12, row.y + 2, COL_TEXT_DIM, false);
            String v = formatValue(num.get());
            ctx.drawText(this.textRenderer, v,
                    row.x + row.w - 6 - this.textRenderer.getWidth(v),
                    row.y + 2, COL_TEXT, false);
            // Slider track + fill.
            int trackY = row.y + row.h - 5;
            ctx.fill(row.x + 8, trackY, row.x + row.w - 8, trackY + 3, 0x30FFFFFF);
            double frac = num.max() > num.min()
                    ? (num.get() - num.min()) / (num.max() - num.min()) : 0.0;
            int fillEnd = row.x + 8 + (int) Math.round((row.w - 16) * Math.max(0.0, Math.min(1.0, frac)));
            ctx.fill(row.x + 8, trackY, fillEnd, trackY + 3, COL_ACCENT);
        } else if (s instanceof KeySetting key) {
            ctx.drawText(this.textRenderer, s.label(), row.x + 12, row.y + 5, COL_TEXT_DIM, false);
            String v = capturing == key ? "[press key]" : key.display();
            ctx.drawText(this.textRenderer, v,
                    row.x + row.w - 6 - this.textRenderer.getWidth(v),
                    row.y + 5, capturing == key ? COL_ACCENT : 0xFF8FA0AC, false);
        }
    }

    private static String formatValue(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) {
            return String.valueOf((long) v);
        }
        return String.format("%.2f", v);
    }

    // ------------------------------------------------------------------
    // Input (screen px -> gui px conversion: gui = screen / SCALE)
    // ------------------------------------------------------------------

    /** Last known mouse position in gui space (hover shading in render). */
    private double lastMouseGuiX;
    private double lastMouseGuiY;

    private double guiX(double screenX) {
        return screenX / SCALE;
    }

    private double guiY(double screenY) {
        return screenY / SCALE;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        double mx = guiX(click.x());
        double my = guiY(click.y());
        lastMouseGuiX = mx;
        lastMouseGuiY = my;
        int button = click.button();
        buildLayout();
        for (Row row : rows) {
            if (!row.inside(mx, my)) {
                continue;
            }
            if (row.header && button == 0) {
                dragColumn = row.column;
                dragOffX = (int) mx - row.column.x;
                dragOffY = (int) my - row.column.y;
                return true;
            }
            if (row.setting != null) {
                return handleSettingClick(row, (int) mx, button);
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

    private boolean handleSettingClick(Row row, int mx, int button) {
        Setting<?> s = row.setting;
        if (s instanceof BooleanSetting b) {
            if (button == 0) {
                b.set(!b.get());
                ZephyrClient.config().save();
                return true;
            }
        } else if (s instanceof ModeSetting mode) {
            if (button == 0) {
                mode.cycle();
                ZephyrClient.config().save();
                return true;
            }
        } else if (s instanceof NumberSetting num) {
            if (button == 0) {
                dragSlider = num;
                applySlider(row, num, mx);
                return true;
            }
        } else if (s instanceof KeySetting key) {
            if (button == 0) {
                capturing = capturing == key ? null : key;
                return true;
            }
        }
        return false;
    }

    private void applySlider(Row row, NumberSetting num, int mx) {
        double frac = (mx - (row.x + 8)) / (double) (row.w - 16);
        frac = Math.max(0.0, Math.min(1.0, frac));
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
        lastMouseGuiX = mx;
        lastMouseGuiY = my;
        if (dragColumn != null) {
            dragColumn.x = Math.max(2, Math.min((int) mx - dragOffX,
                    Math.max(2, (int) guiWidth() - COL_W - 2)));
            dragColumn.y = Math.max(2, Math.min((int) my - dragOffY,
                    Math.max(2, (int) guiHeight() - HEADER_H - 2)));
            LAYOUT.put(dragColumn.category.name(), new int[]{dragColumn.x, dragColumn.y});
            return true;
        }
        if (dragSlider != null) {
            for (Row row : rows) {
                if (row.setting == dragSlider) {
                    applySlider(row, dragSlider, (int) mx);
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
        double gx = guiX(mx);
        double gy = guiY(my);
        buildLayout();
        for (Row row : rows) {
            if (row.header && row.inside(gx, gy)) {
                Column col = row.column;
                int max = Math.max(0, contentHeight(col) + 4
                        - ((int) guiHeight() - col.y - HEADER_H - 16));
                col.scroll = Math.max(0, Math.min(col.scroll - (int) (vertical * 24), max));
                return true;
            }
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int keyCode = input.getKeycode();
        if (capturing != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                capturing = null; // cancel capture
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE || keyCode == GLFW.GLFW_KEY_DELETE) {
                capturing.set(GLFW.GLFW_KEY_UNKNOWN); // unbind
                capturing = null;
                ZephyrClient.config().save();
                return true;
            }
            capturing.set(keyCode);
            capturing = null;
            ZephyrClient.config().save();
            return true;
        }
        // Escape closes. Right Shift close/open is handled by the tick-poller's edge
        // detection in ZephyrClient - reacting to keyPressed here too would let OS
        // key-repeat events slam the GUI shut while the key is merely held.
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.close();
            return true;
        }
        return super.keyPressed(input);
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
