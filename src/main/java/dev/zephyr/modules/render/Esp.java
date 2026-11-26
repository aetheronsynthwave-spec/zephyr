package dev.zephyr.modules.render;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.entity.LivingEntity;

/**
 * ESP - box outlines around players (and optionally mobs), visible THROUGH walls by
 * default, at the frame-interpolated position so they stay glued to the entity while
 * either of you moves.
 */
public final class Esp extends Module implements WorldRenderParticipant {

    private final NumberSetting range = num("range", "Range", 8.0, 256.0, 1.0, 96.0);
    private final BooleanSetting throughWalls = bool("walls", "Through walls", true);
    private final BooleanSetting players = bool("players", "Players", true);
    private final BooleanSetting hostiles = bool("hostiles", "Hostile mobs", false);
    private final BooleanSetting others = bool("others", "Other mobs", false);
    private final BooleanSetting healthColor = bool("healthColor", "Color by health", true);
    private final BooleanSetting teammates = bool("teammates", "Show teammates", false);
    private final NumberSetting width = num("width", "Line width", 1.0, 6.0, 0.5, 2.0);

    /** Per-frame scratch (render thread only). */
    private final float[] rgb = new float[3];
    private final double[] off = new double[3];

    public Esp() {
        super("ESP", ModuleCategory.RENDER, 0);
    }

    @Override
    public void onWorldRender(WorldRenderContext ctx) {
        if (ctx.consumers() == null) {
            return;
        }
        float td = RenderUtil.tickProgress();
        RenderUtil.lineWidth = width.get().floatValue();
        boolean walls = throughWalls.get();
        boolean byHealth = healthColor.get();
        for (LivingEntity e : EspTargets.collect(range.get(), players.get(), hostiles.get(), others.get(),
                teammates.get())) {
            EspTargets.color(e, byHealth, rgb);
            EspTargets.lerpOffset(e, td, off);
            net.minecraft.util.math.Box bb = e.getBoundingBox();
            RenderUtil.drawBoxOutline(ctx.matrices(), ctx.consumers(),
                    bb.minX + off[0] - 0.05, bb.minY + off[1] - 0.05, bb.minZ + off[2] - 0.05,
                    bb.maxX + off[0] + 0.05, bb.maxY + off[1] + 0.05, bb.maxZ + off[2] + 0.05,
                    rgb[0], rgb[1], rgb[2], 1f, walls);
        }
        RenderUtil.lineWidth = 2.0f;
    }

    @Override
    public String hudLabel() {
        return name + " · " + range.get().intValue() + "m";
    }
}
