package dev.zephyr.modules.hud;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.NumberSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * Hotbar - Donut Client's streaming privacy module: obscures the item icons/counts in the
 * hotbar and the offhand slot with a frosted overlay whose strength is the "Blur amount"
 * setting (0-140%). Display only; inventory and server state are untouched.
 */
public final class HotbarPrivacy extends Module {

    private final NumberSetting amount = num("amount", "Blur amount %", 0, 140, 5, 90);

    public HotbarPrivacy() {
        super("Hotbar", ModuleCategory.RENDER, 0);
    }

    @Override
    public void onHudRender(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) {
            return;
        }
        double a = Math.min(1.0, amount.get() / 100.0);
        if (a <= 0.0) {
            return;
        }
        int alpha = (int) (255 * Math.min(1.0, a * 0.92));
        int color = (alpha << 24) | 0x20242C;
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        int x0 = w / 2 - 91, y0 = h - 22;
        // New root layer: without it the vanilla stack-count text (drawn in a later text
        // pass) still showed through on top of the cover.
        ctx.createNewRootLayer();
        // 9 slots (items sit inset from the frame), then the offhand slot on the left.
        for (int i = 0; i < 9; i++) {
            int sx = x0 + 3 + i * 20;
            ctx.fill(sx, y0 + 3, sx + 16 + 1, y0 + 3 + 17, color);
        }
        if (!mc.player.getOffHandStack().isEmpty()) {
            ctx.fill(x0 - 29 + 3, y0 + 3, x0 - 29 + 20, y0 + 20, color);
        }
    }

    @Override
    public String hudLabel() {
        return name + " · " + amount.asInt() + "%";
    }
}
