package dev.zephyr.mixin;

import dev.zephyr.modules.render.XRay;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** XRay: blocks that are not shown are not drawn at all. */
@Mixin(AbstractBlock.AbstractBlockState.class)
public abstract class MixinAbstractBlockState {

    @Inject(method = "getRenderType", at = @At("HEAD"), cancellable = true, require = 0)
    private void zephyr$xray(CallbackInfoReturnable<BlockRenderType> cir) {
        if (XRay.active() && !XRay.visible((BlockState) (Object) this)) {
            cir.setReturnValue(BlockRenderType.INVISIBLE);
        }
    }
}
