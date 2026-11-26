package dev.zephyr.mixin.accessor;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** RenderLayer.of is package-private in 1.21.11. */
@Mixin(RenderLayer.class)
public interface RenderLayerInvoker {

    @Invoker("of")
    static RenderLayer zephyr$of(String name, RenderSetup setup) {
        throw new AssertionError();
    }
}
