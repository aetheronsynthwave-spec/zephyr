package dev.zephyr.modules.donut;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.modules.donut.scan.EspMemory;
import dev.zephyr.modules.donut.scan.Mat;
import dev.zephyr.modules.donut.scan.Pos;
import dev.zephyr.modules.donut.scan.Trace;
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
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AmethystFinder - finds amethyst geodes in loaded chunks and outlines their grown clusters
 * and budding blocks through walls, with one tracer per geode.
 *
 * <p>{@link EspTracker} adds geodes seen earlier (fainter) and chunk hints that can survive a
 * server hiding the blocks: palette entries, light patterns and amethyst sounds. Data that
 * is never sent leaves nothing to find.
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
    private final BooleanSetting remember = bool("remember", "Remember finds", true);
    private final BooleanSetting hidden = bool("hidden", "Hidden amethyst hints", true);
    private final NumberSetting memoryRange = num("memoryRange", "Memory range (blocks)", 64, 1024, 32, 256);
    private final BooleanSetting palette = bool("palette", "Hint: palette lists amethyst", true);
    private final BooleanSetting budLight = bool("budLight", "Hint: bud light by geodes", true);
    private final BooleanSetting glow = bool("glow", "Hint: impossible light edges", true);
    private final NumberSetting glowCells = num("glowCells", "Light edge cells needed", 1, 100, 1, 12);
    private final BooleanSetting sounds = bool("sounds", "Hint: amethyst sounds", true);

    private static final int MAX_TRACES = 96;

    /** Blocks this close together belong to the same geode. */
    private static final double GEODE_SPREAD = 14.0;

    private record Found(BlockPos pos, boolean cluster, double distance) {
    }

    private volatile List<Found> found = List.of();
    private volatile List<Vec3d> geodes = List.of();
    private volatile List<EspMemory.Seen> remembered = List.of();
    private volatile List<EspMemory.Hint> hints = List.of();
    /** Tracer targets: geodes in view now plus remembered ones. */
    private volatile List<Vec3d> targets = List.of();
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
        EspTracker.get().leave();
    }

    private void clear() {
        found = List.of();
        geodes = List.of();
        remembered = List.of();
        hints = List.of();
        targets = List.of();
    }

    void configure(EspTracker.Config config) {
        config.glowCells = glowCells.asInt();
    }

    private boolean wanted(Mat mat) {
        if (mat == Mat.CLUSTER) {
            return clusters.get();
        }
        if (mat == Mat.BUDDING) {
            return budding.get();
        }
        return buds.get() && mat.bud();
    }

    /** Pulls the matching memory: blocks not already outlined from this scan, and hints. */
    private void recall(MinecraftClient client) {
        Pos me = EspTracker.viewer(client);
        EspMemory memory = EspTracker.get().memory();
        Set<Pos> shown = new HashSet<>();
        for (Found item : found) {
            shown.add(new Pos(item.pos.getX(), item.pos.getY(), item.pos.getZ()));
        }
        boolean old = remember.get();
        List<EspMemory.Seen> blocks = memory.finds(this::wanted, me, memoryRange.get(),
                maxBoxes.asInt() + shown.size());
        blocks.removeIf(s -> shown.contains(s.pos) || !s.live() && !old);
        remembered = List.copyOf(blocks.subList(0, Math.min(blocks.size(), maxBoxes.asInt())));

        List<Vec3d> all = new ArrayList<>(geodes);
        for (EspMemory.Seen seen : remembered) {
            addCentre(all, new Vec3d(seen.pos.x() + 0.5, seen.pos.y() + 0.5, seen.pos.z() + 0.5));
        }
        targets = List.copyOf(all);

        Set<Trace.Kind> kinds = EnumSet.noneOf(Trace.Kind.class);
        if (hidden.get()) {
            if (palette.get()) {
                kinds.add(Trace.Kind.PALETTE_AMETHYST);
            }
            if (budLight.get()) {
                kinds.add(Trace.Kind.BUD_LIGHT);
            }
            if (glow.get()) {
                kinds.add(Trace.Kind.GLOW);
            }
            if (sounds.get()) {
                kinds.add(Trace.Kind.SOUND_AMETHYST);
            }
        }
        List<EspMemory.Hint> list = memory.hints(kinds, me, memoryRange.get(), MAX_TRACES * 2);
        list.removeIf(h -> !h.live() && !old);
        hints = List.copyOf(list.subList(0, Math.min(list.size(), MAX_TRACES)));
    }

    private static void addCentre(List<Vec3d> centres, Vec3d at) {
        if (centres.stream().noneMatch(c -> c.squaredDistanceTo(at) <= GEODE_SPREAD * GEODE_SPREAD)) {
            centres.add(at);
        }
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
        if (client.world == null || client.player == null) {
            return;
        }
        EspTracker.get().tick(client);
        int tick = ticks++;
        if (tick % 40 != 0) {
            if (tick % 10 == 0) {
                recall(client);
            }
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
        recall(client);
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
            addCentre(centres, Vec3d.ofCenter(item.pos));
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
        if (context.consumers() == null || context.matrices() == null) {
            return;
        }
        for (EspMemory.Seen seen : remembered) {
            boolean cluster = seen.mat() != Mat.BUDDING;
            float[] c = {cluster ? 0.85f : 0.55f, cluster ? 0.55f : 0.25f, 1.0f};
            EspDraw.box(context, seen.pos, cluster ? 0.18 : 0.02, c, EspDraw.alpha(seen.live()));
        }
        EspDraw.traces(context, hints, false);
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
            for (Vec3d centre : targets) {
                RenderUtil.drawLine(context.matrices(), context.consumers(), from, centre.x, centre.y, centre.z,
                        0.75f, 0.45f, 1.0f, 0.8f, true);
            }
        }
    }

    @Override
    public String hudLabel() {
        return name + " · " + targets.size() + " geodes · " + hints.size() + " hints";
    }
}
