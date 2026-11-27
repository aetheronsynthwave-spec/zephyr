package dev.zephyr.modules.render;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.target.TargetCriteria;
import dev.zephyr.core.target.TargetManager;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * TargetHUD - a combat detail panel rendered IN THE WORLD, anchored right beside the
 * current enemy's head (billboarded to face the camera, like a nameplate). It rides
 * the same entity the combat modules locked onto - and if no combat module is enabled,
 * it acquires its own target each tick so the panel is ALWAYS the nearest/most
 * crosshair-aligned enemy, never blank.
 *
 * <p>Highlighted details, line by line:
 * <ol>
 *   <li>Target name (white) + "HURT" flash when in hurt frames</li>
 *   <li>Health BAR (green -> amber -> red) with numeric hp + absorption</li>
 *   <li>Distance in meters + held item name</li>
 *   <li>Armor points + totem-in-offhand indicator</li>
 * </ol>
 *
 * <p>Perf: the text lines are rebuilt at most once per client tick (the panel draws
 * per frame, the data changes per tick). The health bar itself is two fill quads.
 */
public final class TargetHud extends Module implements WorldRenderParticipant {

    private final NumberSetting scale = num("scale", "Panel scale", 0.5, 2.0, 0.05, 1.0);
    private final NumberSetting range = num("range", "Acquire range", 4.0, 64.0, 1.0, 16.0);
    private final BooleanSetting showHeld = bool("held", "Show held item", true);
    private final BooleanSetting showArmor = bool("armor", "Show armor points", true);
    private final BooleanSetting showBar = bool("bar", "Show health bar", true);
    private final BooleanSetting requireLook = bool("crosshair", "Only crosshair targets", false);

    /** Cached per-tick panel lines. */
    private Text[] cachedLines;
    private LivingEntity cachedTarget;
    private float cachedHpRatio = 1.0f;
    private float cachedAbsRatio = 0.0f;
    private int cachedTick = -1;

    /** Our own acquire criteria, used only when no combat module drove the target. */
    private final TargetCriteria criteria = new TargetCriteria().range(16.0).playersOnly(true);

    public TargetHud() {
        super("TargetHUD", ModuleCategory.RENDER, 0);
        criteria.range = range.get();
    }

    @Override
    protected void onDisable() {
        cachedLines = null;
        cachedTarget = null;
        cachedTick = -1;
    }

    @Override
    public void onTick(MinecraftClient client) {
        // Self-acquire so the HUD shows the enemy even with no combat module enabled.
        // Combat modules that already acquired a target this tick simply overwrite it
        // with the same (sticky) selection - one shared lock, zero flicker.
        if (client.player == null || client.world == null) {
            return;
        }
        criteria.range = requireLook.get() ? 8.0 : range.get();
        TargetManager.acquire(client, criteria);
        TargetManager.cleanup();
    }

    private void buildLines(MinecraftClient client, LivingEntity target, int tick) {
        if (cachedTick == tick && cachedTarget == target && cachedLines != null) {
            return;
        }
        cachedTick = tick;
        cachedTarget = target;

        String name = target.getDisplayName() != null
                ? target.getDisplayName().getString() : "?";
        float hp = target.getHealth();
        float abs = target.getAbsorptionAmount();
        float maxHp = Math.max(1.0f, target.getMaxHealth());
        float ratio = MathHelper.clamp(hp / maxHp, 0f, 1f);
        cachedHpRatio = ratio;
        cachedAbsRatio = MathHelper.clamp(abs / maxHp, 0f, 1f);
        int hpColor = ratio > 0.5f ? 0xFF3CE07A : ratio > 0.25f ? 0xFFFFC63C : 0xFFFF5050;

        double dist = client.player != null ? client.player.distanceTo(target) : 0.0;
        Formatting distFmt = dist < 8 ? Formatting.RED : dist < 16 ? Formatting.GOLD : Formatting.GRAY;

        java.util.List<Text> lines = new java.util.ArrayList<>(4);
        // Line 1: name (+hurt flash).
        Text nameLine = Text.literal(name).formatted(Formatting.WHITE);
        if (target.hurtTime > 0) {
            nameLine = nameLine.copy().append(Text.literal(" ✗").formatted(Formatting.RED));
        }
        lines.add(nameLine);
        // Line 2: numeric hp + absorption.
        net.minecraft.text.MutableText hpLine = Text.literal(String.format("%.1f", hp))
                .styled(s -> s.withColor(hpColor))
                .append(Text.literal(" HP").formatted(Formatting.GRAY));
        if (abs > 0.01f) {
            hpLine = hpLine.append(Text.literal(String.format("  +%.1f", abs))
                    .formatted(Formatting.GOLD));
        }
        if (target instanceof PlayerEntity p && dev.zephyr.core.util.ItemUtil.isTotem(
                p.getOffHandStack())) {
            hpLine = hpLine.append(Text.literal("  [totem]").formatted(Formatting.GOLD));
        }
        lines.add(hpLine);
        // Line 3: distance + held.
        if (showHeld.get()) {
            ItemStack held = target.getMainHandStack();
            String heldName = held.isEmpty() ? "empty hand"
                    : held.getName().getString();
            int count = held.getCount();
            lines.add(Text.literal(String.format("%.1fm", dist)).formatted(distFmt)
                    .append(Text.literal("  ·  ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(count > 1 ? heldName + " x" + count : heldName)
                            .formatted(Formatting.AQUA)));
        } else {
            lines.add(Text.literal(String.format("%.1fm", dist)).formatted(distFmt));
        }
        // Line 4: armor.
        if (showArmor.get()) {
            int armor = target.getArmor();
            lines.add(Text.literal("Armor ").formatted(Formatting.GRAY)
                    .append(Text.literal(String.valueOf(armor)).formatted(Formatting.WHITE)));
        }
        cachedLines = lines.toArray(new Text[0]);
    }

    @Override
    public void onWorldRender(WorldRenderContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        LivingEntity target = TargetManager.currentTarget();
        if (client.player == null || target == null || !target.isAlive()
                || ctx.consumers() == null) {
            return;
        }
        // Do not chase targets far outside acquire range (sticky lock may outlive it).
        if (client.player.squaredDistanceTo(target) > 48.0 * 48.0) {
            return;
        }
        buildLines(client, target, RenderUtil.currentTick());

        MatrixStack matrices = ctx.matrices();
        VertexConsumerProvider consumers = ctx.consumers();
        float tickDelta = RenderUtil.tickProgress();

        // Anchor: lerped position, top of the head + a small gap, shifted to the right
        // side of the head so the panel sits NEXT TO the enemy (not covering them).
        double x = MathHelper.lerp(tickDelta, target.lastRenderX, target.getX());
        double y = MathHelper.lerp(tickDelta, target.lastRenderY, target.getY());
        double z = MathHelper.lerp(tickDelta, target.lastRenderZ, target.getZ());
        Vec3d pos = new Vec3d(x, y + target.getHeight() + 0.45, z);
        Vec3d cam = RenderUtil.cameraPos();

        matrices.push();
        matrices.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
        // Billboard: yaw-only rotation so the panel always faces the camera.
        float faceYaw = (float) Math.toDegrees(Math.atan2(cam.x - pos.x, cam.z - pos.z));
        matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotationDegrees(-faceYaw));
        float s = 0.024f * scale.get().floatValue();
        matrices.scale(s, s, s);

        TextRenderer tr = client.textRenderer;
        int lineHeight = tr.fontHeight + 1;
        int widest = 0;
        for (Text line : cachedLines) {
            widest = Math.max(widest, tr.getWidth(line));
        }
        int pad = 3;
        int barH = showBar.get() ? 4 : 0;
        int panelH = cachedLines.length * lineHeight + pad * 2 + barH;
        float x0 = -pad;
        float x1 = widest + pad;
        // Panel background with an accent edge.
        fillQuad(matrices, consumers, x0, -pad, x1, panelH, 0xB20D0F14);
        fillQuad(matrices, consumers, x0, -pad, x0 + 1.5f, panelH, 0xFF4FD2EC);

        // Health bar: background track + hp fill + absorption sliver.
        if (showBar.get()) {
            float barY = panelH - pad - 4 + 1;
            float barW = widest;
            fillQuad(matrices, consumers, x0 + pad, barY, x0 + pad + barW, barY + 4, 0x66000000);
            float hpEnd = x0 + pad + barW * cachedHpRatio;
            int hpColor = cachedHpRatio > 0.5f ? 0xFF3CE07A
                    : cachedHpRatio > 0.25f ? 0xFFFFC63C : 0xFFFF5050;
            if (cachedHpRatio > 0.001f) {
                fillQuad(matrices, consumers, x0 + pad, barY, hpEnd, barY + 4, hpColor);
            }
            if (cachedAbsRatio > 0.001f) {
                float absEnd = x0 + pad + barW * Math.min(1.0f, cachedHpRatio + cachedAbsRatio);
                fillQuad(matrices, consumers, hpEnd, barY, absEnd, barY + 4, 0xFFFFC63C);
            }
        }

        int ly = 0;
        for (Text line : cachedLines) {
            tr.draw(line, 0, ly, 0xFFFFFFFF, false,
                    matrices.peek().getPositionMatrix(), consumers,
                    TextRenderer.TextLayerType.NORMAL, 0, 0xF000F0);
            ly += lineHeight;
        }
        matrices.pop();
    }

    /**
     * Camera-facing background quad for the panel, on the debug filled-box layer
     * (POSITION_COLOR, translucent, no texture - the same primitive debug renderers use).
     */
    private static void fillQuad(MatrixStack matrices, VertexConsumerProvider consumers,
                                 float x0, float y0, float x1, float y1, int argb) {
        var buffer = consumers.getBuffer(net.minecraft.client.render.RenderLayers.debugFilledBox());
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float a = ((argb >>> 24) & 0xFF) / 255.0f;
        float r = ((argb >>> 16) & 0xFF) / 255.0f;
        float g = ((argb >>> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        // The billboard's +Z axis points away from the camera; both winding orders
        // (front and back quad) guarantee visibility regardless of cull state.
        for (int quad = 0; quad < 2; quad++) {
            float z = quad == 0 ? 0.001f : -0.001f;
            buffer.vertex(matrix, x0, y0, z).color(r, g, b, a);
            buffer.vertex(matrix, x1, y0, z).color(r, g, b, a);
            buffer.vertex(matrix, x1, y1, z).color(r, g, b, a);
            buffer.vertex(matrix, x0, y1, z).color(r, g, b, a);
            buffer.vertex(matrix, x0, y1, z).color(r, g, b, a);
            buffer.vertex(matrix, x1, y1, z).color(r, g, b, a);
            buffer.vertex(matrix, x1, y0, z).color(r, g, b, a);
            buffer.vertex(matrix, x0, y0, z).color(r, g, b, a);
        }
    }
}
