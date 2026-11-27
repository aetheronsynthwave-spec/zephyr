package dev.zephyr.modules.render;

import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.hud.HudModule;
import dev.zephyr.core.settings.BooleanSetting;
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

/**
 * TargetHUD - a small, smooth panel on the HUD (not in the world) showing the current
 * target: head, name, animated health bar with absorption, distance, totem status and
 * their armour + held items. It slides/fades in and out when a target appears or is lost.
 * Position, scale and look come from the shared HUD settings (default: near the crosshair).
 */
public final class TargetHud extends HudModule {

    private static final float WIDTH = 132f;
    private static final float HEAD = 26f;
    private static final float TEXT_X = 7f + HEAD + 9f;

    private final NumberSetting range = num("range", "Acquire range", 4.0, 64.0, 1.0, 16.0);
    private final BooleanSetting showItems = bool("items", "Show armour + items", true);

    private final TargetCriteria criteria = new TargetCriteria().range(16.0).playersOnly(true);

    private LivingEntity shown;
    private float fade;
    private float hpAnim = 1f;
    private float absAnim;
    private long lastNanos;
    private float frameDt;
    private float slideY;

    private final float[] size = new float[2];
    private final ItemStack[] itemBuf = new ItemStack[6];

    private Object nameFor;
    private float nameMaxW = -1f;
    private String nameCached = "";
    private int infoHp = Integer.MIN_VALUE;
    private int infoDist = Integer.MIN_VALUE;
    private String infoCached;

    public TargetHud() {
        super("TargetHUD", ModuleCategory.RENDER, 50, 50, "TOP_LEFT");
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

    /** Runs the target fade/slide animation and reports the panel size for this frame. */
    @Override
    protected float[] measure() {
        MinecraftClient mc = MinecraftClient.getInstance();
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1.0e9f);
        lastNanos = now;
        frameDt = dt;

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
            size[0] = 0f;
            size[1] = 0f;
            return size;
        }
        slideY = (1f - fade) * 6f;
        size[0] = WIDTH;
        size[1] = showItems.get() ? 58f : 42f;
        return size;
    }

    @Override
    protected float alphaMul() {
        return fade;
    }

    @Override
    protected float offsetY() {
        return slideY;
    }

    @Override
    protected void renderContent(DrawContext ctx, float x, float y, float alpha) {
        MinecraftClient mc = MinecraftClient.getInstance();
        LivingEntity e = shown;
        if (e == null) {
            return;
        }
        float dt = frameDt;

        // Head (players) or a neutral badge.
        Ui.roundRect(ctx, x + 7, y + 7, HEAD + 2, HEAD + 2, 6, Ui.alpha(0xFF0B0E13, alpha));
        if (e instanceof AbstractClientPlayerEntity p) {
            PlayerSkinDrawer.draw(ctx, p.getSkin(), Math.round(x) + 8, Math.round(y) + 8, (int) HEAD,
                    Ui.alpha(0xFFFFFFFF, alpha));
        } else {
            Ui.logo(ctx, x + 10, y + 10, HEAD - 4, Ui.alpha(textFaint(), alpha));
        }

        // Name truncation re-measured the text in a loop every frame; cache it per
        // (entity, available width) and rebuild only when either changes.
        float nameMax = WIDTH - TEXT_X - 26;
        if (e != nameFor || nameMax != nameMaxW) {
            String full = e.getName().getString();
            while (full.length() > 3 && Ui.textWidth(full, 9.5f, true) > nameMax) {
                full = full.substring(0, full.length() - 2) + "…";
            }
            nameCached = full;
            nameFor = e;
            nameMaxW = nameMax;
        }
        drawText(ctx, nameCached, x + TEXT_X, y + 8, 9.5f,
                e.hurtTime > 0 ? 0xFFFF8A8A : text(), true, alpha);

        if (e instanceof PlayerEntity p && ItemUtil.isTotem(p.getOffHandStack())) {
            Ui.pill(ctx, x + WIDTH - 23, y + 7, 16, 10, Ui.alpha(0x33FFC63C, alpha));
            drawTextCentered(ctx, "T", x + WIDTH - 15, y + 8.5f, 7.5f, 0xFFFFC63C, true, alpha);
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
        drawText(ctx, infoCached, x + TEXT_X, y + 20, 8f, textDim(), false, alpha);

        float barX = x + TEXT_X;
        float barY = y + 30;
        float barW = WIDTH - TEXT_X - 8;
        float barH = 4;
        Ui.pill(ctx, barX, barY, barW, barH, Ui.alpha(0xFF262C38, alpha));
        int hpColor = Ui.mix(0xFFFF5A5A, 0xFF4BE38C, hpAnim * 1.4f - 0.2f);
        if (hpAnim > 0.01f) {
            Ui.pill(ctx, barX, barY, Math.max(barH, barW * hpAnim), barH, Ui.alpha(hpColor, alpha));
        }
        if (absAnim > 0.01f) {
            float aw = Math.min(barW, barW * absAnim);
            Ui.pill(ctx, barX + barW - aw, barY, aw, barH, Ui.alpha(0xCCFFC63C, alpha));
        }

        if (showItems.get()) {
            itemBuf[0] = e.getMainHandStack();
            itemBuf[1] = e.getEquippedStack(EquipmentSlot.HEAD);
            itemBuf[2] = e.getEquippedStack(EquipmentSlot.CHEST);
            itemBuf[3] = e.getEquippedStack(EquipmentSlot.LEGS);
            itemBuf[4] = e.getEquippedStack(EquipmentSlot.FEET);
            itemBuf[5] = e.getOffHandStack();
            float ix = x + 8;
            for (ItemStack it : itemBuf) {
                Ui.roundRect(ctx, ix, y + 39, 17, 15, 4, Ui.alpha(0x14FFFFFF, alpha));
                if (!it.isEmpty() && alpha > 0.5f) {
                    ctx.getMatrices().pushMatrix();
                    ctx.getMatrices().translate(ix + 1.5f, y + 40f);
                    ctx.getMatrices().scale(0.85f, 0.85f);
                    ctx.drawItem(it, 0, 0);
                    ctx.getMatrices().popMatrix();
                }
                ix += 20;
            }
        }
    }

    @Override
    public String hudLabel() {
        return name;
    }
}
