package dev.zephyr.modules.render;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.target.TargetManager;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * TargetHUD - a compact detail panel rendered IN THE WORLD, anchored right above the
 * current enemy's head (billboarded to face the camera, like a nameplate).
 *
 * <p>What it highlights, line by line:
 * <ol>
 *   <li>Target name (white)</li>
 *   <li>Live HP + absorption, colored green -> amber -> red as health drops</li>
 *   <li>Distance in meters + currently held item</li>
 *   <li>Total armor points</li>
 * </ol>
 *
 * <p>All strings are rebuilt at most once per client tick (the HUD is per-frame; the
 * target's stats change per tick), so the hot path is four cached text draws per frame.
 */
public final class TargetHud extends Module implements WorldRenderParticipant {

    private final NumberSetting scale = num("scale", "Panel scale", 0.5, 2.0, 0.05, 1.0);
    private final BooleanSetting showHeld = bool("held", "Show held item", true);
    private final BooleanSetting showArmor = bool("armor", "Show armor points", true);

    /** Cached per-tick panel lines. */
    private Text[] cachedLines;
    private LivingEntity cachedTarget;
    private int cachedTick = -1;

    public TargetHud() {
        super("TargetHUD", ModuleCategory.RENDER, 0);
    }

    @Override
    protected void onDisable() {
        cachedLines = null;
        cachedTarget = null;
        cachedTick = -1;
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
        int hpColor = ratio > 0.5f ? 0xFF3CE07A : ratio > 0.25f ? 0xFFFFC63C : 0xFFFF5050;

        double dist = client.player != null ? client.player.distanceTo(target) : 0.0;
        Formatting distFmt = dist < 8 ? Formatting.RED : dist < 16 ? Formatting.GOLD : Formatting.GRAY;

        java.util.List<Text> lines = new java.util.ArrayList<>(4);
        lines.add(Text.literal(name).formatted(Formatting.WHITE));
        net.minecraft.text.MutableText hpLine = Text.literal(String.format("%.1f", hp))
                .styled(s -> s.withColor(hpColor))
                .append(Text.literal(" HP").formatted(Formatting.GRAY));
        if (abs > 0.01f) {
            hpLine = hpLine.append(Text.literal(String.format("  +%.1f", abs))
                    .formatted(Formatting.GOLD));
        }
        if (target.hurtTime > 0) {
            hpLine = hpLine.append(Text.literal("  [hurt]").formatted(Formatting.RED));
        }
        lines.add(hpLine);
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
        buildLines(client, target, RenderUtil.currentTick());

        MatrixStack matrices = ctx.matrices();
        VertexConsumerProvider consumers = ctx.consumers();
        float tickDelta = RenderUtil.tickProgress();

        // Anchor: lerped position, top of the head + a small gap.
        double x = MathHelper.lerp(tickDelta, target.lastRenderX, target.getX());
        double y = MathHelper.lerp(tickDelta, target.lastRenderY, target.getY());
        double z = MathHelper.lerp(tickDelta, target.lastRenderZ, target.getZ());
        Vec3d pos = new Vec3d(x, y + target.getHeight() + 0.4, z);
        Vec3d cam = RenderUtil.cameraPos();

        matrices.push();
        matrices.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
        // Billboard: yaw-only rotation so the panel always faces the camera.
        float faceYaw = (float) Math.toDegrees(Math.atan2(cam.x - pos.x, cam.z - pos.z));
        matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotationDegrees(-faceYaw));
        float s = 0.02f * scale.get().floatValue();
        matrices.scale(s, s, s);

        TextRenderer tr = client.textRenderer;
        int lineHeight = tr.fontHeight + 1;
        // Panel background: one quad behind all lines (text bg per line would look seamed).
        int widest = 0;
        for (Text line : cachedLines) {
            widest = Math.max(widest, tr.getWidth(line));
        }
        int pad = 3;
        int panelH = cachedLines.length * lineHeight + pad * 2 - 1;
        fillQuad(matrices, consumers,
                -widest / 2.0f - pad, -pad, widest / 2.0f + pad, panelH, 0xB20D0F14);

        int ly = 0;
        for (Text line : cachedLines) {
            tr.draw(line, -tr.getWidth(line) / 2.0f, ly, 0xFFFFFFFF, false,
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
