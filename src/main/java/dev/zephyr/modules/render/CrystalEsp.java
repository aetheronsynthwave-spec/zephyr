package dev.zephyr.modules.render;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.combat.DamageCalculator;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Crystal ESP - end crystal outlines tinted by their projected damage to the local
 * player (red = lethal band).
 *
 * <p>Perf model: entity scan and damage math are cached per client tick and only
 * refreshed every {@value #REFRESH_TICKS} ticks (or when a crystal moves), so the
 * render path never re-runs the 27-ray explosion exposure math per frame per crystal
 * - that was the module that dragged the baseline from 250 fps down to 80.
 */
public final class CrystalEsp extends Module implements WorldRenderParticipant {

    private static final int REFRESH_TICKS = 5;
    private static final int PRUNE_TICKS = 100;

    private final NumberSetting range = num("range", "Range", 8.0, 64.0, 1.0, 32.0);
    private final NumberSetting dangerHealth = num("danger", "Red below self-dmg", 0.0, 20.0, 0.5, 6.0);

    private final BooleanSetting walls = bool("walls", "Through walls", true);

    /** Per-crystal cached self-damage (entity id -> damage + position it was measured at). */
    private final Map<Integer, CachedDamage> damageCache = new HashMap<>();
    private int scanTick = -1;
    private List<EndCrystalEntity> scanned;

    private static final class CachedDamage {
        double dmg;
        Vec3d pos;
        int tick;
    }

    public CrystalEsp() {
        super("CrystalESP", ModuleCategory.RENDER, 0);
    }

    @Override
    protected void onDisable() {
        damageCache.clear();
        scanned = null;
        scanTick = -1;
    }

    @Override
    public void onWorldRender(WorldRenderContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity self = client.player;
        if (self == null || ctx.consumers() == null) {
            return;
        }
        MatrixStack matrices = ctx.matrices();
        VertexConsumerProvider consumers = ctx.consumers();
        double rangeSq = range.get() * range.get();
        int tick = dev.zephyr.modules.render.RenderUtil.currentTick();

        for (EndCrystalEntity crystal : scanCrystals(client, self, tick)) {
            if (self.squaredDistanceTo(crystal) > rangeSq) {
                continue;
            }
            // Crystals behind the camera are not drawn, so skip their exposure math too
            // (the damage cache refreshes on demand, so the value is still correct on return).
            Box bb = crystal.getBoundingBox();
            if (RenderUtil.boxBehindCamera(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ)) {
                continue;
            }
            double selfDmg = cachedSelfDamage(crystal, tick);
            float r = 1.0f;
            float g = 1.0f;
            float b = 1.0f;
            if (selfDmg >= dangerHealth.get()) {
                r = 1.0f;
                g = 0.15f;
                b = 0.15f;
            } else if (selfDmg > 1.0) {
                r = 1.0f;
                g = 0.85f;
                b = 0.2f;
            } else {
                r = 0.2f;
                g = 1.0f;
                b = 0.2f;
            }
            // Crystals are stationary entities; the raw box is already render-accurate.
            RenderUtil.drawBoxOutline(matrices, consumers, bb, r, g, b, 1.0f, walls.get());
        }
    }

    /** Entity scan cached per client tick - one box query per tick, not per frame. */
    private List<EndCrystalEntity> scanCrystals(MinecraftClient client, ClientPlayerEntity self, int tick) {
        if (scanned == null || scanTick != tick) {
            scanTick = tick;
            // Crystal ids only ever grow, and a crystal that is gone is never looked up again:
            // drop entries not refreshed for a while so the cache stays bounded on long sessions.
            damageCache.values().removeIf(c -> tick - c.tick > PRUNE_TICKS);
            scanned = new java.util.ArrayList<>();
            for (var e : dev.zephyr.core.world.EntityScan.around(
                    client.world, self.getLerpedPos(1.0F), range.get())) {
                if (e instanceof EndCrystalEntity crystal && crystal.isAlive()) {
                    scanned.add(crystal);
                }
            }
        }
        return scanned;
    }

    /** Self-damage per crystal, recomputed at most every {@value #REFRESH_TICKS} ticks. */
    private double cachedSelfDamage(EndCrystalEntity crystal, int tick) {
        CachedDamage c = damageCache.get(crystal.getId());
        Vec3d pos = crystal.getLerpedPos(1.0F);
        if (c == null || tick - c.tick >= REFRESH_TICKS || c.pos.squaredDistanceTo(pos) > 0.0625) {
            if (c == null) {
                c = new CachedDamage();
                damageCache.put(crystal.getId(), c);
            }
            c.dmg = DamageCalculator.crystalDamage(MinecraftClient.getInstance().world, pos,
                    MinecraftClient.getInstance().player);
            c.pos = pos;
            c.tick = tick;
        }
        return c.dmg;
    }

    @Override
    public String hudLabel() {
        return name + " · " + range.get().intValue() + "m";
    }
}
