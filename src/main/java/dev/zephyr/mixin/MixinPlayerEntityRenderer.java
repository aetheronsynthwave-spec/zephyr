package dev.zephyr.mixin;

import dev.zephyr.ZephyrClient;
import dev.zephyr.modules.render.HandChams;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** HandChams: the one call that draws a first-person arm. */
@Mixin(PlayerEntityRenderer.class)
public abstract class MixinPlayerEntityRenderer {

    @Redirect(method = "renderArm", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;submitModelPart("
                    + "Lnet/minecraft/client/model/ModelPart;Lnet/minecraft/client/util/math/MatrixStack;"
                    + "Lnet/minecraft/client/render/RenderLayer;IILnet/minecraft/client/texture/Sprite;)V"))
    private void zephyr$chams(OrderedRenderCommandQueue queue, ModelPart part, MatrixStack matrices, RenderLayer layer,
                              int light, int overlay, Sprite sprite, MatrixStack unusedMatrices,
                              OrderedRenderCommandQueue unusedQueue, int unusedLight, Identifier skin,
                              ModelPart unusedPart, boolean sleeve) {
        HandChams chams = ZephyrClient.modules() == null ? null : ZephyrClient.modules().get(HandChams.class);
        if (chams == null || !chams.isEnabled()) {
            queue.submitModelPart(part, matrices, layer, light, overlay, sprite);
            return;
        }
        queue.submitModelPart(part, matrices, chams.layer(skin), chams.light(light), overlay, sprite, false, false,
                chams.color(), null, 0);
    }
}
