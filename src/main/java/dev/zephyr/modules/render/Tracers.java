package dev.zephyr.modules.render;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Tracers - ONE straight line per player, from the camera to the player's body.
 *
 * <p>Lightweight by construction: two vertices per player on the shared lines layer,
 * per-tick cached entity list, no shaders, no framebuffer passes, no per-frame
 * allocations beyond the vertex stream itself. Even a full lobby costs a handful of
 * draw calls.
 */
public final class Tracers extends Module implements WorldRenderParticipant {

    private final NumberSetting range = num("range", "Range", 8.0, 128.0, 1.0, 64.0);
    private final BooleanSetting healthColor = bool("healthColor", "Color by health", false);
    private final BooleanSetting teammates = bool("teammates", "Show teammates", false);

    public Tracers() {
        super("Tracers", ModuleCategory.RENDER, 0);
    }

    @Override
    public void onWorldRender(WorldRenderContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity self = client.player;
        if (self == null || ctx.consumers() == null) {
            return;
        }
        MatrixStack matrices = ctx.matrices();
        float tickDelta = RenderUtil.tickProgress();
        double rangeSq = range.get() * range.get();
        Vec3d from = RenderUtil.cameraPos();

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
            double x = MathHelper.lerp(tickDelta, player.lastRenderX, player.getX());
            double y = MathHelper.lerp(tickDelta, player.lastRenderY, player.getY());
            double z = MathHelper.lerp(tickDelta, player.lastRenderZ, player.getZ());
            Vec3d to = new Vec3d(x, y + player.getHeight() * 0.5, z);
            if (healthColor.get()) {
                float ratio = player.getHealth() / Math.max(1.0f, player.getMaxHealth());
                RenderUtil.drawLine(matrices, ctx.consumers(), from, to,
                        1.0f - ratio, ratio, 0.05f, 0.85f);
            } else {
                RenderUtil.drawLine(matrices, ctx.consumers(), from, to,
                        0.21f, 0.75f, 0.86f, 0.85f);
            }
        }
    }

    @Override
    public String hudLabel() {
        return name + " · " + range.get().intValue() + "m";
    }
}
