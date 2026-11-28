package dev.zephyr.modules.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.ModeSetting;
import dev.zephyr.core.settings.NumberSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * CustomSky - replaces the overworld sky with an animated, fully computed one (see
 * assets/zephyr/shaders/core/sky.fsh): aurora curtains, a rotating nebula, a turning spiral
 * galaxy, or black and white brush strokes of wind. No images.
 */
public final class CustomSky extends Module {

    private static final List<String> STYLES = List.of("AURORA", "NEBULA", "GALAXY", "MONO");

    private final ModeSetting style = s(new ModeSetting("style", "Style", STYLES, "AURORA"));
    private final NumberSetting speed = num("speed", "Motion speed", 0.0, 5.0, 0.1, 1.0);
    private final NumberSetting brightness = num("brightness", "Brightness", 0.2, 2.0, 0.05, 1.0);
    private final NumberSetting horizon = num("horizon", "Blend into fog at horizon", 0.0, 1.0, 0.05, 0.6);

    private final BooleanSetting hideClouds = bool("hideClouds", "Hide vanilla clouds", true);

    public boolean hidesClouds() {
        return hideClouds.get();
    }

    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.TRANSFORMS_PROJECTION_FOG_SNIPPET)
            .withLocation(Identifier.of("zephyr", "pipeline/sky"))
            .withVertexShader(Identifier.of("zephyr", "core/sky"))
            .withFragmentShader(Identifier.of("zephyr", "core/sky"))
            .withDepthWrite(false)
            .withCull(false)
            .withVertexFormat(VertexFormats.POSITION, VertexFormat.DrawMode.TRIANGLES)
            .build();

    private static GpuBuffer cube;
    private double seconds;
    private long lastNanos;

    public CustomSky() {
        super("CustomSky", ModuleCategory.RENDER, 0);
    }

    /** Called in place of the vanilla sky, on the render thread. */
    public void draw() {
        long now = System.nanoTime();
        // Accumulated, so changing the speed does not make the sky jump.
        if (lastNanos != 0) {
            seconds = (seconds + Math.min(0.25, (now - lastNanos) / 1.0e9) * speed.get()) % 3600.0;
        }
        lastNanos = now;

        Vector4f parameters = new Vector4f((float) seconds, STYLES.indexOf(style.get()),
                brightness.get().floatValue(), horizon.get().floatValue());
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
                .write(RenderSystem.getModelViewMatrix(), parameters, new Vector3f(), new Matrix4f());
        var framebuffer = MinecraftClient.getInstance().getFramebuffer();
        GpuBuffer vertices = cube();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Zephyr custom sky", framebuffer.getColorAttachmentView(), OptionalInt.empty(),
                framebuffer.getDepthAttachmentView(), OptionalDouble.empty())) {
            pass.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0, vertices);
            pass.draw(0, 36);
        }
    }

    /** A cube around the camera, uploaded once. */
    private static GpuBuffer cube() {
        if (cube != null) {
            return cube;
        }
        float r = 100f;
        float[][][] faces = {
                {{-r, -r, -r}, {r, -r, -r}, {r, r, -r}, {-r, r, -r}},
                {{r, -r, r}, {-r, -r, r}, {-r, r, r}, {r, r, r}},
                {{-r, -r, r}, {-r, -r, -r}, {-r, r, -r}, {-r, r, r}},
                {{r, -r, -r}, {r, -r, r}, {r, r, r}, {r, r, -r}},
                {{-r, r, -r}, {r, r, -r}, {r, r, r}, {-r, r, r}},
                {{-r, -r, r}, {r, -r, r}, {r, -r, -r}, {-r, -r, -r}}};
        try (BufferAllocator allocator = new BufferAllocator(36 * VertexFormats.POSITION.getVertexSize())) {
            BufferBuilder builder = new BufferBuilder(allocator, VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION);
            for (float[][] face : faces) {
                for (int corner : new int[]{0, 1, 2, 0, 2, 3}) {
                    builder.vertex(face[corner][0], face[corner][1], face[corner][2]);
                }
            }
            try (BuiltBuffer built = builder.end()) {
                cube = RenderSystem.getDevice().createBuffer(() -> "Zephyr sky cube", GpuBuffer.USAGE_VERTEX,
                        built.getBuffer());
            }
        }
        return cube;
    }

    @Override
    public String hudLabel() {
        return name + " · " + style.get().toLowerCase();
    }
}
