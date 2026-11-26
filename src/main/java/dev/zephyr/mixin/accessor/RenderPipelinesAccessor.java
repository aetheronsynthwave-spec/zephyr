package dev.zephyr.mixin.accessor;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gl.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes vanilla's line shader setup so Zephyr can build a see-through variant of it. */
@Mixin(RenderPipelines.class)
public interface RenderPipelinesAccessor {

    @Accessor("RENDERTYPE_LINES_SNIPPET")
    static RenderPipeline.Snippet zephyr$linesSnippet() {
        throw new AssertionError();
    }
}
