package dev.zephyr.modules.donut;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.modules.render.RenderUtil;
import dev.zephyr.modules.render.WorldRenderParticipant;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * StorageESP - outlines containers through walls, one colour per kind. It reads the block
 * entities of chunks the server has already sent; nothing is requested and nothing is opened.
 */
public final class StorageESP extends Module implements WorldRenderParticipant {

    private final NumberSetting range = num("range", "Range (blocks)", 16, 256, 8, 128);
    private final NumberSetting maxBoxes = num("maxBoxes", "Max outlines", 32, 1024, 32, 384);
    private final BooleanSetting chests = bool("chests", "Chests", true);
    private final BooleanSetting shulkers = bool("shulkers", "Shulker boxes", true);
    private final BooleanSetting barrels = bool("barrels", "Barrels", true);
    private final BooleanSetting ender = bool("ender", "Ender chests", true);
    private final BooleanSetting hoppers = bool("hoppers", "Hoppers", true);
    private final BooleanSetting furnaces = bool("furnaces", "Furnaces", false);
    private final BooleanSetting droppers = bool("droppers", "Droppers / dispensers", false);
    private final BooleanSetting spawners = bool("spawners", "Spawners", true);
    /** Off: black and white like the rest of the client. */
    private final BooleanSetting colors = bool("colors", "Colour by type", false);
    private final BooleanSetting tracers = bool("tracers", "Tracers", false);

    private record Hit(Box box, float r, float g, float b, double distance) {
    }

    private volatile List<Hit> hits = List.of();
    private int ticks;

    public StorageESP() {
        super("StorageESP", ModuleCategory.DONUT, 0);
    }

    public int count() {
        return hits.size();
    }

    @Override
    protected void onEnable() {
        ticks = 0;
        hits = List.of();
    }

    @Override
    protected void onDisable() {
        hits = List.of();
    }

    @Override
    public void onWorldLeave(MinecraftClient client) {
        hits = List.of();
    }

    @Override
    public void onTick(MinecraftClient client) {
        if (client.world == null || client.player == null || ticks++ % 10 != 0) {
            return;
        }
        double limit = range.get() * range.get();
        Vec3d me = client.player.getEntityPos();
        int radius = (int) Math.ceil(range.get() / 16.0);
        int chunkX = client.player.getBlockX() >> 4;
        int chunkZ = client.player.getBlockZ() >> 4;
        List<Hit> found = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                WorldChunk chunk = client.world.getChunkManager().getWorldChunk(chunkX + dx, chunkZ + dz);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    BlockPos pos = entity.getPos();
                    double distance = pos.getSquaredDistance(me);
                    if (distance <= limit) {
                        classify(Registries.BLOCK_ENTITY_TYPE.getId(entity.getType()).getPath(), pos, distance, found);
                    }
                }
            }
        }
        // The nearest are kept when there are more than can be drawn.
        found.sort(Comparator.comparingDouble(Hit::distance));
        hits = List.copyOf(found.subList(0, Math.min(found.size(), maxBoxes.asInt())));
    }

    private void classify(String id, BlockPos pos, double distance, List<Hit> out) {
        float[] color = switch (id) {
            case "chest", "trapped_chest" -> chests.get() ? new float[]{1.0f, 0.75f, 0.15f} : null;
            case "shulker_box" -> shulkers.get() ? new float[]{1.0f, 0.35f, 0.85f} : null;
            case "barrel" -> barrels.get() ? new float[]{0.80f, 0.52f, 0.28f} : null;
            case "ender_chest" -> ender.get() ? new float[]{0.55f, 0.25f, 1.0f} : null;
            case "hopper" -> hoppers.get() ? new float[]{0.65f, 0.70f, 0.75f} : null;
            case "furnace", "blast_furnace", "smoker" -> furnaces.get() ? new float[]{0.35f, 0.65f, 1.0f} : null;
            case "dropper", "dispenser", "crafter" -> droppers.get() ? new float[]{0.4f, 1.0f, 0.9f} : null;
            case "mob_spawner", "trial_spawner" -> spawners.get() ? new float[]{1.0f, 0.2f, 0.2f} : null;
            default -> null;
        };
        if (color != null) {
            out.add(new Hit(new Box(pos).contract(0.04), color[0], color[1], color[2], distance));
        }
    }

    @Override
    public void onWorldRender(WorldRenderContext context) {
        RenderUtil.trueColor(colors.get());
        try {
            render(context);
        } finally {
            RenderUtil.trueColor(false);
        }
    }

    private void render(WorldRenderContext context) {
        List<Hit> list = hits;
        if (context.consumers() == null || context.matrices() == null || list.isEmpty()) {
            return;
        }
        boolean lines = tracers.get();
        Vec3d from = lines ? RenderUtil.crosshairOrigin() : null;
        for (Hit hit : list) {
            Box box = hit.box;
            if (!RenderUtil.boxBehindCamera(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) {
                RenderUtil.drawBoxOutline(context.matrices(), context.consumers(), box, hit.r, hit.g, hit.b, 1f, true);
            }
            if (lines) {
                RenderUtil.drawLine(context.matrices(), context.consumers(), from,
                        (box.minX + box.maxX) / 2, (box.minY + box.maxY) / 2, (box.minZ + box.maxZ) / 2,
                        hit.r, hit.g, hit.b, 0.7f, true);
            }
        }
    }

    @Override
    public String hudLabel() {
        return name + " · " + hits.size();
    }
}
