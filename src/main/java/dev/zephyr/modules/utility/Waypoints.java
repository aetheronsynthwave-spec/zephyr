package dev.zephyr.modules.utility;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.modules.render.RenderUtil;
import dev.zephyr.modules.render.WorldRenderParticipant;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Waypoints - named markers saved per server (or singleplayer world) and dimension, drawn as a
 * coloured beam with a "name - 123m" label. Managed with {@code /zephyr wp add|remove|list|clear}.
 * Saved to {@code config/zephyr-waypoints.json}.
 */
public final class Waypoints extends Module implements WorldRenderParticipant {

    private final NumberSetting maxDistance = num("maxDist", "Max draw distance", 50, 10000, 50, 5000);
    private final BooleanSetting beams = bool("beams", "Beams", true);

    public record Waypoint(String name, int x, int y, int z, float r, float g, float b) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("zephyr-waypoints.json");
    /** key = "server|dimension" -> waypoints. */
    private Map<String, List<Waypoint>> all = new LinkedHashMap<>();
    private boolean loaded;
    private final Map<String, Text> labelCache = new java.util.HashMap<>();

    public Waypoints() {
        super("Waypoints", ModuleCategory.UTILITY, 0);
    }

    private static String key(MinecraftClient mc) {
        String server = mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address
                : mc.getServer() != null ? "sp:" + mc.getServer().getSaveProperties().getLevelName() : "unknown";
        String dim = mc.world == null ? "?" : mc.world.getRegistryKey().getValue().toString();
        return server + "|" + dim;
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (Files.exists(FILE)) {
            try (Reader r = Files.newBufferedReader(FILE)) {
                Map<String, List<Waypoint>> m = GSON.fromJson(r,
                        new TypeToken<LinkedHashMap<String, List<Waypoint>>>() { }.getType());
                if (m != null) {
                    all = m;
                }
            } catch (Exception e) {
                System.err.println("[Zephyr] could not read waypoints: " + e);
            }
        }
    }

    private void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer w = Files.newBufferedWriter(FILE)) {
                GSON.toJson(all, w);
            }
        } catch (Exception e) {
            System.err.println("[Zephyr] could not save waypoints: " + e);
        }
    }

    public List<Waypoint> current(MinecraftClient mc) {
        ensureLoaded();
        return all.computeIfAbsent(key(mc), k -> new ArrayList<>());
    }

    /** Adds (or replaces) a waypoint at the player's block position. */
    public Waypoint add(MinecraftClient mc, String name) {
        List<Waypoint> list = current(mc);
        list.removeIf(w -> w.name().equalsIgnoreCase(name));
        int h = name.hashCode();
        float r = 0.4f + ((h >> 16) & 0xFF) / 425f, g = 0.4f + ((h >> 8) & 0xFF) / 425f, b = 0.4f + (h & 0xFF) / 425f;
        Waypoint wp = new Waypoint(name, mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(), r, g, b);
        list.add(wp);
        save();
        return wp;
    }

    public boolean remove(MinecraftClient mc, String name) {
        boolean removed = current(mc).removeIf(w -> w.name().equalsIgnoreCase(name));
        if (removed) {
            save();
        }
        return removed;
    }

    public void clear(MinecraftClient mc) {
        current(mc).clear();
        save();
    }

    @Override
    public void onWorldRender(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || ctx.consumers() == null) {
            return;
        }
        Vec3d cam = RenderUtil.cameraPos();
        double maxSq = maxDistance.get() * maxDistance.get();
        int bottom = mc.world.getBottomY();
        int top = bottom + mc.world.getHeight();
        for (Waypoint w : current(mc)) {
            double cx = w.x() + 0.5, cz = w.z() + 0.5;
            double dx = cx - cam.x, dz = cz - cam.z, dy = w.y() - cam.y;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > maxSq) {
                continue;
            }
            if (beams.get()) {
                RenderUtil.drawBoxOutline(ctx.matrices(), ctx.consumers(), cx - 0.15, bottom, cz - 0.15,
                        cx + 0.15, top, cz + 0.15, w.r(), w.g(), w.b(), 0.9f, true);
            }
            int dist = (int) Math.sqrt(distSq);
            // Label is pulled toward the camera when far away so it stays readable on screen.
            double len = Math.sqrt(distSq);
            double k = len > 12 ? 12 / len : 1.0;
            String cacheKey = w.name() + "|" + dist;
            Text label = labelCache.get(cacheKey);
            if (label == null) {
                if (labelCache.size() > 256) {
                    labelCache.clear();
                }
                label = Text.literal(w.name() + " - " + dist + "m");
                labelCache.put(cacheKey, label);
            }
            RenderUtil.drawBillboardText(ctx.matrices(), ctx.consumers(),
                    cam.x + dx * k, cam.y + (dy + 1.5) * k, cam.z + dz * k, label, 0xFFFFFFFF, 0x66000000, true);
        }
    }

    @Override
    public String hudLabel() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.world == null ? name : name + " · " + current(mc).size();
    }
}
