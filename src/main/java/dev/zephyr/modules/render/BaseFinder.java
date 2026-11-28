package dev.zephyr.modules.render;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * BaseFinder - port of Donut Client's Base Finder idea: outline the things that mean
 * "somebody built here" inside chunks the server already sent: containers, ender chests,
 * hoppers/machines, spawners, beds, armor stands and item frames. Everything comes from
 * data the client already holds (block entities + loaded entities); nothing is requested.
 * The rescan is throttled to once per second and capped, so it costs nothing per frame.
 */
public final class BaseFinder extends Module implements WorldRenderParticipant {

    private final NumberSetting range = num("range", "Range (blocks)", 16, 256, 8, 96);
    private final NumberSetting maxBoxes = num("maxBoxes", "Max outlines", 16, 1024, 16, 300);
    private final BooleanSetting containers = bool("containers", "Chests / barrels / shulkers", true);
    private final BooleanSetting enderChests = bool("ender", "Ender chests", true);
    private final BooleanSetting machines = bool("machines", "Hoppers / furnaces / dispensers", true);
    private final BooleanSetting spawners = bool("spawners", "Spawners", true);
    private final BooleanSetting beds = bool("beds", "Beds", false);
    private final BooleanSetting entities = bool("entities", "Armor stands / item frames", true);
    private final BooleanSetting tracers = bool("tracers", "Tracers", false);

    private record Hit(Box box, float r, float g, float b) {
    }

    private volatile List<Hit> hits = List.of();
    private int tickCounter;
    public int lastCount;

    public BaseFinder() {
        super("BaseFinder", ModuleCategory.DONUT, 0);
    }

    @Override
    protected void onEnable() {
        tickCounter = 0;
        hits = List.of();
    }

    @Override
    protected void onDisable() {
        hits = List.of();
    }

    @Override
    public void onTick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) {
            return;
        }
        if (tickCounter++ % 20 != 0) {
            return;
        }
        double r = range.get();
        double rSq = r * r;
        Vec3d me = mc.player.getEntityPos();
        int chunkR = (int) Math.ceil(r / 16.0) + 1;
        int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
        int cap = maxBoxes.asInt();
        List<Hit> out = new ArrayList<>();

        for (int dx = -chunkR; dx <= chunkR && out.size() < cap; dx++) {
            for (int dz = -chunkR; dz <= chunkR && out.size() < cap; dz++) {
                WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(pcx + dx, pcz + dz);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos p = be.getPos();
                    if (p.getSquaredDistance(me) > rSq) {
                        continue;
                    }
                    Hit h = classify(Registries.BLOCK_ENTITY_TYPE.getId(be.getType()).getPath(), p);
                    if (h != null && out.size() < cap) {
                        out.add(h);
                    }
                }
            }
        }
        if (entities.get()) {
            // Section-backed box query instead of walking every loaded entity.
            for (Entity e : mc.world.getOtherEntities(null,
                    new net.minecraft.util.math.Box(me, me).expand(Math.sqrt(rSq)),
                    x -> x instanceof ArmorStandEntity || x instanceof ItemFrameEntity)) {
                if (e.squaredDistanceTo(me) <= rSq && out.size() < cap) {
                    out.add(new Hit(e.getBoundingBox(), 0.9f, 0.9f, 0.9f));
                }
            }
        }
        hits = out;
        lastCount = out.size();
    }

    private Hit classify(String id, BlockPos p) {
        Box box = new Box(p);
        if (id.equals("ender_chest")) {
            return enderChests.get() ? new Hit(box, 0.6f, 0.2f, 0.9f) : null;
        }
        if (id.equals("chest") || id.equals("trapped_chest") || id.equals("barrel")
                || id.equals("shulker_box") || id.equals("chiseled_bookshelf")) {
            return containers.get() ? new Hit(box, 1f, 0.8f, 0.2f) : null;
        }
        if (id.equals("hopper") || id.equals("furnace") || id.equals("blast_furnace")
                || id.equals("smoker") || id.equals("dispenser") || id.equals("dropper")
                || id.equals("brewing_stand") || id.equals("crafter")) {
            return machines.get() ? new Hit(box, 0.3f, 0.8f, 1f) : null;
        }
        if (id.equals("mob_spawner") || id.equals("spawner") || id.equals("trial_spawner")) {
            return spawners.get() ? new Hit(box, 1f, 0.25f, 0.25f) : null;
        }
        if (id.equals("bed")) {
            return beds.get() ? new Hit(box, 1f, 0.5f, 0.7f) : null;
        }
        return null;
    }

    @Override
    public void onWorldRender(WorldRenderContext ctx) {
        List<Hit> list = hits;
        if (ctx.consumers() == null || list.isEmpty()) {
            return;
        }
        // One tracer origin per frame (it allocates); the per-hit loop uses raw coordinates.
        boolean drawTracers = tracers.get();
        Vec3d from = drawTracers ? RenderUtil.crosshairOrigin() : null;
        for (Hit h : list) {
            Box b = h.box;
            if (!RenderUtil.boxBehindCamera(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ)) {
                RenderUtil.drawBoxOutline(ctx.matrices(), ctx.consumers(), b, h.r, h.g, h.b, 1f);
            }
            if (drawTracers) {
                RenderUtil.drawLine(ctx.matrices(), ctx.consumers(), from,
                        (b.minX + b.maxX) * 0.5, (b.minY + b.maxY) * 0.5, (b.minZ + b.maxZ) * 0.5,
                        h.r, h.g, h.b, 0.7f, false);
            }
        }
    }

    @Override
    public String hudLabel() {
        return name + " · " + lastCount;
    }
}
