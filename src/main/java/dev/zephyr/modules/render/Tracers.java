package dev.zephyr.modules.render;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Tracers - a line from your crosshair to each player (optionally mobs), drawn through
 * walls by default. The old version started the line exactly at the eye, which projects
 * to a single point and gets clipped, so tracers were mostly invisible.
 */
public final class Tracers extends Module implements WorldRenderParticipant {

    private final NumberSetting range = num("range", "Range", 8.0, 256.0, 1.0, 128.0);
    private final BooleanSetting throughWalls = bool("walls", "Through walls", true);
    private final BooleanSetting players = bool("players", "Players", true);
    private final BooleanSetting hostiles = bool("hostiles", "Hostile mobs", false);
    private final BooleanSetting others = bool("others", "Other mobs", false);
    private final BooleanSetting healthColor = bool("healthColor", "Color by health", false);
    private final BooleanSetting teammates = bool("teammates", "Show teammates", false);
    private final BooleanSetting toBody = bool("body", "Aim at body (off = feet)", true);
    private final NumberSetting width = num("width", "Line width", 1.0, 6.0, 0.5, 1.5);

    public Tracers() {
        super("Tracers", ModuleCategory.RENDER, 0);
    }

    @Override
    public void onWorldRender(WorldRenderContext ctx) {
        if (ctx.consumers() == null) {
            return;
        }
        float td = RenderUtil.tickProgress();
        Vec3d from = RenderUtil.crosshairOrigin();
        RenderUtil.lineWidth = width.get().floatValue();
        for (LivingEntity e : EspTargets.collect(range.get(), players.get(), hostiles.get(), others.get(),
                teammates.get())) {
            Box b = EspTargets.box(e, td);
            Vec3d to = toBody.get() ? b.getCenter() : new Vec3d((b.minX + b.maxX) / 2, b.minY, (b.minZ + b.maxZ) / 2);
            float[] c = EspTargets.color(e, healthColor.get());
            RenderUtil.drawLine(ctx.matrices(), ctx.consumers(), from, to, c[0], c[1], c[2], 0.9f,
                    throughWalls.get());
        }
        RenderUtil.lineWidth = 2.0f;
    }

    @Override
    public String hudLabel() {
        return name + " · " + range.get().intValue() + "m";
    }
}
