package dev.zephyr.modules.hud;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;

/**
 * ArmorHUD - your helmet, chestplate, leggings and boots (plus main hand / offhand) drawn
 * as item icons with the vanilla durability bar and stack count, centred above the hotbar.
 * Optionally prints the remaining durability as a percentage under each damageable item,
 * coloured green to red.
 */
public final class ArmorHud extends Module {

    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final BooleanSetting hands = bool("hands", "Show held items", true);
    private final BooleanSetting percent = bool("percent", "Durability %", true);
    private final NumberSetting yOffset = num("yOffset", "Height above bottom", 40, 160, 1, 66);

    public ArmorHud() {
        super("ArmorHUD", ModuleCategory.RENDER, 0);
    }

    @Override
    public void onHudRender(DrawContext ctx, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity p = client.player;
        if (p == null || client.options.hudHidden || p.isSpectator()) {
            return;
        }
        ItemStack[] stacks = new ItemStack[hands.get() ? 6 : 4];
        int n = 0;
        if (hands.get()) {
            stacks[n++] = p.getMainHandStack();
        }
        for (EquipmentSlot slot : ARMOR) {
            stacks[n++] = p.getEquippedStack(slot);
        }
        if (hands.get()) {
            stacks[n++] = p.getOffHandStack();
        }
        int visible = 0;
        for (ItemStack s : stacks) {
            if (!s.isEmpty()) {
                visible++;
            }
        }
        if (visible == 0) {
            return;
        }
        TextRenderer tr = client.textRenderer;
        int cell = 20;
        int total = visible * cell;
        int x = client.getWindow().getScaledWidth() / 2 - total / 2 + 2;
        int y = client.getWindow().getScaledHeight() - yOffset.get().intValue();
        for (ItemStack s : stacks) {
            if (s.isEmpty()) {
                continue;
            }
            ctx.drawItem(s, x, y);
            ctx.drawStackOverlay(tr, s, x, y);
            if (percent.get() && s.isDamageable() && s.getMaxDamage() > 0) {
                float frac = 1f - (float) s.getDamage() / s.getMaxDamage();
                String txt = Math.round(frac * 100f) + "%";
                int color = durabilityColor(frac);
                int tw = tr.getWidth(txt);
                ctx.getMatrices().pushMatrix();
                ctx.getMatrices().translate(x + 8f, y + 18f);
                ctx.getMatrices().scale(0.6f, 0.6f);
                ctx.drawText(tr, txt, -tw / 2, 0, color, true);
                ctx.getMatrices().popMatrix();
            }
            x += cell;
        }
    }

    /** Green (full) -> amber -> red (empty), opaque ARGB. */
    static int durabilityColor(float frac) {
        frac = Math.max(0f, Math.min(1f, frac));
        int r, g;
        if (frac > 0.5f) {
            r = (int) ((1f - frac) * 2f * 255f);
            g = 255;
        } else {
            r = 255;
            g = (int) (frac * 2f * 255f);
        }
        return 0xFF000000 | (r << 16) | (g << 8) | 0x40;
    }
}
