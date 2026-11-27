package dev.zephyr.modules.render;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Nametags - compact player nameplates with live HP suffix. Text objects are cached
 * per player and only rebuilt when the displayed HP integer changes, so the hot path
 * is a map lookup plus one draw call per visible player.
 */
public final class NameTags extends Module implements WorldRenderParticipant {

    private final NumberSetting range = num("range", "Range", 8.0, 128.0, 1.0, 64.0);
    private final NumberSetting scale = num("scale", "Text scale", 0.5, 2.0, 0.05, 0.8);
    private final BooleanSetting showHealth = bool("health", "Show HP", true);
    private final BooleanSetting seeThrough = bool("seeThrough", "See through walls", false);

    private final Map<UUID, CachedTag> tagCache = new HashMap<>();
    /** Tick of the last stale-entry sweep (see the end of onWorldRender). */
    private int lastPruneTick;

    private static final class CachedTag {
        Text text;
        int hpBucket = -1;
        /** Cached pixel width - recomputed only when the text rebuilds. */
        float width;
        /** Last client tick this player was drawn on. */
        int seenTick;
    }

    public NameTags() {
        super("NameTags", ModuleCategory.RENDER, 0);
    }

    @Override
    protected void onDisable() {
        tagCache.clear();
    }

    @Override
    public void onWorldRender(WorldRenderContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity self = client.player;
        if (self == null || ctx.consumers() == null) {
            return;
        }
        MatrixStack matrices = ctx.matrices();
        VertexConsumerProvider consumers = ctx.consumers();
        double rangeSq = range.get() * range.get();
        float td = RenderUtil.tickProgress();
        Vec3d cam = RenderUtil.cameraPos();
        int tick = RenderUtil.currentTick();

        for (PlayerEntity player : RenderUtil.cachedPlayers()) {
            if (player == self || !player.isAlive() || player.isSpectator()) {
                continue;
            }
            if (self.squaredDistanceTo(player) > rangeSq) {
                continue;
            }
            CachedTag cached = tagCache.computeIfAbsent(player.getUuid(), u -> new CachedTag());
            cached.seenTick = tick;
            int hpBucket = (int) Math.ceil(player.getHealth());
            if (cached.text == null || cached.hpBucket != hpBucket) {
                cached.hpBucket = hpBucket;
                cached.text = showHealth.get()
                        ? player.getDisplayName().copy()
                        .append(Text.literal(" " + hpBucket + "hp").formatted(Formatting.GRAY))
                        : player.getDisplayName().copy();
                cached.width = client.textRenderer.getWidth(cached.text);
            }

            // Frame-interpolated anchor (the raw tick position made tags stutter at 20 Hz).
            double height = player.getBoundingBox().getLengthY();
            double ax = net.minecraft.util.math.MathHelper.lerp(td, player.lastRenderX, player.getX());
            double ay = net.minecraft.util.math.MathHelper.lerp(td, player.lastRenderY, player.getY()) + height + 0.35;
            double az = net.minecraft.util.math.MathHelper.lerp(td, player.lastRenderZ, player.getZ());
            matrices.push();
            float s = scale.get().floatValue();
            matrices.translate(ax - cam.x, ay - cam.y, az - cam.z);
            float faceYaw = (float) Math.toDegrees(Math.atan2(cam.x - ax, cam.z - az));
            matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotationDegrees(faceYaw));
            matrices.scale(0.02f * s, -0.02f * s, 0.02f * s);
            client.textRenderer.draw(cached.text, -cached.width / 2.0f, 0, 0xFFFFFFFF, false,
                    matrices.peek().getPositionMatrix(), consumers,
                    seeThrough.get()
                            ? net.minecraft.client.font.TextRenderer.TextLayerType.SEE_THROUGH
                            : net.minecraft.client.font.TextRenderer.TextLayerType.NORMAL,
                    0x40101010, 0xF000F0);
            matrices.pop();
        }

        // Drop entries for players not drawn for 100+ ticks, once per 100 ticks. Clearing the
        // whole map on every frame of that tick used to rebuild every nameplate's text.
        if (tick - lastPruneTick >= 100) {
            lastPruneTick = tick;
            tagCache.values().removeIf(c -> tick - c.seenTick > 100);
        }
    }

    @Override
    public String hudLabel() {
        return name + " · " + range.get().intValue() + "m";
    }
}
