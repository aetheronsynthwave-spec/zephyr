package dev.zephyr.gui;

import dev.zephyr.ZephyrClient;
import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleManager;
import dev.zephyr.core.hud.HudModule;
import dev.zephyr.gui.render.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * HUD editor - every enabled HUD element is drawn exactly as in game over a dimmed world,
 * outlined in the accent colour with its name. Left-drag moves an element, the mouse wheel
 * over an element changes its scale, and Escape closes the editor and saves the config.
 * Positions are stored as percentages, so they hold at any window size.
 */
public final class HudEditorScreen extends Screen {

    private static final String HINT = "Drag to move  ·  Esc to close  ·  Scroll to resize";
    private static final float HINT_SIZE = 8.5f;
    private static final float LABEL_SIZE = 8f;

    private long openedAt;
    private HudModule dragging;
    private double dragOffX;
    private double dragOffY;

    public HudEditorScreen() {
        super(Text.literal("Zephyr HUD"));
    }

    @Override
    protected void init() {
        HudModule.setEditing(true);
        openedAt = System.nanoTime();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        long now = System.nanoTime();
        float open = Math.min(1f, (now - openedAt) / 1.8e8f);
        float ease = 1f - (1f - open) * (1f - open) * (1f - open);

        // Same backdrop as the ClickGui.
        ctx.fill(0, 0, this.width, this.height, Ui.alpha(0x8C06080C, ease));
        Ui.beginFrame(1f);

        // Draw every enabled element with its real renderer.
        List<Module> all = ZephyrClient.modules().all();
        int enabled = 0;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) instanceof HudModule hud && hud.isEnabled()) {
                enabled++;
                try {
                    hud.onHudRender(ctx, delta);
                } catch (RuntimeException e) {
                    ModuleManager.recordFault(hud, "onHudRender", e);
                }
            }
        }
        Ui.beginFrame(1f); // elements leave their own scale behind; editor chrome is 1:1

        // Outline and name label per element.
        HudModule hovered = hitTest(mouseX, mouseY);
        for (int i = 0; i < all.size(); i++) {
            if (!(all.get(i) instanceof HudModule hud) || !hud.isEnabled()) {
                continue;
            }
            float[] b = hud.screenBounds();
            if (b == null) {
                continue;
            }
            boolean active = hud == dragging || hud == hovered;
            int accent = Ui.alpha(hud.accent(), ease * (active ? 1f : 0.6f));
            float x = b[0] - 2f;
            float y = b[1] - 2f;
            float w = b[2] + 4f;
            float h = b[3] + 4f;
            Ui.rect(ctx, x, y, w, 1.5f, accent);
            Ui.rect(ctx, x, y + h - 1.5f, w, 1.5f, accent);
            Ui.rect(ctx, x, y, 1.5f, h, accent);
            Ui.rect(ctx, x + w - 1.5f, y, 1.5f, h, accent);

            String label = hud.name();
            float tagW = Ui.textWidth(label, LABEL_SIZE, true) + 10f;
            float tagY = y - 13f >= 2f ? y - 13f : y + h + 2f;
            Ui.roundRect(ctx, x, tagY, tagW, 11f, 5f, Ui.alpha(Ui.BG_2, ease));
            Ui.text(ctx, label, x + 5f, tagY + 1.5f, LABEL_SIZE, Ui.alpha(Ui.TEXT, ease), true);
        }

        // Hint bar, in the ClickGui top-bar style, at the bottom centre.
        MinecraftClient client = MinecraftClient.getInstance();
        String hint;
        if (client.player == null) {
            hint = "Join a world to preview the HUD  ·  Esc to close";
        } else if (enabled == 0) {
            hint = "No HUD modules are enabled  ·  Enable one in the ClickGui  ·  Esc to close";
        } else {
            hint = HINT;
        }
        float hintW = Ui.textWidth(hint, HINT_SIZE, false) + 28f;
        float hintX = (this.width - hintW) / 2f;
        float hintY = this.height - 30f;
        Ui.shadow(ctx, hintX, hintY, hintW, 20f, 10f, 10f, Ui.alpha(0x99000000, ease));
        Ui.pill(ctx, hintX, hintY, hintW, 20f, Ui.alpha(Ui.BG_2, ease));
        Ui.textCentered(ctx, hint, this.width / 2f, hintY + 6f, HINT_SIZE, Ui.alpha(Ui.TEXT_DIM, ease), false);
    }

    /** Topmost enabled element under the point (screen GUI px), or null. */
    private HudModule hitTest(double mx, double my) {
        List<Module> all = ZephyrClient.modules().all();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i) instanceof HudModule hud && hud.isEnabled()) {
                float[] b = hud.screenBounds();
                if (b != null && mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3]) {
                    return hud;
                }
            }
        }
        return null;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        if (click.button() == 0) {
            HudModule hit = hitTest(click.x(), click.y());
            if (hit != null) {
                float[] b = hit.screenBounds();
                dragging = hit;
                dragOffX = click.x() - b[0];
                dragOffY = click.y() - b[1];
                return true;
            }
        }
        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (dragging != null) {
            dragging.moveTo((float) (click.x() - dragOffX), (float) (click.y() - dragOffY));
            return true;
        }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (dragging != null) {
            dragging = null;
            ZephyrClient.config().save();
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        HudModule hit = hitTest(mx, my);
        if (hit != null) {
            hit.adjustScale(vertical > 0 ? 0.05 : -0.05);
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.getKeycode() == GLFW.GLFW_KEY_ESCAPE) {
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
        HudModule.setEditing(false);
        dragging = null;
        ZephyrClient.config().save();
        super.removed();
    }
}
