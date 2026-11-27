package dev.zephyr.modules.hud;

import dev.zephyr.ZephyrClient;
import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.ModuleManager;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * ActiveHud - ultra-minimal plain-text module HUD: one line per active module,
 * "Name · label", dimmed gray while disabled-flash decays after a toggle.
 * No blur, no gradients, no animation; two ints and N draw calls.
 */
public final class ActiveHud extends Module {

    private final NumberSetting lineGap = num("gap", "Line gap px", 4, 16, 1, 9);
    private final BooleanSetting showCategoryHeaders = bool("headers", "Category headers", false);

    public ActiveHud() {
        super("HUD", ModuleCategory.RENDER, 0);
    }

    @Override
    public void onHudRender(DrawContext ctx, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) {
            return;
        }
        ModuleManager modules = ZephyrClient.modules();
        int x = 4;
        int y = 4;
        ModuleCategory lastCategory = null;

        List<Module> all = modules.all();
        for (Module m : all) {
            if (!m.isEnabled()) {
                continue;
            }
            if (showCategoryHeaders.get() && (lastCategory == null || lastCategory != m.category())) {
                ctx.drawText(client.textRenderer,
                        Text.literal(m.category().label()).formatted(Formatting.DARK_GRAY), x, y, 0xFF808080, false);
                y += lineGap.asInt();
                lastCategory = m.category();
            }
            int color = 0xFFFFFFFF;
            if (m.disableFlashTicks() > 0) {
                // Toggle feedback: brief gray flash instead of animations.
                color = 0xFF909090;
            }
            Text line = Text.literal(m.hudLabel());
            ctx.drawText(client.textRenderer, line, x, y, color, true);
            y += lineGap.asInt() + client.textRenderer.fontHeight;
        }
    }

    @Override
    public String hudLabel() {
        return name;
    }
}
