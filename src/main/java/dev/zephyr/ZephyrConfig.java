package dev.zephyr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleManager;
import dev.zephyr.core.settings.Setting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * JSON config: { "ModuleName": { "enabled": true, "settingId": value } }.
 *
 * <p>The live config is config/zephyr.json (saved on every change). Named configs live in
 * config/zephyr/configs/NAME.json and are managed from the Configs screen: save, load,
 * delete, start fresh, generate from the Legit/Smart profiles, and copy/paste as text
 * ("post" one to a friend or a Discord channel, import one someone sent you).
 */
public final class ZephyrConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson COMPACT = new Gson();
    private static final Path FILE = Path.of("config", "zephyr.json");
    private static final Path DIR = Path.of("config", "zephyr", "configs");

    private final ModuleManager modules;

    public ZephyrConfig(ModuleManager modules) {
        this.modules = modules;
    }

    // ------------------------------------------------------------------
    // Live config
    // ------------------------------------------------------------------

    public void load() {
        try {
            if (Files.exists(FILE)) {
                applyJson(Files.readString(FILE, StandardCharsets.UTF_8), false);
            }
        } catch (IOException | RuntimeException ignored) {
            // Corrupt config must not brick startup; defaults stay in force.
        }
    }

    public void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, toJson(true), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public String toJson(boolean pretty) {
        Map<String, Map<String, Object>> data = new LinkedHashMap<>();
        modules.writeSettings(data);
        return (pretty ? GSON : COMPACT).toJson(data);
    }

    /**
     * Applies a config. With {@code full} every module is first switched off and reset to
     * its defaults, so the result is exactly the config (used for named configs/imports).
     */
    @SuppressWarnings("unchecked")
    public void applyJson(String json, boolean full) {
        var parsed = GSON.<Map<String, Object>>fromJson(json, Map.class);
        if (parsed == null) {
            throw new IllegalArgumentException("empty config");
        }
        Map<String, Map<String, Object>> data = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : parsed.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> inner) {
                Map<String, Object> cast = new LinkedHashMap<>();
                for (Map.Entry<?, ?> kv : inner.entrySet()) {
                    cast.put(String.valueOf(kv.getKey()), kv.getValue());
                }
                data.put(e.getKey(), cast);
            }
        }
        if (data.isEmpty()) {
            throw new IllegalArgumentException("not a Zephyr config");
        }
        if (full) {
            resetAll();
        }
        modules.readSettings(data);
    }

    /** Every module off and back to defaults (the menu key is kept so you can reopen it). */
    public void resetAll() {
        for (Module m : modules.all()) {
            m.setEnabled(false);
            if (m instanceof dev.zephyr.modules.gui.ClickGui) {
                continue;
            }
            for (Setting<?> s : m.settings()) {
                s.reset();
            }
        }
    }

    // ------------------------------------------------------------------
    // Named configs
    // ------------------------------------------------------------------

    public static String sanitize(String name) {
        String s = name == null ? "" : name.trim().replaceAll("[^A-Za-z0-9 _.-]", "");
        return s.isEmpty() ? "config" : (s.length() > 40 ? s.substring(0, 40) : s);
    }

    public List<String> list() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(DIR)) {
            return out;
        }
        try (Stream<Path> files = Files.list(DIR)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(p -> {
                        String n = p.getFileName().toString();
                        out.add(n.substring(0, n.length() - 5));
                    });
        } catch (IOException ignored) {
        }
        return out;
    }

    public void saveAs(String name) throws IOException {
        Files.createDirectories(DIR);
        Files.writeString(DIR.resolve(sanitize(name) + ".json"), toJson(true), StandardCharsets.UTF_8);
    }

    public void loadNamed(String name) throws IOException {
        applyJson(Files.readString(DIR.resolve(sanitize(name) + ".json"), StandardCharsets.UTF_8), true);
        save();
    }

    public void delete(String name) throws IOException {
        Files.deleteIfExists(DIR.resolve(sanitize(name) + ".json"));
    }

    // ------------------------------------------------------------------
    // Generator
    // ------------------------------------------------------------------

    public static final List<String> GENERATORS = List.of("Legit Sword", "Smart Sword", "Crystal PvP", "Mace PvP", "Anchor PvP");

    /**
     * Builds a complete config from scratch for a play style: resets everything, enables
     * the modules that style uses, and applies the LEGIT or SMART profile to each.
     * Returns the name it was saved under.
     */
    public String generate(String kind) throws IOException {
        resetAll();
        Set<String> visuals = Set.of("HUD", "TargetHUD", "ESP", "NameTags");
        Set<String> on;
        String profile;
        switch (kind) {
            case "Legit Sword" -> {
                on = Set.of("AimAssist", "TriggerBot", "JumpReset", "STap", "AutoTotem");
                profile = "LEGIT";
            }
            case "Smart Sword" -> {
                on = Set.of("LegitAura", "STap", "JumpReset", "ShieldBreaker", "AutoTotem", "BreachSwap");
                profile = "SMART";
            }
            case "Crystal PvP" -> {
                on = Set.of("AutoHitCrystal", "JumpReset", "AutoTotem", "CrystalESP");
                profile = "SMART";
            }
            case "Mace PvP" -> {
                on = Set.of("AutoMace", "BreachSwap", "JumpReset", "AutoTotem", "PearlCatch", "LungeMacro");
                profile = "SMART";
            }
            default -> {
                on = Set.of("SafeAnchor", "JumpReset", "AutoTotem");
                profile = "SMART";
            }
        }
        for (Module m : modules.all()) {
            if (m.hasProfile()) {
                m.applyProfile(profile);
            }
        }
        for (Module m : modules.all()) {
            if (on.contains(m.name()) || visuals.contains(m.name())) {
                m.setEnabled(true);
            }
        }
        save();
        String name = kind.replace(' ', '-').toLowerCase();
        saveAs(name);
        return name;
    }
}
