package dev.zephyr.modules.hud;

import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.hud.HudModule;
import dev.zephyr.core.settings.BooleanSetting;
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
 * coloured green to red. Item icons use the vanilla item renderer, so they are drawn at
 * full opacity regardless of the element's opacity setting.
 */
public final class ArmorHud extends HudModule {

    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final int CELL = 20;

    private final BooleanSetting hands = bool("hands", "Show held items", true);
    private final BooleanSetting percent = bool("percent", "Durability %", true);

    /** Slot stacks gathered in measure(), reused every frame (no per-frame allocation). */
    private final ItemStack[] stacks = new ItemStack[6];
    private final float[] size = new float[2];
    private int slotCount;
    private int visibleCount;

    public ArmorHud() {
        super("ArmorHUD", ModuleCategory.RENDER, 50, 92, "CENTER");
    }

    @Override
    protected float[] measure() {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        visibleCount = 0;
        slotCount = 0;
        if (p == null || p.isSpectator()) {
            size[0] = 0f;
            size[1] = 0f;
            return size;
        }
        boolean held = hands.get();
        if (held) {
            stacks[slotCount++] = p.getMainHandStack();
        }
        for (EquipmentSlot slot : ARMOR) {
            stacks[slotCount++] = p.getEquippedStack(slot);
        }
        if (held) {
            stacks[slotCount++] = p.getOffHandStack();
        }
        for (int k = 0; k < slotCount; k++) {
            if (!stacks[k].isEmpty()) {
                visibleCount++;
            }
        }
        size[0] = visibleCount * CELL;
        size[1] = visibleCount == 0 ? 0f : percent.get() ? 24f : 16f;
        return size;
    }

    @Override
    protected void renderContent(DrawContext ctx, float x, float y, float alpha) {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        boolean pct = percent.get();
        int slot = 0;
        for (int k = 0; k < slotCount; k++) {
            ItemStack s = stacks[k];
            if (s.isEmpty()) {
                continue;
            }
            int ix = Math.round(x) + slot * CELL + 2;
            int iy = Math.round(y);
            ctx.drawItem(s, ix, iy);
            ctx.drawStackOverlay(tr, s, ix, iy);
            if (pct && s.isDamageable() && s.getMaxDamage() > 0) {
                float frac = 1f - (float) s.getDamage() / s.getMaxDamage();
                String txt = Math.round(frac * 100f) + "%";
                int tw = tr.getWidth(txt);
                ctx.getMatrices().pushMatrix();
                ctx.getMatrices().translate(ix + 8f, iy + 18f);
                ctx.getMatrices().scale(0.6f, 0.6f);
                ctx.drawText(tr, txt, -tw / 2, 0, durabilityColor(frac), true);
                ctx.getMatrices().popMatrix();
            }
            slot++;
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
