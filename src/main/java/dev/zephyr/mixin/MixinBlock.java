package dev.zephyr.mixin;

import dev.zephyr.modules.render.XRay;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** XRay: every face of a shown block is drawn, even against another block. */
@Mixin(Block.class)
public abstract class MixinBlock {

    @Inject(method = "shouldDrawSide", at = @At("HEAD"), cancellable = true, require = 0)
    private static void zephyr$xray(BlockState state, BlockState other, Direction side,
                                    CallbackInfoReturnable<Boolean> cir) {
        if (XRay.active()) {
            cir.setReturnValue(XRay.visible(state));
        }
    }
}
