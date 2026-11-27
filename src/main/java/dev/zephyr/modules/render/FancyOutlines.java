package dev.zephyr.modules.render;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.target.TargetManager;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;

/**
 * FancyOutlines - the current combat target gets a double-pass outline: a slightly
 * inflated bright pass under a tight crisp pass. Two line streams, zero shaders.
 */
public final class FancyOutlines extends Module implements WorldRenderParticipant {

    private final NumberSetting inflate = num("inflate", "Glow inflate", 0.02, 0.4, 0.02, 0.12);

    public FancyOutlines() {
        super("FancyOutlines", ModuleCategory.RENDER, 0);
    }

    @Override
    public void onWorldRender(WorldRenderContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        LivingEntity target = TargetManager.currentTarget();
        if (client.player == null || target == null || !target.isAlive() || ctx.consumers() == null) {
            return;
        }
        MatrixStack matrices = ctx.matrixStack();
        VertexConsumerProvider consumers = ctx.consumers();

        double inflate = this.inflate.get();
        // Outer glow pass (bright, inflated).
        RenderUtil.drawBoxOutline(matrices, consumers,
                target.getBoundingBox().expand(inflate), 1.0f, 0.55f, 0.05f, 0.65f);
        // Crisp inner pass.
        RenderUtil.drawBoxOutline(matrices, consumers,
                target.getBoundingBox().expand(0.01), 1.0f, 1.0f, 1.0f, 1.0f);
    }

    @Override
    public String hudLabel() {
        return name;
    }
}
