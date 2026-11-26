package dev.zephyr.modules.render;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.ModeSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.target.TargetCriteria;
import dev.zephyr.core.target.TargetManager;
import dev.zephyr.core.util.ItemUtil;
import dev.zephyr.gui.render.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.MathHelper;

import java.util.List;

/**
 * TargetHUD - a small, smooth panel on the HUD (not in the world) showing the current
 * target: head, name, animated health bar with absorption, distance, totem status and
 * their armour + held items. It slides/fades in and out when a target appears or is lost.
 */
public final class TargetHud extends Module {

    private final ModeSetting position = s(new ModeSetting("pos", "Position",
            List.of("NEAR_CROSSHAIR", "LEFT", "RIGHT", "CUSTOM"), "NEAR_CROSSHAIR"));
    private final NumberSetting customX = num("x", "Custom X", 0, 2000, 5, 20);
    private final NumberSetting customY = num("y", "Custom Y", 0, 1200, 5, 120);
    private final NumberSetting scale = num("scale", "Scale", 0.6, 2.0, 0.05, 1.0);
    private final NumberSetting range = num("range", "Acquire range", 4.0, 64.0, 1.0, 16.0);
    private final BooleanSetting showItems = bool("items", "Show armour + items", true);

    private final TargetCriteria criteria = new TargetCriteria().range(16.0).playersOnly(true);

    private LivingEntity shown;
    private float fade;
    private float hpAnim = 1f;
    private float absAnim;
    private long lastNanos;

    private Object nameFor;
    private float nameMaxW = -1f;
    private String nameCached = "";
    private int infoHp = Integer.MIN_VALUE;
    private int infoDist = Integer.MIN_VALUE;
    private String infoCached;

    public TargetHud() {
        super("TargetHUD", ModuleCategory.RENDER, 0);
    }

    @Override
    protected void onDisable() {
        shown = null;
        fade = 0;
    }

    @Override
    public void onTick(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            return;
        }
        criteria.range = range.get();
        TargetManager.acquire(client, criteria);
        TargetManager.cleanup();
    }

    @Override
    public void onHudRender(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) {
            return;
        }
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1.0e9f);
        lastNanos = now;

        LivingEntity t = TargetManager.currentTarget();
        boolean valid = t != null && t.isAlive() && mc.player.squaredDistanceTo(t) < 48 * 48;
        if (valid) {
            if (shown != t) {
                hpAnim = MathHelper.clamp(t.getHealth() / Math.max(1f, t.getMaxHealth()), 0f, 1f);
            }
            shown = t;
        }
        fade = Ui.approach(fade, valid ? 1f : 0f, 14f, dt);
        if (shown == null || fade < 0.01f) {
            if (!valid) {
                shown = null;
            }
            return;
        }
        LivingEntity e = shown;

        float s = scale.get().floatValue();
        float w = 132, h = showItems.get() ? 58 : 42;
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        float px, py;
        switch (position.get()) {
            case "LEFT" -> { px = 8; py = sh / 2f - h * s / 2f; }
            case "RIGHT" -> { px = sw - 8 - w * s; py = sh / 2f - h * s / 2f; }
            case "CUSTOM" -> { px = customX.get().floatValue(); py = customY.get().floatValue(); }
            default -> { px = sw / 2f + 16; py = sh / 2f + 12; }
        }

        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(px, py + (1f - fade) * 6f);
        ctx.getMatrices().scale(s, s);
        Ui.beginFrame(s);

        Ui.shadow(ctx, 0, 0, w, h, 8, 8, Ui.alpha(0x80000000, fade));
        Ui.roundRect(ctx, 0, 0, w, h, 8, Ui.alpha(Ui.BG, fade));

        // Head (players) or a neutral badge.
        float head = 26;
        Ui.roundRect(ctx, 7, 7, head + 2, head + 2, 6, Ui.alpha(0xFF0B0E13, fade));
        if (e instanceof AbstractClientPlayerEntity p) {
            PlayerSkinDrawer.draw(ctx, p.getSkin(), 8, 8, (int) head, Ui.alpha(0xFFFFFFFF, fade));
        } else {
            Ui.logo(ctx, 10, 10, head - 4, Ui.alpha(Ui.TEXT_FAINT, fade));
        }

        float tx = 7 + head + 9;
        // Name truncation re-measured the text in a loop every frame; cache it per
        // (entity, available width) and rebuild only when either changes.
        float nameMax = w - tx - 26;
        if (e != nameFor || nameMax != nameMaxW) {
            String full = e.getName().getString();
            while (full.length() > 3 && Ui.textWidth(full, 9.5f, true) > nameMax) {
                full = full.substring(0, full.length() - 2) + "…";
            }
            nameCached = full;
            nameFor = e;
            nameMaxW = nameMax;
        }
        String name = nameCached;
        Ui.text(ctx, name, tx, 8, 9.5f, Ui.alpha(e.hurtTime > 0 ? 0xFFFF8A8A : Ui.TEXT, fade), true);

        if (e instanceof PlayerEntity p && ItemUtil.isTotem(p.getOffHandStack())) {
            Ui.pill(ctx, w - 23, 7, 16, 10, Ui.alpha(0x33FFC63C, fade));
            Ui.textCentered(ctx, "T", w - 15, 8.5f, 7.5f, Ui.alpha(0xFFFFC63C, fade), true);
        }

        float hp = e.getHealth();
        float abs = e.getAbsorptionAmount();
        float max = Math.max(1f, e.getMaxHealth());
        hpAnim = Ui.approach(hpAnim, MathHelper.clamp(hp / max, 0f, 1f), 9f, dt);
        absAnim = Ui.approach(absAnim, MathHelper.clamp(abs / max, 0f, 1f), 9f, dt);
        double dist = mc.player.distanceTo(e);
        // String.format twice per frame was the costliest call in this HUD; rebuild the
        // line only when the displayed (one-decimal) values change.
        int hpTenths = Math.round((hp + abs) * 10f);
        int distTenths = (int) Math.round(dist * 10.0);
        if (hpTenths != infoHp || distTenths != infoDist || infoCached == null) {
            infoHp = hpTenths;
            infoDist = distTenths;
            infoCached = (hpTenths / 10) + "." + Math.abs(hpTenths % 10) + " hp  ·  "
                    + (distTenths / 10) + "." + (distTenths % 10) + "m";
        }
        String info = infoCached;
        Ui.text(ctx, info, tx, 20, 8f, Ui.alpha(Ui.TEXT_DIM, fade), false);

        float barX = tx, barY = 30, barW = w - tx - 8, barH = 4;
        Ui.pill(ctx, barX, barY, barW, barH, Ui.alpha(0xFF262C38, fade));
        int hpColor = Ui.mix(0xFFFF5A5A, 0xFF4BE38C, hpAnim * 1.4f - 0.2f);
        if (hpAnim > 0.01f) {
            Ui.pill(ctx, barX, barY, Math.max(barH, barW * hpAnim), barH, Ui.alpha(hpColor, fade));
        }
        if (absAnim > 0.01f) {
            float aw = Math.min(barW, barW * absAnim);
            Ui.pill(ctx, barX + barW - aw, barY, aw, barH, Ui.alpha(0xCCFFC63C, fade));
        }

        if (showItems.get()) {
            ItemStack[] items = {
                    e.getMainHandStack(),
                    e.getEquippedStack(EquipmentSlot.HEAD), e.getEquippedStack(EquipmentSlot.CHEST),
                    e.getEquippedStack(EquipmentSlot.LEGS), e.getEquippedStack(EquipmentSlot.FEET),
                    e.getOffHandStack()};
            float ix = 8;
            for (ItemStack it : items) {
                Ui.roundRect(ctx, ix, 39, 17, 15, 4, Ui.alpha(0x14FFFFFF, fade));
                if (!it.isEmpty() && fade > 0.5f) {
                    ctx.getMatrices().pushMatrix();
                    ctx.getMatrices().translate(ix + 1.5f, 40f);
                    ctx.getMatrices().scale(0.85f, 0.85f);
                    ctx.drawItem(it, 0, 0);
                    ctx.getMatrices().popMatrix();
                }
                ix += 20;
            }
        }
        ctx.getMatrices().popMatrix();
    }

    @Override
    public String hudLabel() {
        return name;
    }
}
