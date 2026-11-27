package dev.zephyr.modules.render;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.combat.DamageCalculator;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Crystal ESP - end crystal outlines tinted by their projected damage to the local
 * player (red = lethal band). Same zero-allocation discipline as ESP.
 */
public final class CrystalEsp extends Module implements WorldRenderParticipant {

    private final NumberSetting range = num("range", "Range", 8.0, 64.0, 1.0, 32.0);
    private final NumberSetting dangerHealth = num("danger", "Red below self-dmg", 0.0, 20.0, 0.5, 6.0);

    public CrystalEsp() {
        super("CrystalESP", ModuleCategory.RENDER, 0);
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
        double rangeSq = range.get() * range.get();

        for (var e : client.world.getEntities()) {
            if (!(e instanceof EndCrystalEntity crystal) || !crystal.isAlive()) {
                continue;
            }
            if (self.squaredDistanceTo(crystal) > rangeSq) {
                continue;
            }
            double selfDmg = DamageCalculator.crystalDamage(client.world, crystal.getLerpedPos(1.0F), self);
            float r = 1.0f;
            float g = 1.0f;
            float b = 1.0f;
            if (selfDmg >= dangerHealth.get()) {
                r = 1.0f;
                g = 0.15f;
                b = 0.15f;
            } else if (selfDmg > 1.0) {
                r = 1.0f;
                g = 0.85f;
                b = 0.2f;
            } else {
                r = 0.2f;
                g = 1.0f;
                b = 0.2f;
            }
            // Crystals are stationary entities; the raw box is already render-accurate.
            RenderUtil.drawBoxOutline(matrices, consumers, crystal.getBoundingBox(), r, g, b, 1.0f);
        }
    }

    @Override
    public String hudLabel() {
        return name + " · " + range.get().intValue() + "m";
    }
}
