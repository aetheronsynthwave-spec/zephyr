package dev.zephyr.modules.render;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.util.MathUtil;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;

/**
 * ESP - player box outlines on the shared lines layer. No shaders, no framebuffer
 * passes, no per-frame allocations: one vertex stream, per-tick cached entity list,
 * health-mapped plain color.
 */
public final class Esp extends Module implements WorldRenderParticipant {

    private final NumberSetting range = num("range", "Range", 8.0, 128.0, 1.0, 64.0);
    private final BooleanSetting healthColor = bool("healthColor", "Color by health", true);
    private final BooleanSetting teammates = bool("teammates", "Show teammates", false);

    public Esp() {
        super("ESP", ModuleCategory.RENDER, 0);
    }

    @Override
    public void onWorldRender(WorldRenderContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity self = client.player;
        if (self == null || ctx.consumers() == null) {
            return;
        }
        MatrixStack matrices = ctx.matrixStack();
        VertexConsumerProvider consumers = ctx.consumers();
        float tickDelta = RenderUtil.tickProgress();
        double rangeSq = range.get() * range.get();

        for (PlayerEntity player : RenderUtil.cachedPlayers()) {
            if (player == self || !player.isAlive() || player.isSpectator()) {
                continue;
            }
            if (!teammates.get() && player.isTeammate(self)) {
                continue;
            }
            if (self.squaredDistanceTo(player) > rangeSq) {
                continue;
            }
            float healthRatio = player.getHealth() / Math.max(1.0f, player.getMaxHealth());
            float r = healthColor.get() ? 1.0f - healthRatio : 1.0f;
            float g = healthColor.get() ? healthRatio : 1.0f;
            RenderUtil.drawBoxOutline(matrices, consumers,
                    RenderUtil.interpolatedBox(player, tickDelta), r, g, 0.05f, 1.0f);
        }
    }

    @Override
    public String hudLabel() {
        return name + " · " + range.get().intValue() + "m";
    }
}
