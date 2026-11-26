package dev.zephyr.modules.hud;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * ProgressBar - Donut Client's streaming overlay: three milestone tiles
 * (Overworld / Nether / End) plus a "Bases Found" counter. Milestones and the counter are
 * plain settings, so they are edited in the ClickGui (and persist in the config). The
 * overlay position is a corner setting and its size a percentage, like the original.
 */
public final class ProgressBar extends Module {

    private final BooleanSetting overworld = bool("overworld", "Overworld done", false);
    private final BooleanSetting nether = bool("nether", "Nether done", false);
    private final BooleanSetting end = bool("end", "End done", false);
    private final NumberSetting bases = num("bases", "Bases found", 0, 999, 1, 0);
    private final NumberSetting size = num("size", "Size %", 50, 200, 10, 100);
    private final NumberSetting posX = num("x", "Position X", 0, 2000, 5, 6);
    private final NumberSetting posY = num("y", "Position Y", 0, 1200, 5, 40);
    private final BooleanSetting outline = bool("outline", "Shiny outline", true);

    public ProgressBar() {
        super("ProgressBar", ModuleCategory.RENDER, 0);
    }

    @Override
    public void onHudRender(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) {
            return;
        }
        float s = size.get().floatValue() / 100f;
        int x = posX.asInt(), y = posY.asInt();
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x, y);
        ctx.getMatrices().scale(s, s);
        String[] names = {"Overworld", "Nether", "End"};
        boolean[] done = {overworld.get(), nether.get(), end.get()};
        int[] colors = {0xFF4CAF50, 0xFFB84A3A, 0xFF8E7CC3};
        // Tiles sized to the widest label ("Overworld" overflowed the old fixed 40px tile).
        int tile = 8;
        for (String label : names) {
            tile = Math.max(tile, mc.textRenderer.getWidth(label) + 8);
        }
        int w = 3 * (tile + 4) + 4;
        if (outline.get()) {
            int glow = 0xFF000000 | (int) (180 + 70 * Math.sin(System.currentTimeMillis() / 400.0)) << 8 | 0xFF;
            ctx.fill(-2, -2, w + 2, 62, glow);
        }
        ctx.fill(0, 0, w, 60, 0xCC101216);
        for (int i = 0; i < 3; i++) {
            int tx = 4 + i * (tile + 4);
            ctx.fill(tx, 4, tx + tile, 38, colors[i]);
            ctx.drawText(mc.textRenderer, names[i], tx + tile / 2 - mc.textRenderer.getWidth(names[i]) / 2, 18,
                    0xFFFFFFFF, true);
            if (done[i]) {
                ctx.drawText(mc.textRenderer, "X", tx + tile / 2 - 3, 6, 0xFFFF3030, true);
            }
        }
        ctx.drawText(mc.textRenderer, "Bases Found: ", 6, 44, 0xFFFFFFFF, true);
        ctx.drawText(mc.textRenderer, String.valueOf(bases.asInt()),
                6 + mc.textRenderer.getWidth("Bases Found: "), 44, 0xFFFF4040, true);
        ctx.getMatrices().popMatrix();
    }

    @Override
    public String hudLabel() {
        int d = (overworld.get() ? 1 : 0) + (nether.get() ? 1 : 0) + (end.get() ? 1 : 0);
        return name + " · " + d + "/3 · " + bases.asInt() + " bases";
    }
}
