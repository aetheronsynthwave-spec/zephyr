package dev.zephyr.gui;

import dev.zephyr.ZephyrClient;
import dev.zephyr.ZephyrConfig;
import dev.zephyr.gui.render.Ui;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Config manager: save the current setup under a name, load or delete saved configs,
 * start a fresh one, generate a complete config for a play style from the Legit/Smart
 * profiles, and share configs as text (copy to clipboard to post it anywhere, or import
 * one someone sent you from the clipboard). Nothing is uploaded anywhere.
 */
public final class ConfigScreen extends Screen {

    private final Screen parent;
    private String name = "";
    private String status = "";
    private int statusColor = Ui.TEXT_DIM;
    private String confirmDelete;
    private int scroll;

    private record Button(float x, float y, float w, float h, String label, Runnable action, int color) {
        boolean inside(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final List<Button> buttons = new ArrayList<>();
    private double mouseX;
    private double mouseY;

    public ConfigScreen(Screen parent) {
        super(Text.literal("Zephyr configs"));
        this.parent = parent;
    }

    private float scale() {
        return Math.max(0.5f, ClickGuiScreen.currentScale() * 1.6f);
    }

    private ZephyrConfig cfg() {
        return ZephyrClient.config();
    }

    private void ok(String msg) {
        status = msg;
        statusColor = 0xFF6BE39A;
    }

    private void err(String msg) {
        status = msg;
        statusColor = 0xFFFF7070;
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        float sc = scale();
        mouseX = mx / sc;
        mouseY = my / sc;
        buttons.clear();
        ctx.fill(0, 0, this.width, this.height, 0x9006080C);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().scale(sc, sc);
        Ui.beginFrame(sc);
        try {
            float gw = this.width / sc, gh = this.height / sc;
            float w = 300, h = Math.min(gh - 20, 300);
            float x = (gw - w) / 2f, y = (gh - h) / 2f;
            Ui.shadow(ctx, x, y, w, h, 12, 14, 0x99000000);
            Ui.roundRect(ctx, x, y, w, h, 12, Ui.BG_2);
            Ui.logo(ctx, x + 12, y + 10, 16, Ui.ACCENT);
            Ui.text(ctx, "Configs", x + 34, y + 13, 11f, Ui.TEXT, true);
            button(ctx, x + w - 62, y + 10, 50, 16, "Back", () -> this.client.setScreen(parent), Ui.TEXT_DIM);

            // Name field + save / new
            float fy = y + 36;
            Ui.roundRect(ctx, x + 12, fy, w - 132, 18, 6, 0xFF0E1117);
            String shown = name.isEmpty() ? "config name..." : name;
            float tw = Ui.text(ctx, shown, x + 20, fy + 5, 8.5f, name.isEmpty() ? Ui.TEXT_FAINT : Ui.TEXT, false);
            if ((System.currentTimeMillis() / 500) % 2 == 0) {
                Ui.rect(ctx, x + 21 + (name.isEmpty() ? 0 : tw), fy + 4, 1, 10, Ui.ACCENT);
            }
            button(ctx, x + w - 114, fy, 50, 18, "Save", this::saveCurrent, Ui.ACCENT);
            boolean confirmNew = "__new__".equals(confirmDelete);
            button(ctx, x + w - 60, fy, 48, 18, confirmNew ? "Sure?" : "New", () -> {
                if (!"__new__".equals(confirmDelete)) {
                    confirmDelete = "__new__"; // second click: wipes the current setup
                    err("Click again to reset everything (save first if you want to keep it)");
                    return;
                }
                confirmDelete = null;
                cfg().resetAll();
                cfg().save();
                ok("Started a fresh config (everything off, defaults)");
            }, confirmNew ? 0xFFFF7070 : Ui.TEXT_DIM);

            // Generate
            float gy = fy + 28;
            Ui.text(ctx, "Generate", x + 12, gy, 8f, Ui.TEXT_DIM, true);
            float bx = x + 12;
            float by = gy + 11;
            for (String g : ZephyrConfig.GENERATORS) {
                float bw = Ui.textWidth(g, 8f, false) + 14;
                if (bx + bw > x + w - 12) {
                    bx = x + 12;
                    by += 20;
                }
                button(ctx, bx, by, bw, 16, g, () -> {
                    try {
                        String saved = cfg().generate(g);
                        ok("Generated and loaded \"" + saved + "\"");
                    } catch (Exception e) {
                        err("Generate failed: " + e.getMessage());
                    }
                }, Ui.ACCENT_2);
                bx += bw + 6;
            }

            // Share
            float sy = by + 26;
            Ui.text(ctx, "Share", x + 12, sy, 8f, Ui.TEXT_DIM, true);
            button(ctx, x + 12, sy + 11, 132, 16, "Copy current (to post)", () -> {
                this.client.keyboard.setClipboard(cfg().toJson(false));
                ok("Config copied - paste it anywhere to share it");
            }, Ui.ACCENT);
            button(ctx, x + 150, sy + 11, 138, 16, "Import from clipboard", () -> {
                try {
                    cfg().applyJson(this.client.keyboard.getClipboard(), true);
                    cfg().save();
                    ok("Imported config from clipboard");
                } catch (Exception e) {
                    err("Clipboard does not contain a Zephyr config");
                }
            }, Ui.ACCENT);

            // Saved list
            float ly = sy + 36;
            Ui.text(ctx, "Saved", x + 12, ly, 8f, Ui.TEXT_DIM, true);
            List<String> saved = cfg().list();
            float listTop = ly + 11;
            float listH = y + h - 26 - listTop;
            int rows = Math.max(1, (int) (listH / 20));
            scroll = Math.max(0, Math.min(scroll, Math.max(0, saved.size() - rows)));
            if (saved.isEmpty()) {
                Ui.text(ctx, "No saved configs yet - type a name and press Save.", x + 14, listTop + 4, 8f,
                        Ui.TEXT_FAINT, false);
            }
            for (int i = 0; i < rows && i + scroll < saved.size(); i++) {
                String n = saved.get(i + scroll);
                float ry = listTop + i * 20;
                Ui.roundRect(ctx, x + 12, ry, w - 24, 17, 5, 0xFF161B24);
                Ui.text(ctx, n, x + 20, ry + 5, 8.5f, Ui.TEXT, false);
                button(ctx, x + w - 118, ry + 1.5f, 48, 14, "Load", () -> {
                    try {
                        cfg().loadNamed(n);
                        name = n;
                        ok("Loaded \"" + n + "\"");
                    } catch (Exception e) {
                        err("Could not load \"" + n + "\"");
                    }
                }, Ui.ACCENT);
                boolean confirm = n.equals(confirmDelete);
                button(ctx, x + w - 66, ry + 1.5f, 50, 14, confirm ? "Sure?" : "Delete", () -> {
                    if (!n.equals(confirmDelete)) {
                        confirmDelete = n; // second click deletes
                        return;
                    }
                    try {
                        cfg().delete(n);
                        ok("Deleted \"" + n + "\"");
                    } catch (Exception e) {
                        err("Could not delete \"" + n + "\"");
                    }
                    confirmDelete = null;
                }, confirm ? 0xFFFF7070 : Ui.TEXT_DIM);
            }
            if (!status.isEmpty()) {
                Ui.text(ctx, status, x + 12, y + h - 16, 8f, statusColor, false);
            }
        } finally {
            ctx.getMatrices().popMatrix();
        }
    }

    /** Draws a button now and registers its click area for this frame. */
    private void button(DrawContext ctx, float x, float y, float w, float h, String label, Runnable action, int color) {
        boolean hov = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        Ui.roundRect(ctx, x, y, w, h, Math.min(6, h / 2f), hov ? Ui.alpha(color, 0.30f) : Ui.alpha(color, 0.14f));
        Ui.textCentered(ctx, label, x + w / 2f, y + (h - 7.5f) / 2f, 8f, hov ? Ui.TEXT : color, false);
        buttons.add(new Button(x, y, w, h, label, action, color));
    }

    private void saveCurrent() {
        if (name.isBlank()) {
            err("Type a name first");
            return;
        }
        try {
            cfg().saveAs(name);
            ok("Saved \"" + ZephyrConfig.sanitize(name) + "\"");
        } catch (Exception e) {
            err("Save failed: " + e.getMessage());
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        float sc = scale();
        double mx = click.x() / sc, my = click.y() / sc;
        if (click.button() == 0) {
            for (Button b : new ArrayList<>(buttons)) {
                if (b.inside(mx, my)) {
                    b.action().run();
                    return true;
                }
            }
            confirmDelete = null;
        }
        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scroll -= (int) Math.signum(v);
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int k = input.getKeycode();
        if (k == GLFW.GLFW_KEY_ESCAPE) {
            this.client.setScreen(parent);
            return true;
        }
        if (k == GLFW.GLFW_KEY_BACKSPACE && !name.isEmpty()) {
            name = (input.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0 ? "" : name.substring(0, name.length() - 1);
            return true;
        }
        if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) {
            saveCurrent();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (input.isValidChar() && name.length() < 40) {
            name += input.asString();
            return true;
        }
        return false;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
