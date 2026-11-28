package dev.zephyr.modules.render;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.ModeSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.modules.donut.EspDraw;
import dev.zephyr.modules.donut.EspTracker;
import dev.zephyr.modules.donut.scan.EspMemory;
import dev.zephyr.modules.donut.scan.Mat;
import dev.zephyr.modules.donut.scan.Pos;
import dev.zephyr.modules.donut.scan.Trace;
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
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * BaseFinder - port of Donut Client's Base Finder idea: outline the things that mean
 * "somebody built here" inside chunks the server already sent: containers, ender chests,
 * hoppers/machines, spawners, beds, armor stands and item frames. Everything comes from
 * data the client already holds (block entities + loaded entities); nothing is requested.
 * The rescan is throttled to once per second and capped. {@link EspTracker} adds what was
 * seen earlier (fainter) and chunk hints: grown-out amethyst areas, unexplained light
 * underground and hives. Hints are clues, not proof of a base.
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
    private final BooleanSetting walls = bool("walls", "Through walls", true);
    private final BooleanSetting remember = bool("remember", "Remember finds", true);
    private final BooleanSetting suspect = bool("suspect", "Suspect chunk hints", true);
    private final NumberSetting memoryRange = num("memoryRange", "Memory range (blocks)", 64, 1024, 32, 256);
    private final BooleanSetting growth = bool("growth", "Hint: grown-out amethyst", true);
    private final NumberSetting sensitivity = num("sensitivity", "Growth heat needed", 1, 64, 1, 6);
    private final NumberSetting spread = num("spread", "Growth reach (chunks)", 1, 12, 1, 5);
    private final ModeSetting growthMode = s(new ModeSetting("growthMode", "Count growth by",
            List.of("AUTO", "PALETTE", "BLOCKS"), "AUTO"));
    private final BooleanSetting underLight = bool("underLight", "Hint: light underground", true);
    private final NumberSetting lightMin = num("lightMin", "Weakest light counted", 1, 15, 1, 5);
    private final NumberSetting lightCells = num("lightCells", "Lit cells needed", 1, 256, 1, 8);
    private final BooleanSetting denoise = bool("denoise", "Drop faint light patches", true);
    private final BooleanSetting skipLava = bool("skipLava", "Ignore lava-lit patches", true);
    private final BooleanSetting bees = bool("bees", "Hint: hives and honeyed nests", true);

    private static final int MAX_TRACES = 96;

    private record Hit(Box box, float r, float g, float b) {
    }

    private volatile List<Hit> hits = List.of();
    private volatile List<EspMemory.Seen> remembered = List.of();
    private volatile List<EspMemory.Hint> hints = List.of();
    private int tickCounter;
    public int lastCount;

    public BaseFinder() {
        super("BaseFinder", ModuleCategory.DONUT, 0);
    }

    @Override
    protected void onEnable() {
        tickCounter = 0;
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
        hits = List.of();
        remembered = List.of();
        hints = List.of();
        lastCount = 0;
    }

    /** Hands the scanner the numbers this module owns. */
    public void configure(EspTracker.Config config) {
        config.sensitivity = sensitivity.asInt();
        config.spread = spread.asInt();
        config.growth = growthMode.get();
        config.lightMin = lightMin.asInt();
        config.lightCells = lightCells.asInt();
        config.denoise = denoise.get();
        config.skipLava = skipLava.get();
    }

    @Override
    public void onTick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) {
            return;
        }
        EspTracker.get().tick(mc);
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

        for (int dx = -chunkR; dx <= chunkR; dx++) {
            for (int dz = -chunkR; dz <= chunkR; dz++) {
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
                    if (h != null) {
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
                if (e.squaredDistanceTo(me) <= rSq) {
                    out.add(new Hit(e.getBoundingBox(), 0.9f, 0.9f, 0.9f));
                }
            }
        }
        // The nearest are kept when there are more than can be drawn.
        out.sort(Comparator.comparingDouble(h -> me.squaredDistanceTo((h.box.minX + h.box.maxX) / 2,
                (h.box.minY + h.box.maxY) / 2, (h.box.minZ + h.box.maxZ) / 2)));
        hits = List.copyOf(out.subList(0, Math.min(cap, out.size())));
        lastCount = hits.size();
        recall(mc);
    }

    private void recall(MinecraftClient mc) {
        Pos me = EspTracker.viewer(mc);
        EspMemory memory = EspTracker.get().memory();
        Set<Pos> shown = new HashSet<>();
        for (Hit h : hits) {
            shown.add(new Pos((int) Math.floor(h.box.minX), (int) Math.floor(h.box.minY),
                    (int) Math.floor(h.box.minZ)));
        }
        boolean old = remember.get();
        List<EspMemory.Seen> blocks = memory.finds(m -> color(m) != null, me, memoryRange.get(),
                maxBoxes.asInt() + shown.size());
        blocks.removeIf(s -> shown.contains(s.pos) || !s.live() && !old);
        remembered = List.copyOf(blocks.subList(0, Math.min(blocks.size(), maxBoxes.asInt())));

        Set<Trace.Kind> kinds = EnumSet.noneOf(Trace.Kind.class);
        if (suspect.get()) {
            if (growth.get()) {
                kinds.add(Trace.Kind.GROWTH);
            }
            if (underLight.get()) {
                kinds.add(Trace.Kind.DEEP_LIGHT);
            }
            if (bees.get()) {
                kinds.add(Trace.Kind.BEES);
            }
        }
        List<EspMemory.Hint> list = memory.hints(kinds, me, memoryRange.get(), MAX_TRACES * 2);
        list.removeIf(h -> !h.live() && !old);
        hints = List.copyOf(list.subList(0, Math.min(list.size(), MAX_TRACES)));
    }

    private float[] color(Mat mat) {
        return switch (mat) {
            case ENDER_CHEST -> enderChests.get() ? new float[]{0.6f, 0.2f, 0.9f} : null;
            case CHEST, BARREL, SHULKER -> containers.get() ? new float[]{1f, 0.8f, 0.2f} : null;
            case HOPPER, FURNACE, DROPPER -> machines.get() ? new float[]{0.3f, 0.8f, 1f} : null;
            case SPAWNER -> spawners.get() ? new float[]{1f, 0.25f, 0.25f} : null;
            default -> null;
        };
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
        if (ctx.consumers() == null) {
            return;
        }
        // One tracer origin per frame (it allocates); the per-hit loop uses raw coordinates.
        boolean drawTracers = tracers.get();
        Vec3d from = drawTracers ? RenderUtil.crosshairOrigin() : null;
        boolean through = walls.get();
        for (EspMemory.Seen seen : remembered) {
            float[] c = color(seen.mat());
            if (c != null) {
                float a = EspDraw.alpha(seen.live());
                EspDraw.box(ctx, seen.pos, 0.0, c, a);
                if (drawTracers) {
                    RenderUtil.drawLine(ctx.matrices(), ctx.consumers(), from, seen.pos.x() + 0.5,
                            seen.pos.y() + 0.5, seen.pos.z() + 0.5, c[0], c[1], c[2], a * 0.7f, through);
                }
            }
        }
        EspDraw.traces(ctx, hints, false);
        for (Hit h : list) {
            Box b = h.box;
            if (!RenderUtil.boxBehindCamera(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ)) {
                RenderUtil.drawBoxOutline(ctx.matrices(), ctx.consumers(), b, h.r, h.g, h.b, 1f, through);
            }
            if (drawTracers) {
                RenderUtil.drawLine(ctx.matrices(), ctx.consumers(), from,
                        (b.minX + b.maxX) * 0.5, (b.minY + b.maxY) * 0.5, (b.minZ + b.maxZ) * 0.5,
                        h.r, h.g, h.b, 0.7f, through);
            }
        }
    }

    @Override
    public String hudLabel() {
        return name + " · " + lastCount + " · " + hints.size() + " hints";
    }
}
