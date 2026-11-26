package dev.zephyr.modules.render;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.NumberSetting;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.LightType;

import java.util.ArrayList;
import java.util.List;

/**
 * LightDebug - marks floor blocks where hostile mobs can currently spawn (block light 0
 * with open space above). Idea ported from Donut Client's Light Debug (MIT).
 */
public final class LightDebug extends Module implements WorldRenderParticipant {

    private final NumberSetting radius = num("radius", "Radius", 4, 24, 1, 12);
    private final NumberSetting maxMarks = num("max", "Max marks", 64, 2048, 64, 512);

    private List<Box> marks = new ArrayList<>();
    private int tick;

    public LightDebug() {
        super("LightDebug", ModuleCategory.RENDER, 0);
    }

    @Override
    public void onTick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null || tick++ % 10 != 0) {
            return;
        }
        List<Box> out = new ArrayList<>();
        BlockPos o = mc.player.getBlockPos();
        int r = radius.asInt();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    m.set(o.getX() + dx, o.getY() + dy, o.getZ() + dz);
                    if (!mc.world.isChunkLoaded(m)) {
                        continue;
                    }
                    var floor = mc.world.getBlockState(m);
                    if (!floor.isSolidBlock(mc.world, m)) {
                        continue;
                    }
                    BlockPos above = m.up();
                    if (!mc.world.getBlockState(above).isAir() || !mc.world.getBlockState(above.up()).isAir()) {
                        continue;
                    }
                    if (mc.world.getLightLevel(LightType.BLOCK, above) == 0) {
                        out.add(new Box(above.getX() + 0.15, above.getY() + 0.02, above.getZ() + 0.15,
                                above.getX() + 0.85, above.getY() + 0.04, above.getZ() + 0.85));
                        if (out.size() >= maxMarks.asInt()) {
                            marks = out;
                            return;
                        }
                    }
                }
            }
        }
        marks = out;
    }

    @Override
    public void onWorldRender(WorldRenderContext ctx) {
        if (ctx.consumers() == null) {
            return;
        }
        for (Box b : marks) {
            RenderUtil.drawBoxOutline(ctx.matrices(), ctx.consumers(), b, 1f, 0.15f, 0.15f, 1f);
        }
    }

    @Override
    public String hudLabel() {
        return name + " · " + marks.size();
    }
}
