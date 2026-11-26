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

    private static final class CachedTag {
        Text text;
        int hpBucket = -1;
        /** Cached pixel width - recomputed only when the text rebuilds. */
        float width;
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

        for (PlayerEntity player : RenderUtil.cachedPlayers()) {
            if (player == self || !player.isAlive() || player.isSpectator()) {
                continue;
            }
            if (self.squaredDistanceTo(player) > rangeSq) {
                continue;
            }
            CachedTag cached = tagCache.computeIfAbsent(player.getUuid(), u -> new CachedTag());
            int hpBucket = (int) Math.ceil(player.getHealth());
            if (cached.text == null || cached.hpBucket != hpBucket) {
                cached.hpBucket = hpBucket;
                cached.text = showHealth.get()
                        ? player.getDisplayName().copy()
                        .append(Text.literal(" " + hpBucket + "hp").formatted(Formatting.GRAY))
                        : player.getDisplayName().copy();
                cached.width = client.textRenderer.getWidth(cached.text);
            }

            double height = player.getBoundingBox().getLengthY();
            Vec3d pos = new net.minecraft.util.math.Vec3d(
                    player.getX(),
                    player.getY() + height + 0.35,
                    player.getZ());
            matrices.push();
            float s = scale.get().floatValue();
            // Apply extra scale around the anchor point.
            matrices.translate(
                    pos.x - RenderUtil.cameraPos().x,
                    pos.y - RenderUtil.cameraPos().y,
                    pos.z - RenderUtil.cameraPos().z);
            float faceYaw = (float) Math.toDegrees(Math.atan2(
                    RenderUtil.cameraPos().x - pos.x,
                    RenderUtil.cameraPos().z - pos.z));
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

        // Prune cache for players that left the render range or the world.
        if (RenderUtil.currentTick() % 100 == 0) {
            tagCache.clear();
        }
    }

    @Override
    public String hudLabel() {
        return name + " · " + range.get().intValue() + "m";
    }
}
