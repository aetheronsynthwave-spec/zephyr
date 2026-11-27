package dev.zephyr.modules.render;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.target.TargetManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * TargetHud - single plain-text strip at the top center: target name, live HP and
 * hurt-time. No textures, no gradients; two draw calls per frame.
 */
public final class TargetHud extends Module {

    public TargetHud() {
        super("TargetHUD", ModuleCategory.RENDER, 0);
    }

    @Override
    public void onHudRender(DrawContext ctx, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        LivingEntity target = TargetManager.currentTarget();
        if (client.player == null || target == null || !target.isAlive()) {
            return;
        }
        int screenW = client.getWindow().getScaledWidth();
        String line = target.getDisplayName().getString()
                + "  " + String.format("%.1f", target.getHealth()) + "hp"
                + (target.hurtTime > 0 ? "  [hurt " + target.hurtTime + "]" : "");
        Text text = Text.literal(line).formatted(Formatting.WHITE);
        int width = client.textRenderer.getWidth(text);
        ctx.drawText(client.textRenderer, text, (screenW - width) / 2, 6, 0xFFFFFFFF, true);
    }

    @Override
    public String hudLabel() {
        LivingEntity target = TargetManager.currentTarget();
        return name + " · " + (target != null ? target.getDisplayName().getString() : "-");
    }
}
