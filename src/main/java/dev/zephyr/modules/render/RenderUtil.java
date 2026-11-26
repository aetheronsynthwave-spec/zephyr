package dev.zephyr.modules.render;

import dev.zephyr.ZephyrClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared, allocation-conscious world rendering helpers.
 *
 * <p>Discipline: edge index tables and scratch buffers are static finals; nothing in the
 * per-frame path allocates beyond what the vertex stream itself requires. Entity lists
 * used by the render modules are cached per client tick (rebuilt at most once per tick,
 * shared across ESP/Nametags/FancyOutlines).
 */
public final class RenderUtil {

    private RenderUtil() {
    }

    /** 12 box edges as corner index pairs over the 8 corner vertices. */
    private static final int[][] BOX_EDGES = {
            {0, 1}, {1, 3}, {3, 2}, {2, 0},          // bottom
            {4, 5}, {5, 7}, {7, 6}, {6, 4},          // top
            {0, 4}, {1, 5}, {2, 6}, {3, 7}           // pillars
    };

    /** Shared tick counter for per-tick caches; bumped by the client tick hook. */
    private static int clientTick;
    private static int cacheTick = -1;
    private static final List<PlayerEntity> CACHED_PLAYERS = new ArrayList<>(32);

    /** Fallback immediate provider when the world-render context supplies none. */
    private static VertexConsumerProvider fallbackConsumers;

    /**
     * Camera world position, cached per render pass. The camera cannot move mid-frame,
     * and the old version walked gameRenderer->camera->entity->getEyePos() (which
     * allocates a Vec3d) on EVERY call from EVERY render module - now it is one
     * allocation per frame instead of dozens.
     */
    private static Vec3d cachedCameraPos;
    private static boolean cameraValid;

    /** Called once per frame by the world-render dispatcher before modules run. */
    public static void beginFrame() {
        cameraValid = false;
    }

    /**
     * Camera world position. 1.21.11 removed Camera#getPos(); the camera sits at the
     * focused entity's eyes in first person, which is the only mode this client renders.
     */
    public static Vec3d cameraPos() {
        if (!cameraValid || cachedCameraPos == null) {
            MinecraftClient client = MinecraftClient.getInstance();
            // The render camera's own (frame-interpolated) position. The old version used
            // the entity's current-tick eye position, so every box/line drifted by the
            // player's per-tick movement while walking - the "shaky ESP" effect - and was
            // wrong in third person.
            var camera = client.gameRenderer.getCamera();
            cachedCameraPos = camera.getCameraPos();
            if (cachedCameraPos == null) {
                cachedCameraPos = client.player != null ? client.player.getEyePos() : Vec3d.ZERO;
            }
            cameraValid = true;
        }
        return cachedCameraPos;
    }

    /** Point just in front of the crosshair (tracer origin): a line starting AT the eye collapses to a dot. */
    public static Vec3d crosshairOrigin() {
        var camera = MinecraftClient.getInstance().gameRenderer.getCamera();
        double yaw = Math.toRadians(camera.getYaw());
        double pitch = Math.toRadians(camera.getPitch());
        Vec3d look = new Vec3d(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        return cameraPos().add(look.multiply(0.6));
    }

    // ------------------------------------------------------------------
    // Line layers: normal (depth-tested) and see-through (drawn over walls)
    // ------------------------------------------------------------------

    private static net.minecraft.client.render.RenderLayer seeThroughLines;
    private static boolean seeThroughFailed;

    /** Line width (pixels) used by the box/line helpers; modules set it before drawing. */
    public static float lineWidth = 2.0f;

    private static net.minecraft.client.render.RenderLayer lineLayer(boolean seeThrough) {
        if (seeThrough && !seeThroughFailed) {
            if (seeThroughLines == null) {
                try {
                    var pipeline = com.mojang.blaze3d.pipeline.RenderPipeline.builder(
                                    dev.zephyr.mixin.accessor.RenderPipelinesAccessor.zephyr$linesSnippet())
                            .withLocation(net.minecraft.util.Identifier.of("zephyr", "pipeline/see_through_lines"))
                            .withDepthTestFunction(com.mojang.blaze3d.platform.DepthTestFunction.NO_DEPTH_TEST)
                            .withDepthWrite(false)
                            .build();
                    seeThroughLines = dev.zephyr.mixin.accessor.RenderLayerInvoker.zephyr$of(
                            "zephyr_see_through_lines",
                            net.minecraft.client.render.RenderSetup.builder(pipeline).translucent().build());
                } catch (Throwable t) {
                    seeThroughFailed = true; // fall back to normal lines rather than draw nothing
                    t.printStackTrace();
                }
            }
            if (seeThroughLines != null) {
                return seeThroughLines;
            }
        }
        return net.minecraft.client.render.RenderLayers.lines();
    }

    /** Current frame tick progress (interpolation factor). */
    public static float tickProgress() {
        return MinecraftClient.getInstance().getRenderTickCounter().getTickProgress(false);
    }

    public static void advanceTick() {
        clientTick++;
    }

    public static int currentTick() {
        return clientTick;
    }

    /** Per-tick cached player list (rebuilt at most once per client tick). */
    public static List<PlayerEntity> cachedPlayers() {
        if (cacheTick != clientTick) {
            cacheTick = clientTick;
            CACHED_PLAYERS.clear();
            ClientWorld world = MinecraftClient.getInstance().world;
            if (world != null) {
                CACHED_PLAYERS.addAll(world.getPlayers());
            }
        }
        return CACHED_PLAYERS;
    }

    // ------------------------------------------------------------------
    // Line-box outlines
    // ------------------------------------------------------------------

    public static void drawBoxOutline(MatrixStack matrices, VertexConsumerProvider consumers,
                                      Box box, float r, float g, float b, float a) {
        drawBoxOutline(matrices, consumers, box, r, g, b, a, false);
    }

    public static void drawBoxOutline(MatrixStack matrices, VertexConsumerProvider consumers,
                                      Box box, float r, float g, float b, float a, boolean seeThrough) {
        // 1.21.11: the lines layer moved from RenderLayer.getLines() to RenderLayers.lines();
        // its vertex format carries a trailing line-width element.
        VertexConsumer lines = consumers.getBuffer(lineLayer(seeThrough));
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        // Static scratch (no per-box allocation - this runs per player per frame).
        // Corner index convention (matches BOX_EDGES): bit2=x, bit1=y, bit0=z.
        // 1.21.11 world-render matrices are CAMERA-RELATIVE: subtract the camera position
        // in double precision before narrowing to float, or everything lands off-screen.
        Vec3d cam = cameraPos();
        final float minX = (float) (box.minX - cam.x), maxX = (float) (box.maxX - cam.x);
        final float minY = (float) (box.minY - cam.y), maxY = (float) (box.maxY - cam.y);
        final float minZ = (float) (box.minZ - cam.z), maxZ = (float) (box.maxZ - cam.z);
        for (int[] edge : BOX_EDGES) {
            float x0 = (edge[0] & 4) == 0 ? minX : maxX;
            float y0 = (edge[0] & 2) == 0 ? minY : maxY;
            float z0 = (edge[0] & 1) == 0 ? minZ : maxZ;
            float x1 = (edge[1] & 4) == 0 ? minX : maxX;
            float y1 = (edge[1] & 2) == 0 ? minY : maxY;
            float z1 = (edge[1] & 1) == 0 ? minZ : maxZ;
            lines.vertex(matrix, x0, y0, z0).color(r, g, b, a)
                    .normal(0, 1, 0).lineWidth(lineWidth);
            lines.vertex(matrix, x1, y1, z1).color(r, g, b, a)
                    .normal(0, 1, 0).lineWidth(lineWidth);
        }
    }

    /** Player-interpolated bounding box at the render tick. */
    public static Box interpolatedBox(PlayerEntity player, float tickDelta) {
        double x = MathHelper.lerp(tickDelta, player.lastRenderX, player.getX());
        double y = MathHelper.lerp(tickDelta, player.lastRenderY, player.getY());
        double z = MathHelper.lerp(tickDelta, player.lastRenderZ, player.getZ());
        Box local = player.getBoundingBox();
        double dx = x - player.getX();
        double dy = y - player.getY();
        double dz = z - player.getZ();
        return local.offset(dx, dy, dz);
    }

    // ------------------------------------------------------------------
    // Straight lines (tracers)
    // ------------------------------------------------------------------

    /**
     * One camera-space line on the shared lines layer. Two vertices, one normalized
     * normal - the cheapest possible world-space primitive.
     */
    public static void drawLine(MatrixStack matrices, VertexConsumerProvider consumers,
                                Vec3d from, Vec3d to, float r, float g, float b, float a) {
        drawLine(matrices, consumers, from, to, r, g, b, a, false);
    }

    public static void drawLine(MatrixStack matrices, VertexConsumerProvider consumers,
                                Vec3d from, Vec3d to, float r, float g, float b, float a, boolean seeThrough) {
        VertexConsumer lines = consumers.getBuffer(lineLayer(seeThrough));
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float dx = (float) (to.x - from.x);
        float dy = (float) (to.y - from.y);
        float dz = (float) (to.z - from.z);
        float lenSq = dx * dx + dy * dy + dz * dz;
        float nx = 0f, ny = 1f, nz = 0f;
        if (lenSq > 1.0e-9f) {
            float inv = (float) (1.0 / Math.sqrt(lenSq));
            nx = dx * inv;
            ny = dy * inv;
            nz = dz * inv;
        }
        Vec3d cam = cameraPos(); // camera-relative matrices (see drawBoxOutline)
        // Tracer origin sits slightly in front of the eye so it is visible in first person.
        lines.vertex(matrix, (float) (from.x - cam.x), (float) (from.y - cam.y), (float) (from.z - cam.z))
                .color(r, g, b, a).normal(nx, ny, nz).lineWidth(lineWidth);
        lines.vertex(matrix, (float) (to.x - cam.x), (float) (to.y - cam.y), (float) (to.z - cam.z))
                .color(r, g, b, a).normal(nx, ny, nz).lineWidth(lineWidth);
    }

    // ------------------------------------------------------------------
    // World-space billboard text
    // ------------------------------------------------------------------

    public static void drawBillboardText(MatrixStack matrices, VertexConsumerProvider consumers,
                                         Vec3d worldPos, Text text, int color,
                                         int backgroundColor, boolean seeThrough) {
        MinecraftClient client = MinecraftClient.getInstance();
        Vec3d cameraPos = cameraPos();

        matrices.push();
        matrices.translate(worldPos.x - cameraPos.x, worldPos.y - cameraPos.y, worldPos.z - cameraPos.z);
        // Billboard: yaw-only rotation so the label always faces the camera horizontally.
        float faceYaw = (float) Math.toDegrees(Math.atan2(cameraPos.x - worldPos.x, cameraPos.z - worldPos.z));
        matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotationDegrees(faceYaw));
        float scale = 0.02f;
        matrices.scale(scale, -scale, scale);

        float width = client.textRenderer.getWidth(text);
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        client.textRenderer.draw(text, -width / 2.0f, 0, color, false, matrix,
                consumers, seeThrough
                        ? net.minecraft.client.font.TextRenderer.TextLayerType.SEE_THROUGH
                        : net.minecraft.client.font.TextRenderer.TextLayerType.NORMAL,
                backgroundColor, 0xF000F0);
        matrices.pop();
    }
}
