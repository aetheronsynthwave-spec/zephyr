package dev.zephyr.gui.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.awt.image.BufferedImage;

/**
 * A GPU texture generated once from a Java2D image and sampled with LINEAR filtering.
 *
 * <p>This is the whole trick behind the smooth UI: every curved or text pixel is
 * rasterised once, anti-aliased, at (or above) the size it will be shown at, then drawn
 * as plain textured quads. Per frame that costs the same as vanilla GUI sprites - no
 * shaders, no extra passes.
 */
public final class SmoothTexture extends NativeImageBackedTexture {

    private SmoothTexture(String name, NativeImage image) {
        super(() -> name, image);
        this.sampler = RenderSystem.getSamplerCache().get(FilterMode.LINEAR);
    }

    /** Uploads {@code img} under {@code id} (render thread only). */
    public static Identifier register(String path, BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        NativeImage ni = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                ni.setColorArgb(x, y, img.getRGB(x, y));
            }
        }
        Identifier id = Identifier.of("zephyr", path);
        SmoothTexture tex = new SmoothTexture("zephyr:" + path, ni);
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);
        return id;
    }

    /**
     * Java2D draws anti-aliased edges as partially transparent pixels whose colour is
     * premultiplied-looking (dark). For white masks we force RGB to white everywhere so
     * linear filtering never pulls a dark fringe into the edge.
     */
    public static void whiten(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int a = img.getRGB(x, y) >>> 24;
                img.setRGB(x, y, (a << 24) | 0xFFFFFF);
            }
        }
    }
}
