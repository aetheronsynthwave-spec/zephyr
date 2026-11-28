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
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
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
 * StorageESP - outlines containers through walls, one colour per kind, from the block
 * entities and blocks of chunks the server has already sent; nothing is requested and
 * nothing is opened. {@link EspTracker} adds what was seen earlier (fainter) and chunk
 * hints: storage a section palette lists but no block shows, unexplained light underground,
 * and storage sounds.
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
    private final BooleanSetting remember = bool("remember", "Remember finds", true);
    private final BooleanSetting hidden = bool("hidden", "Hidden storage hints", true);
    private final NumberSetting memoryRange = num("memoryRange", "Memory range (blocks)", 64, 1024, 32, 256);
    private final BooleanSetting palette = bool("palette", "Hint: palette lists storage", true);
    private final BooleanSetting underLight = bool("underLight", "Hint: light underground", true);
    private final BooleanSetting sounds = bool("sounds", "Hint: storage sounds", true);
    /* The next four steer the scanner shared by all the ESP modules. */
    private final NumberSetting verify = num("verify", "Forget only within (blocks)", 4, 64, 2, 16);
    private final NumberSetting scanTime = num("scanTime", "Scan time per tick (ms)", 1, 15, 1, 3);
    private final BooleanSetting probe = bool("probe", "Write probe file now", false);
    private final BooleanSetting forget = bool("forget", "Forget this world now", false);

    private static final int MAX_TRACES = 96;

    private record Hit(Box box, float r, float g, float b, double distance) {
    }

    private volatile List<Hit> hits = List.of();
    private volatile List<EspMemory.Seen> remembered = List.of();
    private volatile List<EspMemory.Hint> hints = List.of();
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
    }

    void configure(EspTracker.Config config) {
        config.verify = verify.get();
        config.budgetMs = scanTime.get();
        if (probe.get()) {
            config.probe = true;
            probe.set(false);
        }
        if (forget.get()) {
            config.forget = true;
            forget.set(false);
        }
    }

    @Override
    public void onTick(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            return;
        }
        EspTracker.get().tick(client);
        if (ticks++ % 10 != 0) {
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
        recall(client);
    }

    /** Pulls the matching memory: blocks not already outlined above, and chunk hints. */
    private void recall(MinecraftClient client) {
        Pos me = EspTracker.viewer(client);
        EspMemory memory = EspTracker.get().memory();
        Set<Pos> shown = new HashSet<>();
        for (Hit hit : hits) {
            shown.add(new Pos((int) Math.floor(hit.box.minX), (int) Math.floor(hit.box.minY),
                    (int) Math.floor(hit.box.minZ)));
        }
        boolean old = remember.get();
        List<EspMemory.Seen> blocks = memory.finds(m -> color(m) != null, me, memoryRange.get(),
                maxBoxes.asInt() + shown.size());
        blocks.removeIf(s -> shown.contains(s.pos) || !s.live() && !old);
        remembered = List.copyOf(blocks.subList(0, Math.min(blocks.size(), maxBoxes.asInt())));

        Set<Trace.Kind> kinds = EnumSet.noneOf(Trace.Kind.class);
        if (hidden.get()) {
            if (palette.get()) {
                kinds.add(Trace.Kind.PALETTE_STORAGE);
            }
            if (underLight.get()) {
                kinds.add(Trace.Kind.DEEP_LIGHT);
            }
            if (sounds.get()) {
                kinds.add(Trace.Kind.SOUND_STORAGE);
            }
        }
        List<EspMemory.Hint> list = memory.hints(kinds, me, memoryRange.get(), MAX_TRACES * 2);
        list.removeIf(h -> !h.live() && !old || h.trace().kind() == Trace.Kind.PALETTE_STORAGE
                && !wantsAny(h.trace().mats()));
        hints = List.copyOf(list.subList(0, Math.min(list.size(), MAX_TRACES)));
    }

    private boolean wantsAny(long mats) {
        for (Mat mat : Mat.values()) {
            if ((mats & mat.bit()) != 0 && color(mat) != null) {
                return true;
            }
        }
        return false;
    }

    /** The colour for a remembered block, or null when its kind is switched off. */
    private float[] color(Mat mat) {
        return switch (mat) {
            case CHEST -> color("chest");
            case SHULKER -> color("shulker_box");
            case BARREL -> color("barrel");
            case ENDER_CHEST -> color("ender_chest");
            case HOPPER -> color("hopper");
            case FURNACE -> color("furnace");
            case DROPPER -> color("dropper");
            case SPAWNER -> color("mob_spawner");
            default -> null;
        };
    }

    private void classify(String id, BlockPos pos, double distance, List<Hit> out) {
        float[] color = color(id);
        if (color != null) {
            out.add(new Hit(new Box(pos).contract(0.04), color[0], color[1], color[2], distance));
        }
    }

    private float[] color(String id) {
        return switch (id) {
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
        if (context.consumers() == null || context.matrices() == null) {
            return;
        }
        boolean lines = tracers.get();
        Vec3d from = lines ? RenderUtil.crosshairOrigin() : null;
        for (EspMemory.Seen seen : remembered) {
            float[] c = color(seen.mat());
            if (c == null) {
                continue;
            }
            float a = EspDraw.alpha(seen.live());
            EspDraw.box(context, seen.pos, 0.04, c, a);
            if (lines) {
                RenderUtil.drawLine(context.matrices(), context.consumers(), from, seen.pos.x() + 0.5,
                        seen.pos.y() + 0.5, seen.pos.z() + 0.5, c[0], c[1], c[2], a * 0.7f, true);
            }
        }
        EspDraw.traces(context, hints, lines);
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
        return name + " · " + hits.size() + " · " + remembered.size() + " saved · " + hints.size() + " hints";
    }
}
