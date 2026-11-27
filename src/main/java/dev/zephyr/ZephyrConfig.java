package dev.zephyr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.zephyr.core.ModuleManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ultra-barebones JSON config: { "ModuleName": { "enabled": true, "settingId": value } }.
 * Saved on toggle + world leave; loaded once at client init. No schema ceremony on purpose.
 */
public final class ZephyrConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = Path.of("config", "zephyr.json");

    private final ModuleManager modules;

    public ZephyrConfig(ModuleManager modules) {
        this.modules = modules;
    }

    public void load() {
        try {
            if (!Files.exists(FILE)) {
                return;
            }
            String json = Files.readString(FILE, StandardCharsets.UTF_8);
            Map<String, Map<String, Object>> data = new LinkedHashMap<>();
            var parsed = GSON.<Map<String, Map<String, Object>>>fromJson(json, Map.class);
            if (parsed != null) {
                for (Map.Entry<String, Object> e : parsed.entrySet()) {
                    if (e.getValue() instanceof Map<?, ?> inner) {
                        Map<String, Object> cast = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> kv : inner.entrySet()) {
                            cast.put(String.valueOf(kv.getKey()), kv.getValue());
                        }
                        data.put(e.getKey(), cast);
                    }
                }
            }
            modules.readSettings(data);
        } catch (IOException | RuntimeException ignored) {
            // Corrupt config must not brick startup; defaults stay in force.
        }
    }

    public void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Map<String, Map<String, Object>> data = new LinkedHashMap<>();
            modules.writeSettings(data);
            Files.writeString(FILE, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}
