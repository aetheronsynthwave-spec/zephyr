package dev.zephyr.modules.render;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;

/**
 * Fullbright - overrides ONLY the lightmap gamma (see MixinLightmapTextureManager); the
 * saved brightness option is never touched, so disabling restores the exact old look.
 * (Ported from Donut Client's Fullbright, MIT.)
 */
public final class Fullbright extends Module {
    public static final double GAMMA = 1000.0;
    /** Incremented by the lightmap mixin - lets the self-test prove the hook is live. */
    public static volatile int applied;

    public Fullbright() {
        super("Fullbright", ModuleCategory.RENDER, 0);
    }

    @Override
    protected void onEnable() {
        refresh();
    }

    @Override
    protected void onDisable() {
        refresh();
    }

    private static void refresh() {
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.gameRenderer != null) {
            mc.gameRenderer.getLightmapTextureManager().tick();
        }
    }
}
