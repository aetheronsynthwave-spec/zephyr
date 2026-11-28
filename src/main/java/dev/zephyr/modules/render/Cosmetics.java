package dev.zephyr.modules.render;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * Cosmetics - a halo, a brimmed cone hat and a pair of beating wings on your own character,
 * drawn from code (no model files). Only you see them, and only in third person.
 */
public final class Cosmetics extends Module implements WorldRenderParticipant {

    private final BooleanSetting halo = bool("halo", "Halo", true);
    private final BooleanSetting hat = bool("hat", "Cone hat", false);
    private final BooleanSetting wings = bool("wings", "Wings", true);
    private final NumberSetting red = num("red", "Red", 0, 255, 5, 255);
    private final NumberSetting green = num("green", "Green", 0, 255, 5, 255);
    private final NumberSetting blue = num("blue", "Blue", 0, 255, 5, 255);
    private final NumberSetting wingSize = num("wingSize", "Wing size", 0.5, 2.0, 0.05, 1.0);

    public Cosmetics() {
        super("Cosmetics", ModuleCategory.RENDER, 0);
    }

    @Override
    public void onWorldRender(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        var player = client.player;
        if (player == null || context.consumers() == null || context.matrices() == null
                || client.options.getPerspective().isFirstPerson() || player.isInvisible() || player.isSpectator()) {
            return;
        }
        float delta = client.getRenderTickCounter().getTickProgress(false);
        Vec3d at = player.getLerpedPos(delta).subtract(RenderUtil.cameraPos());
        float body = MathHelper.lerpAngleDegrees(delta, player.lastBodyYaw, player.bodyYaw);
        float head = MathHelper.lerpAngleDegrees(delta, player.lastHeadYaw, player.headYaw);
        float time = (System.currentTimeMillis() % 3_600_000L) / 1000f;
        double top = player.getHeight();
        int r = red.get().intValue();
        int g = green.get().intValue();
        int b = blue.get().intValue();

        MatrixStack matrices = context.matrices();
        VertexConsumer quads = context.consumers().getBuffer(RenderLayers.debugQuads());
        matrices.push();
        matrices.translate(at.x, at.y, at.z);
        if (halo.get()) {
            matrices.push();
            matrices.translate(0.0, top + 0.28 + Math.sin(time * 2.0) * 0.025, 0.0);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotation(time * 0.8f));
            ring(quads, matrices.peek().getPositionMatrix(), 0.24f, 0.32f, 0f, r, g, b, 235);
            ring(quads, matrices.peek().getPositionMatrix(), 0.32f, 0.40f, 0f, r, g, b, 70);
            matrices.pop();
        }
        if (hat.get()) {
            matrices.push();
            matrices.translate(0.0, top + 0.02, 0.0);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-head));
            cone(quads, matrices.peek().getPositionMatrix(), 0.52f, 0.28f, r, g, b);
            matrices.pop();
        }
        if (wings.get() && !player.isGliding()) {
            matrices.push();
            // Between the shoulders, behind the back; the model's +Z is forward.
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-body));
            matrices.translate(0.0, top - (player.isSneaking() ? 0.62 : 0.55), -0.17);
            float size = wingSize.get().floatValue();
            float speed = player.isOnGround() ? 2.2f : 7.0f;
            float beat = (float) Math.sin(time * speed) * (player.isOnGround() ? 0.22f : 0.55f) + 0.45f;
            for (int side = -1; side <= 1; side += 2) {
                matrices.push();
                matrices.translate(side * 0.08, 0.0, 0.0);
                matrices.multiply(RotationAxis.POSITIVE_Y.rotation(side * beat));
                wing(quads, matrices.peek().getPositionMatrix(), side * size, size, r, g, b);
                matrices.pop();
            }
            matrices.pop();
        }
        matrices.pop();
    }

    private static void ring(VertexConsumer out, Matrix4f m, float inner, float outer, float y,
                             int r, int g, int b, int a) {
        int parts = 40;
        for (int i = 0; i < parts; i++) {
            float from = (float) (i * Math.PI * 2 / parts);
            float to = (float) ((i + 1) * Math.PI * 2 / parts);
            quad(out, m, r, g, b, a,
                    MathHelper.cos(from) * inner, y, MathHelper.sin(from) * inner,
                    MathHelper.cos(from) * outer, y, MathHelper.sin(from) * outer,
                    MathHelper.cos(to) * outer, y, MathHelper.sin(to) * outer,
                    MathHelper.cos(to) * inner, y, MathHelper.sin(to) * inner);
        }
    }

    private static void cone(VertexConsumer out, Matrix4f m, float radius, float height, int r, int g, int b) {
        int parts = 24;
        for (int i = 0; i < parts; i++) {
            float from = (float) (i * Math.PI * 2 / parts);
            float to = (float) ((i + 1) * Math.PI * 2 / parts);
            // Alternate panels a little darker, so the shape reads without lighting.
            float shade = i % 2 == 0 ? 1.0f : 0.78f;
            quad(out, m, (int) (r * shade), (int) (g * shade), (int) (b * shade), 215,
                    0f, height, 0f,
                    MathHelper.cos(from) * radius, 0f, MathHelper.sin(from) * radius,
                    MathHelper.cos(to) * radius, 0f, MathHelper.sin(to) * radius,
                    0f, height, 0f);
        }
    }

    /** Layered feathers fanning out and down from the shoulder; {@code side} mirrors it. */
    private static void wing(VertexConsumer out, Matrix4f m, float side, float size, int r, int g, int b) {
        int feathers = 11;
        for (int i = 0; i < feathers; i++) {
            float t = i / (float) (feathers - 1);
            // Roots run along the upper bone, tips hang lower and reach further towards the end.
            float rootX = side * (0.05f + t * 0.62f);
            float rootY = size * (0.05f + (float) Math.sin(t * Math.PI * 0.85) * 0.26f);
            float tipX = rootX + side * (0.08f + t * 0.42f);
            float tipY = rootY - size * (0.34f + (float) Math.sin(t * Math.PI * 0.7) * 0.42f);
            float half = 0.055f * Math.abs(side);
            float shade = 1.0f - t * 0.35f;
            float depth = -0.004f * i;
            quad(out, m, (int) (r * shade), (int) (g * shade), (int) (b * shade), 225,
                    rootX - side * 0.04f, rootY + size * 0.04f, depth,
                    rootX + (side < 0 ? -half : half), rootY, depth,
                    tipX, tipY, depth - 0.02f,
                    rootX - (side < 0 ? -half : half), rootY - size * 0.14f, depth);
        }
    }

    /** Both windings, so the shape is visible from either side. */
    private static void quad(VertexConsumer out, Matrix4f m, int r, int g, int b, int a,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float x3, float y3, float z3, float x4, float y4, float z4) {
        float[][] points = {{x1, y1, z1}, {x2, y2, z2}, {x3, y3, z3}, {x4, y4, z4}};
        for (int i = 0; i < 4; i++) {
            out.vertex(m, points[i][0], points[i][1], points[i][2]).color(r, g, b, a);
        }
        for (int i = 3; i >= 0; i--) {
            out.vertex(m, points[i][0], points[i][1], points[i][2]).color(r, g, b, a);
        }
    }
}
