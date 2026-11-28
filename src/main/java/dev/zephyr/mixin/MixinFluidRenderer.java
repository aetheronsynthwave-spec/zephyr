package dev.zephyr.mixin;

import dev.zephyr.modules.render.XRay;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.FluidRenderer;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** XRay: lava and water are hidden unless asked for. */
@Mixin(FluidRenderer.class)
public abstract class MixinFluidRenderer {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void zephyr$xray(BlockRenderView world, BlockPos pos, VertexConsumer consumer, BlockState state,
                             FluidState fluid, CallbackInfo ci) {
        if (XRay.active() && !XRay.showFluids()) {
            ci.cancel();
        }
    }
}
