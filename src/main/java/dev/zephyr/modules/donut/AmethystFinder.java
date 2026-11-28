package dev.zephyr.modules.donut;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.modules.render.RenderUtil;
import dev.zephyr.modules.render.WorldRenderParticipant;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * AmethystFinder - finds amethyst geodes in loaded chunks and outlines their grown clusters
 * and budding blocks through walls, with one tracer per geode.
 *
 * <p>Only what the client has been sent can be found; a server that hides blocks from the
 * client (anti-xray) hides these too.
 */
public final class AmethystFinder extends Module implements WorldRenderParticipant {

    private final NumberSetting range = num("range", "Range (blocks)", 32, 256, 8, 128);
    private final NumberSetting maxBoxes = num("maxBoxes", "Max outlines", 32, 1024, 32, 320);
    private final BooleanSetting clusters = bool("clusters", "Grown clusters", true);
    private final BooleanSetting buds = bool("buds", "Growing buds", false);
    private final BooleanSetting budding = bool("budding", "Budding amethyst", true);
    /** Off: black and white like the rest of the client. */
    private final BooleanSetting colors = bool("colors", "Colour by type", false);
    private final BooleanSetting tracers = bool("tracers", "Tracer to each geode", true);

    /** Blocks this close together belong to the same geode. */
    private static final double GEODE_SPREAD = 14.0;

    private record Found(BlockPos pos, boolean cluster, double distance) {
    }

    private volatile List<Found> found = List.of();
    private volatile List<Vec3d> geodes = List.of();
    private int ticks;

    public AmethystFinder() {
        super("AmethystFinder", ModuleCategory.DONUT, 0);
    }

    public int geodeCount() {
        return geodes.size();
    }

    public int blockCount() {
        return found.size();
    }

    @Override
    protected void onEnable() {
        ticks = 0;
        clear();
    }

    @Override
    protected void onDisable() {
        clear();
    }

    @Override
    public void onWorldLeave(MinecraftClient client) {
        clear();
    }

    private void clear() {
        found = List.of();
        geodes = List.of();
    }

    private boolean wanted(BlockState state) {
        if (state.isOf(Blocks.AMETHYST_CLUSTER)) {
            return clusters.get();
        }
        if (state.isOf(Blocks.BUDDING_AMETHYST)) {
            return budding.get();
        }
        return buds.get() && (state.isOf(Blocks.SMALL_AMETHYST_BUD) || state.isOf(Blocks.MEDIUM_AMETHYST_BUD)
                || state.isOf(Blocks.LARGE_AMETHYST_BUD));
    }

    @Override
    public void onTick(MinecraftClient client) {
        if (client.world == null || client.player == null || ticks++ % 40 != 0) {
            return;
        }
        double limit = range.get() * range.get();
        Vec3d me = client.player.getEntityPos();
        int radius = (int) Math.ceil(range.get() / 16.0);
        int chunkX = client.player.getBlockX() >> 4;
        int chunkZ = client.player.getBlockZ() >> 4;
        List<Found> list = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                WorldChunk chunk = client.world.getChunkManager().getWorldChunk(chunkX + dx, chunkZ + dz);
                if (chunk != null) {
                    scan(chunk, me, limit, list);
                }
            }
        }
        list.sort(java.util.Comparator.comparingDouble(Found::distance));
        if (list.size() > maxBoxes.asInt()) {
            list = list.subList(0, maxBoxes.asInt());
        }
        found = List.copyOf(list);
        geodes = group(found);
    }

    private void scan(WorldChunk chunk, Vec3d me, double limit, List<Found> out) {
        ChunkSection[] sections = chunk.getSectionArray();
        for (int index = 0; index < sections.length; index++) {
            ChunkSection section = sections[index];
            // The palette says at once whether a 16x16x16 section holds any of these.
            if (section == null || section.isEmpty() || !section.hasAny(this::wanted)) {
                continue;
            }
            int baseY = chunk.sectionIndexToCoord(index) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!wanted(state)) {
                            continue;
                        }
                        BlockPos pos = new BlockPos(chunk.getPos().getStartX() + x, baseY + y,
                                chunk.getPos().getStartZ() + z);
                        double distance = pos.getSquaredDistance(me);
                        if (distance <= limit) {
                            out.add(new Found(pos, !state.isOf(Blocks.BUDDING_AMETHYST), distance));
                        }
                    }
                }
            }
        }
    }

    private static List<Vec3d> group(List<Found> list) {
        List<Vec3d> centres = new ArrayList<>();
        for (Found item : list) {
            Vec3d at = Vec3d.ofCenter(item.pos);
            if (centres.stream().noneMatch(c -> c.squaredDistanceTo(at) <= GEODE_SPREAD * GEODE_SPREAD)) {
                centres.add(at);
            }
        }
        return List.copyOf(centres);
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
        List<Found> list = found;
        if (context.consumers() == null || context.matrices() == null || list.isEmpty()) {
            return;
        }
        for (Found item : list) {
            Box box = new Box(item.pos).contract(item.cluster ? 0.18 : 0.02);
            if (!RenderUtil.boxBehindCamera(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) {
                float r = item.cluster ? 0.85f : 0.55f;
                float g = item.cluster ? 0.55f : 0.25f;
                RenderUtil.drawBoxOutline(context.matrices(), context.consumers(), box, r, g, 1.0f, 1f, true);
            }
        }
        if (tracers.get()) {
            Vec3d from = RenderUtil.crosshairOrigin();
            for (Vec3d centre : geodes) {
                RenderUtil.drawLine(context.matrices(), context.consumers(), from, centre.x, centre.y, centre.z,
                        0.75f, 0.45f, 1.0f, 0.8f, true);
            }
        }
    }

    @Override
    public String hudLabel() {
        return name + " · " + geodes.size() + " geodes";
    }
}
