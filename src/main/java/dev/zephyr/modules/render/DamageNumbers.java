package dev.zephyr.modules.render;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.target.TargetManager;
import dev.zephyr.core.util.MathUtil;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * DamageNumbers - floating damage indicators spawned whenever a tracked player's health
 * drops. Pool-based: a fixed pool of float-number objects is reused, expired entries
 * are recycled, nothing is allocated per frame.
 */
public final class DamageNumbers extends Module implements WorldRenderParticipant {

    private final NumberSetting riseSpeed = num("rise", "Rise speed", 0.005, 0.05, 0.005, 0.02);
    private final NumberSetting lifetimeTicks = num("lifetime", "Lifetime ticks", 10, 80, 5, 40);
    private final BooleanSetting trackAll = bool("all", "Track all players", true);

    private static final int POOL_SIZE = 32;
    private final List<FloatingNumber> pool = new ArrayList<>(POOL_SIZE);

    private static final class FloatingNumber {
        double x;
        double y;
        double z;
        float amount;
        int age;
        boolean active;
    }

    public DamageNumbers() {
        super("DamageNumbers", ModuleCategory.RENDER, 0);
        for (int i = 0; i < POOL_SIZE; i++) {
            pool.add(new FloatingNumber());
        }
    }

    @Override
    protected void onDisable() {
        for (FloatingNumber f : pool) {
            f.active = false;
        }
    }

    @Override
    public void onTick(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            return;
        }
        // Advance ages here (tick-accurate) instead of in the render loop.
        for (FloatingNumber f : pool) {
            if (f.active && ++f.age > lifetimeTicks.get().intValue()) {
                f.active = false;
            }
        }
        // Detect health drops on tracked players.
        for (var e : client.world.getEntities()) {
            if (!(e instanceof LivingEntity living) || living == client.player) {
                continue;
            }
            if (!trackAll.get() && living != TargetManager.currentTarget()) {
                continue;
            }
            float delta = TargetManager.trackHealth(living);
            if (delta < -0.01f) {
                spawn(living.getX(), living.getBoundingBox().getCenter().y + 0.6, living.getZ(), -delta);
            }
        }
    }

    private void spawn(double x, double y, double z, float amount) {
        for (FloatingNumber f : pool) {
            if (!f.active) {
                f.x = x;
                f.y = y;
                f.z = z;
                f.amount = amount;
                f.age = 0;
                f.active = true;
                return;
            }
        }
        // Pool exhausted: recycle the oldest.
        FloatingNumber oldest = pool.get(0);
        for (FloatingNumber f : pool) {
            if (f.age > oldest.age) {
                oldest = f;
            }
        }
        oldest.x = x;
        oldest.y = y;
        oldest.z = z;
        oldest.amount = amount;
        oldest.age = 0;
        oldest.active = true;
    }

    @Override
    public void onWorldRender(WorldRenderContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || ctx.consumers() == null) {
            return;
        }
        MatrixStack matrices = ctx.matrixStack();
        VertexConsumerProvider consumers = ctx.consumers();
        int lifetime = lifetimeTicks.get().intValue();

        for (FloatingNumber f : pool) {
            if (!f.active) {
                continue;
            }
            float progress = (float) f.age / lifetime;
            int alpha = (int) ((1.0f - progress) * 255.0f);
            int color = (alpha << 24) | 0xFF5050;
            Vec3d pos = new Vec3d(f.x, f.y + f.age * riseSpeed.get(), f.z);
            RenderUtil.drawBillboardText(matrices, consumers, pos,
                    Text.literal(String.format("%.1f", f.amount)).formatted(Formatting.BOLD),
                    color, 0x00000000, true);
        }
    }

    @Override
    public String hudLabel() {
        int active = 0;
        for (FloatingNumber f : pool) {
            if (f.active) {
                active++;
            }
        }
        return name + " · " + active + " live";
    }
}
