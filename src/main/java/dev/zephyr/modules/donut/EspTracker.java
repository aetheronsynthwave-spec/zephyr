package dev.zephyr.modules.donut;

import dev.zephyr.ZephyrClient;
import dev.zephyr.modules.donut.scan.ChunkScan;
import dev.zephyr.modules.donut.scan.EspMemory;
import dev.zephyr.modules.donut.scan.Pos;
import dev.zephyr.modules.donut.scan.ProbeReport;
import dev.zephyr.modules.donut.scan.ScanPlan;
import dev.zephyr.modules.donut.scan.SuspectHeat;
import dev.zephyr.modules.donut.scan.Trace;
import dev.zephyr.modules.render.BaseFinder;
import dev.zephyr.modules.render.RenderUtil;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.world.chunk.WorldChunk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The one scanner behind StorageESP, AmethystFinder and BaseFinder. Within a time budget it
 * walks the loaded chunks (the ones around the player first, then new ones, then the longest
 * unchecked) and keeps the result per server and dimension under config/zephyr/esp-memory (singleplayer
 * worlds are remembered for the session only). It runs while any of those modules is on.
 */
public final class EspTracker {

    /** Shared and per-signal numbers, gathered from the modules every tick. */
    public static final class Config {
        public double verify = 16;
        public double budgetMs = 3;
        public boolean probe;
        public boolean forget;
        public int glowCells = 12;
        public int lightMin = 5;
        public int lightCells = 8;
        public boolean denoise = true;
        public boolean skipLava = true;
        public int spread = 5;
        public int sensitivity = 6;
        public String growth = "AUTO";
    }

    private static final EspTracker INSTANCE = new EspTracker();
    private static final int PASS_RADIUS = 33;
    private static final int NEAR_EVERY = 40;
    private static final int SAVE_EVERY = 600;
    private static final int PROBE_RADIUS = 2;
    /** How long a heard sound stays marked. */
    private static final long SOUND_LIFE_MILLIS = 60L * 60 * 1000;

    private final SuspectHeat heat = new SuspectHeat();
    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    /** Loaded chunks already scanned, with the tick of their latest scan. */
    private final Map<Long, Integer> scannedAt = new HashMap<>();
    /** Singleplayer memories by dimension, dropped on leaving the world. */
    private final Map<String, EspMemory> session = new HashMap<>();
    private Config config = new Config();
    private EspMemory memory = new EspMemory();
    private Object world;
    private String server = "";
    private Path file;
    private int lastTick = -1;
    private int age;
    private int planAge;
    private boolean covered;

    private EspTracker() {
    }

    public static EspTracker get() {
        return INSTANCE;
    }

    public EspMemory memory() {
        return memory;
    }

    public static Pos viewer(MinecraftClient client) {
        var p = client.player.getBlockPos();
        return new Pos(p.getX(), p.getY(), p.getZ());
    }

    /** Safe to call from every module each tick; the work happens once. */
    public void tick(MinecraftClient client) {
        if (client.world == null || client.player == null || lastTick == RenderUtil.currentTick()) {
            return;
        }
        lastTick = RenderUtil.currentTick();
        config = gather();
        if (client.world != world) {
            enter(client);
        }
        if (config.forget) {
            memory.clear();
            restart();
            say(client, "ESP memory for this world cleared");
        }
        age++;
        GameScanWorld view = new GameScanWorld(client.world);
        Pos me = viewer(client);
        if (++planAge >= NEAR_EVERY || queue.isEmpty() && planAge >= 5) {
            plan(view, me);
        }

        long deadline = System.nanoTime() + (long) (config.budgetMs * 1_000_000);
        ChunkScan.Params params = params(config);
        while (!queue.isEmpty()) {
            long key = queue.pollFirst();
            if (view.loaded(Pos.keyX(key), Pos.keyZ(key))) {
                ChunkScan.Result result = ChunkScan.scan(view, Pos.keyX(key), Pos.keyZ(key), params);
                memory.merge(result, me, System.currentTimeMillis(), config.verify);
                heat.put(result);
                scannedAt.put(key, age);
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
        }

        if (covered && age % NEAR_EVERY == 0) {
            memory.suspects(heat.compute(config.spread, config.sensitivity), heat.scanned(), me,
                    System.currentTimeMillis(), config.verify);
        }
        if (config.probe) {
            probe(client, view, me);
        }
        if (age % SAVE_EVERY == 0) {
            memory.expireSounds(System.currentTimeMillis(), SOUND_LIFE_MILLIS);
            save(client);
        }
    }

    /** Rebuilds the work list (see {@link ScanPlan}) and marks chunks that left as memory. */
    private void plan(GameScanWorld view, Pos me) {
        planAge = 0;
        Iterator<Long> old = scannedAt.keySet().iterator();
        while (old.hasNext()) {
            long key = old.next();
            if (!view.loaded(Pos.keyX(key), Pos.keyZ(key))) {
                old.remove();
                heat.remove(key);
                memory.unload(key);
            }
        }
        ScanPlan.Plan next = ScanPlan.make(me.x() >> 4, me.z() >> 4, PASS_RADIUS, view::loaded, scannedAt,
                age, NEAR_EVERY);
        queue.clear();
        queue.addAll(next.order());
        // Growth heat compares neighbours, so it waits until most chunks have been seen once.
        covered |= next.covered();
    }

    private void restart() {
        heat.clear();
        queue.clear();
        scannedAt.clear();
        covered = false;
        planAge = NEAR_EVERY;
    }

    private static Config gather() {
        Config c = new Config();
        if (ZephyrClient.modules() == null) {
            return c;
        }
        StorageESP storage = ZephyrClient.modules().get(StorageESP.class);
        AmethystFinder amethyst = ZephyrClient.modules().get(AmethystFinder.class);
        BaseFinder base = ZephyrClient.modules().get(BaseFinder.class);
        if (storage != null) {
            storage.configure(c);
        }
        if (amethyst != null) {
            amethyst.configure(c);
        }
        if (base != null) {
            base.configure(c);
        }
        return c;
    }

    private ChunkScan.Params params(Config c) {
        // Donut Client judges growth by palette on the "DonutFolia" server software. Taking
        // a donutsmp address to mean that software is an assumption; the setting overrides it.
        boolean byPalette = c.growth.equals("PALETTE")
                || c.growth.equals("AUTO") && server.contains("donutsmp");
        return new ChunkScan.Params(c.glowCells, c.lightMin, c.lightCells, c.denoise, c.skipLava, byPalette);
    }

    /** A block sound from the server: marks where it came from. Client thread only. */
    public void sound(MinecraftClient client, String path, double x, double y, double z) {
        if (world == null || client.world != world || RenderUtil.currentTick() - lastTick > 20) {
            return;
        }
        Trace.Kind kind;
        if (path.startsWith("block.amethyst")) {
            kind = Trace.Kind.SOUND_AMETHYST;
        } else if (path.startsWith("block.chest.") || path.startsWith("block.barrel.")
                || path.startsWith("block.shulker_box.") || path.startsWith("block.ender_chest.")) {
            kind = Trace.Kind.SOUND_STORAGE;
        } else {
            return;
        }
        if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) {
            memory.sound(kind, new Pos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)),
                    System.currentTimeMillis());
        }
    }

    /** Saves and forgets the current world. Safe to call more than once. */
    public void leave() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && !client.isOnThread()) {
            // Disconnects can arrive on the network thread; the memory belongs to this one.
            client.execute(this::leave);
            return;
        }
        if (world != null) {
            save(null);
        }
        world = null;
        file = null;
        server = "";
        session.clear();
        memory = new EspMemory();
        restart();
    }

    private void enter(MinecraftClient client) {
        if (world != null) {
            save(client);
        }
        world = client.world;
        restart();
        String dimension = client.world.getRegistryKey().getValue().toString();
        var entry = client.getCurrentServerEntry();
        if (entry == null) {
            server = "";
            file = null;
            memory = session.computeIfAbsent(dimension, k -> new EspMemory());
            return;
        }
        server = entry.address.toLowerCase(java.util.Locale.ROOT);
        file = FabricLoader.getInstance().getConfigDir().resolve("zephyr").resolve("esp-memory")
                .resolve(safe(server) + "__" + safe(dimension) + ".json");
        memory = load(client, file);
    }

    private static String safe(String text) {
        return text.replaceAll("[^a-z0-9._-]", "_");
    }

    private EspMemory load(MinecraftClient client, Path path) {
        if (!Files.isRegularFile(path)) {
            return new EspMemory();
        }
        try {
            return EspMemory.fromJson(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            Path kept = path.resolveSibling(path.getFileName() + ".bad-" + System.currentTimeMillis());
            try {
                Files.move(path, kept);
                say(client, "ESP memory could not be read; kept it as " + kept.getFileName());
            } catch (IOException moveFailed) {
                // Saving would destroy the only copy.
                file = null;
                say(client, "ESP memory could not be read; this session will not be saved");
            }
            return new EspMemory();
        }
    }

    private void save(MinecraftClient client) {
        if (file == null || !memory.dirty()) {
            return;
        }
        try {
            write(file, memory.toJson());
        } catch (IOException e) {
            if (client != null) {
                say(client, "could not save ESP memory: " + e.getMessage());
            }
        }
    }

    private static void write(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        try {
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void probe(MinecraftClient client, GameScanWorld view, Pos me) {
        List<String> header = new ArrayList<>();
        header.add("server: " + (server.isEmpty() ? "singleplayer" : server));
        header.add("dimension: " + client.world.getRegistryKey().getValue());
        header.add("player: " + me.x() + " " + me.y() + " " + me.z());
        Map<String, Integer> entities = new TreeMap<>();
        for (int dx = -PROBE_RADIUS; dx <= PROBE_RADIUS; dx++) {
            for (int dz = -PROBE_RADIUS; dz <= PROBE_RADIUS; dz++) {
                WorldChunk chunk = client.world.getChunkManager()
                        .getWorldChunk((me.x() >> 4) + dx, (me.z() >> 4) + dz);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    String id = Registries.BLOCK_ENTITY_TYPE.getId(entity.getType()).getPath();
                    entities.merge("Y " + (entity.getPos().getY() >> 4 << 4) + " " + id, 1, Integer::sum);
                }
            }
        }
        header.add("block entities held: " + (entities.isEmpty() ? "none" : entities.toString()));
        String name = "esp-probe-y" + me.y() + "-" + System.currentTimeMillis() + ".txt";
        Path path = FabricLoader.getInstance().getConfigDir().resolve("zephyr").resolve(name);
        try {
            write(path, ProbeReport.build(view, me.x() >> 4, me.z() >> 4, PROBE_RADIUS, header));
            say(client, "ESP probe written to config/zephyr/" + name);
        } catch (IOException e) {
            say(client, "could not write the ESP probe: " + e.getMessage());
        }
    }

    private static void say(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[Zephyr] " + message), false);
        }
    }
}
