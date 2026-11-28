package dev.zephyr.modules.render;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.ModeSetting;
import dev.zephyr.core.settings.NumberSetting;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.util.List;

/**
 * HandChams - recolours your first-person arms (see MixinPlayerEntityRenderer): tinted skin,
 * a flat colour, or a glowing flat colour, optionally see-through or cycling through hues.
 */
public final class HandChams extends Module {

    private static final Identifier WHITE = Identifier.of("zephyr", "textures/misc/white.png");

    private final ModeSetting style = s(new ModeSetting("style", "Style", List.of("TINT", "SOLID", "GLOW"), "GLOW"));
    private final NumberSetting red = num("red", "Red", 0, 255, 5, 255);
    private final NumberSetting green = num("green", "Green", 0, 255, 5, 255);
    private final NumberSetting blue = num("blue", "Blue", 0, 255, 5, 255);
    private final NumberSetting opacity = num("opacity", "Opacity", 20, 255, 5, 190);
    private final BooleanSetting rainbow = bool("rainbow", "Cycle colours", false);
    private final NumberSetting rainbowSpeed = num("rainbowSpeed", "Cycle speed", 0.1, 3.0, 0.1, 0.5);

    public HandChams() {
        super("HandChams", ModuleCategory.RENDER, 0);
    }

    public RenderLayer layer(Identifier skin) {
        if (style.is("TINT")) {
            return RenderLayers.entityTranslucent(skin);
        }
        return style.is("SOLID") ? RenderLayers.entityTranslucent(WHITE) : RenderLayers.entityTranslucentEmissive(WHITE);
    }

    /** Light to render with: GLOW ignores the world's darkness. */
    public int light(int worldLight) {
        return style.is("GLOW") ? 0xF000F0 : worldLight;
    }

    public int color() {
        int alpha = opacity.get().intValue() << 24;
        if (rainbow.get()) {
            float hue = (float) ((System.currentTimeMillis() % 1_000_000L) / 1000.0 * rainbowSpeed.get() % 1.0);
            return alpha | MathHelper.hsvToRgb(hue, 0.75f, 1.0f) & 0xFFFFFF;
        }
        return alpha | red.get().intValue() << 16 | green.get().intValue() << 8 | blue.get().intValue();
    }
}
