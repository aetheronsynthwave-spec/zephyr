package dev.zephyr.modules.donut;

import dev.zephyr.modules.donut.scan.EspMemory;
import dev.zephyr.modules.donut.scan.Pos;
import dev.zephyr.modules.donut.scan.Trace;
import dev.zephyr.modules.render.RenderUtil;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Draws traces so they can be read from the surface: a square around the chunk at the
 * player's height, a drop line to the spot below, and the sampled cells themselves.
 * Remembered traces are fainter than current ones. Render thread only.
 */
public final class EspDraw {

    private static final int MAX_CELLS_PER_FRAME = 600;
    private static final float REMEMBERED = 0.45f;

    private EspDraw() {
    }

    public static float alpha(boolean live) {
        return live ? 1f : REMEMBERED;
    }

    private static float[] colour(Trace.Kind kind) {
        return switch (kind) {
            case PALETTE_AMETHYST -> new float[]{0.80f, 0.40f, 1.00f};
            case PALETTE_STORAGE -> new float[]{1.00f, 0.60f, 0.10f};
            case BUD_LIGHT -> new float[]{1.00f, 0.55f, 0.85f};
            case GLOW -> new float[]{0.95f, 0.95f, 0.45f};
            case DEEP_LIGHT -> new float[]{1.00f, 0.25f, 0.20f};
            case GROWTH -> new float[]{0.40f, 1.00f, 0.45f};
            case BEES -> new float[]{1.00f, 0.85f, 0.20f};
            case SOUND_AMETHYST -> new float[]{0.55f, 0.65f, 1.00f};
            case SOUND_STORAGE -> new float[]{0.35f, 0.95f, 0.95f};
        };
    }

    public static void traces(WorldRenderContext context, List<EspMemory.Hint> hints, boolean tracers) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (context.consumers() == null || context.matrices() == null || client.player == null || hints.isEmpty()) {
            return;
        }
        double level = Math.floor(client.player.getY()) + 0.05;
        Vec3d from = tracers ? RenderUtil.crosshairOrigin() : null;
        int cells = 0;
        for (EspMemory.Hint hint : hints) {
            Trace trace = hint.trace();
            Pos at = trace.pos();
            float[] c = colour(trace.kind());
            float a = alpha(hint.live());
            // Each kind sits slightly further in, so several in one chunk stay apart.
            double inset = 0.1 + trace.kind().ordinal() * 0.4;
            double x0 = (at.x() >> 4 << 4) + inset;
            double z0 = (at.z() >> 4 << 4) + inset;
            double x1 = (at.x() >> 4 << 4) + 16 - inset;
            double z1 = (at.z() >> 4 << 4) + 16 - inset;
            if (!RenderUtil.boxBehindCamera(x0, level, z0, x1, level + 0.1, z1)) {
                RenderUtil.drawBoxOutline(context.matrices(), context.consumers(), x0, level, z0, x1, level + 0.1, z1,
                        c[0], c[1], c[2], a, true);
            }
            if (tracers) {
                RenderUtil.drawLine(context.matrices(), context.consumers(), from,
                        (x0 + x1) / 2, level, (z0 + z1) / 2, c[0], c[1], c[2], a * 0.7f, true);
            }
            // Growth heat belongs to the whole chunk: there is no spot to point at.
            if (trace.kind() == Trace.Kind.GROWTH) {
                continue;
            }
            Vec3d top = new Vec3d(at.x() + 0.5, level, at.z() + 0.5);
            RenderUtil.drawLine(context.matrices(), context.consumers(), top,
                    at.x() + 0.5, at.y() + 0.5, at.z() + 0.5, c[0], c[1], c[2], a, true);
            box(context, at, 0.0, c, a);
            for (Pos cell : trace.cells()) {
                if (cells >= MAX_CELLS_PER_FRAME) {
                    break;
                }
                cells++;
                box(context, cell, 0.3, c, a);
            }
        }
    }

    public static void box(WorldRenderContext context, Pos at, double shrink, float[] c, float a) {
        double x0 = at.x() + shrink;
        double y0 = at.y() + shrink;
        double z0 = at.z() + shrink;
        double x1 = at.x() + 1 - shrink;
        double y1 = at.y() + 1 - shrink;
        double z1 = at.z() + 1 - shrink;
        if (!RenderUtil.boxBehindCamera(x0, y0, z0, x1, y1, z1)) {
            RenderUtil.drawBoxOutline(context.matrices(), context.consumers(), x0, y0, z0, x1, y1, z1,
                    c[0], c[1], c[2], a, true);
        }
    }
}
