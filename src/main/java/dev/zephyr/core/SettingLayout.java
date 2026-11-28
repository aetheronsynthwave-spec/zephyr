package dev.zephyr.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which settings of a module show straight away. Everything not named here sits in the
 * module's Advanced section. The profile (where a module has one) and the key bind are
 * basic everywhere, except that the bind moves down when nothing else would be advanced,
 * so every module has an Advanced section. Free of game classes.
 */
public final class SettingLayout {

    private static final Map<String, List<String>> BASIC = new HashMap<>();

    private static void basic(String module, String... ids) {
        BASIC.put(module, List.of(ids));
    }

    static {
        basic("ClickGui", "scale");
        // Combat
        basic("AutoMace", "mode", "playersOnly", "stunSlam");
        basic("ElytraMaceBot", "range", "minHealth", "aiMode");
        basic("SmartMaceBot", "range", "minHealth", "pearlCatch", "elytra");
        basic("BreachSwap", "playersOnly", "weaponOnly");
        basic("LungeMacro", "preferLunge", "returnOriginal");
        basic("LegitAura", "range");
        basic("STap", "mode");
        basic("JumpReset", "chance");
        basic("AimAssist", "range", "fov");
        basic("TriggerBot", "playersOnly", "weapon");
        basic("ShieldBreaker", "stunSlam");
        // Crystal & anchor
        basic("AutoCrystalPvP", "range", "move", "anchors");
        basic("AutoCrystal", "mode", "minDmg", "maxSelf");
        basic("AutoHitCrystal", "minDmg", "maxSelf", "obsidian");
        basic("AnchorMacro", "range", "maxSelf");
        basic("SafeAnchor", "range", "maxSelf");
        // Survival
        basic("AutoTotem", "mode", "health");
        basic("FastXP", "keep");
        basic("PearlCatch");
        basic("AutoLog", "health", "noTotems");
        basic("AutoRespawn", "coords");
        basic("DurabilityAlert", "warn", "sound");
        // Movement
        basic("NoDelay", "attack", "use", "jump");
        basic("NoPush", "entities", "knockback", "fluids");
        basic("Sprint", "omni");
        basic("Freelook", "hold");
        basic("WalkInventory", "sprint", "jump");
        // Utility & DonutSMP
        basic("StaffDetector", "chat");
        basic("SoundDetector", "minDist");
        basic("AutoTool", "swapBack", "saveTools");
        basic("Zoom", "level", "hold");
        basic("Waypoints", "beams");
        basic("Freecam", "speed");
        basic("Fly", "speed");
        basic("AuctionFlipper", "auto", "askFirst", "maxPrice", "budget", "sellMargin");
        basic("CraftAutomate", "askFirst", "maxPrice", "minGain");
        basic("AmethystFinder", "range", "tracers", "remember", "hidden");
        basic("StorageESP", "range", "tracers", "remember", "hidden");
        // Render & HUD
        basic("Fullbright");
        basic("XRay", "diamonds", "netherite", "storage");
        basic("CustomSky", "style");
        basic("HandChams", "style", "opacity");
        basic("Cosmetics", "halo", "hat", "wings");
        basic("BlockESP", "preset", "radius", "remember");
        basic("LightDebug", "radius");
        basic("BaseFinder", "range", "tracers", "remember", "suspect");
        basic("ProgressBar", "overworld", "nether", "end", "bases");
        basic("Hotbar", "amount");
        basic("InfoHUD", "coords", "fps", "ping");
        basic("ArmorHUD", "hands");
        basic("Keystrokes", "mouse");
        basic("SpotifyLyrics", "display", "offset");
        basic("SessionHUD");
        basic("NameHider");
        basic("ESP", "range", "players", "hostiles");
        basic("Tracers", "range", "players", "hostiles");
        basic("CrystalESP", "range");
        basic("NameTags", "range", "health");
        basic("DamageNumbers", "all");
        basic("TargetHUD", "items");
        basic("FancyOutlines", "inflate");
        basic("HUD", "watermark");
    }

    private SettingLayout() {
    }

    /** The ids listed for this module (without profile and bind), or an empty list. */
    public static List<String> listed(String module) {
        return BASIC.getOrDefault(module, List.of());
    }

    public static boolean known(String module) {
        return BASIC.containsKey(module);
    }

    /** Splits a module's setting ids, given in declaration order, into [basic, advanced]. */
    public static List<List<String>> split(String module, List<String> ids) {
        List<String> basic = new ArrayList<>();
        List<String> advanced = new ArrayList<>();
        if (ids.contains("profile")) {
            basic.add("profile");
        }
        for (String id : listed(module)) {
            if (ids.contains(id)) {
                basic.add(id);
            }
        }
        for (String id : ids) {
            if (!basic.contains(id) && !id.equals("bind")) {
                advanced.add(id);
            }
        }
        if (ids.contains("bind")) {
            (advanced.isEmpty() ? advanced : basic).add("bind");
        }
        return List.of(basic, advanced);
    }
}
