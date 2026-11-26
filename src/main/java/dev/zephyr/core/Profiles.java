package dev.zephyr.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-module LEGIT and SMART presets (setting id -> value).
 *
 * <p><b>LEGIT</b> = the most human-looking configuration for 1.21.11 (cooldown-era) PvP:
 * attacks only on (near) full charge, 7-11 CPS-like pacing, slow visible aim with
 * jitter, no silent rotations, visible item swaps, reaction delays, occasional misses.
 *
 * <p><b>SMART</b> = strongest sensible configuration; the module additionally adapts at
 * runtime where it implements it (see each module's {@code smart()} checks): e.g. attacks
 * timed exactly to the charge, S-tap length from distance, Breach only against armour,
 * jump reset only on real knockback, pearl-catch intercept math.
 *
 * <p>Picking a profile writes these values into the module's settings; afterwards every
 * setting can still be tuned by hand (profile "CUSTOM" applies nothing).
 */
public final class Profiles {

    private Profiles() {
    }

    public static final List<String> MODES = List.of("CUSTOM", "SMART", "LEGIT");

    private static final Map<String, Map<String, Object>> LEGIT = new HashMap<>();
    private static final Map<String, Map<String, Object>> SMART = new HashMap<>();

    private static void legit(String module, Object... kv) {
        LEGIT.put(module, map(kv));
    }

    private static void smart(String module, Object... kv) {
        SMART.put(module, map(kv));
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static {
        legit("LegitAura", "range", 3.0, "cpsMin", 7.0, "cpsMax", 11.0, "cooldown", 0.92, "hurtTime", 10.0,
                "rotMin", 12.0, "rotMax", 28.0, "jitter", 0.6, "tolerance", 6.0, "requireVisible", true,
                "skipChance", true, "silent", false, "reaction", true);
        smart("LegitAura", "range", 3.0, "cpsMin", 10.0, "cpsMax", 14.0, "cooldown", 0.95, "hurtTime", 10.0,
                "rotMin", 20.0, "rotMax", 50.0, "jitter", 0.35, "tolerance", 4.0, "requireVisible", true,
                "skipChance", false, "silent", false, "reaction", true);

        legit("AimAssist", "range", 4.0, "fov", 60.0, "speedMin", 1.0, "speedMax", 4.0,
                "onlyWhileClicking", true, "vertical", false, "weapon", true);
        smart("AimAssist", "range", 4.5, "fov", 90.0, "speedMin", 2.0, "speedMax", 8.0,
                "onlyWhileClicking", true, "vertical", true, "weapon", true);

        legit("TriggerBot", "cooldown", 0.95, "reactMin", 80.0, "reactMax", 160.0, "playersOnly", true, "weapon", true);
        smart("TriggerBot", "cooldown", 0.92, "reactMin", 20.0, "reactMax", 60.0, "playersOnly", true, "weapon", true);

        legit("AutoMace", "aim", false, "aimFov", 10.0, "showSwap", true, "backTicks", 3.0, "minFall", 2.0,
                "rotMin", 10.0, "rotMax", 20.0, "weaponOnly", true);
        smart("AutoMace", "aim", true, "showSwap", true, "backTicks", 2.0, "minFall", 1.5,
                "rotMin", 20.0, "rotMax", 45.0, "weaponOnly", false);

        legit("BreachSwap", "showSwap", true, "backTicks", 4.0, "weaponOnly", true, "playersOnly", true);
        smart("BreachSwap", "showSwap", true, "backTicks", 2.0, "weaponOnly", true, "playersOnly", true);

        legit("JumpReset", "chance", 0.75, "armWindow", 3.0, "inAir", false);
        smart("JumpReset", "chance", 1.0, "armWindow", 6.0, "inAir", true);

        legit("STap", "mode", "S_TAP", "tapMin", 2.0, "tapMax", 4.0, "delay", 1.0, "onlySprint", true, "resprint", true);
        smart("STap", "mode", "S_TAP", "tapMin", 1.0, "tapMax", 3.0, "delay", 0.0, "onlySprint", true, "resprint", true);

        legit("ShieldBreaker", "swapBack", 220.0);
        smart("ShieldBreaker", "swapBack", 100.0);

        legit("LungeMacro", "showSwap", true, "backTicks", 3.0, "cooldown", 400.0);
        smart("LungeMacro", "showSwap", true, "backTicks", 2.0, "cooldown", 150.0);

        legit("AutoCrystal", "rotation", "SMOOTH", "cpsMin", 10.0, "cpsMax", 13.0, "placeMin", 40.0, "placeMax", 90.0,
                "rotMin", 15.0, "rotMax", 35.0, "maxSelf", 6.0, "minDmg", 4.0, "faceplace", 3.0);
        smart("AutoCrystal", "rotation", "SILENT", "cpsMin", 14.0, "cpsMax", 18.0, "placeMin", 0.0, "placeMax", 25.0,
                "rotMin", 30.0, "rotMax", 70.0, "maxSelf", 8.0, "minDmg", 3.0, "faceplace", 6.0);

        legit("AutoHitCrystal", "cpsMin", 6.0, "cpsMax", 9.0, "swapMin", 50.0, "swapMax", 100.0,
                "rotMin", 12.0, "rotMax", 30.0, "aimTol", 8.0, "perHit", 1.0, "ownObsidian", true);
        smart("AutoHitCrystal", "cpsMin", 10.0, "cpsMax", 14.0, "swapMin", 25.0, "swapMax", 55.0,
                "rotMin", 20.0, "rotMax", 45.0, "aimTol", 10.0, "perHit", 2.0, "ownObsidian", true);

        legit("AnchorMacro", "silent", false, "rotMin", 25.0, "rotMax", 55.0);
        smart("AnchorMacro", "silent", true, "rotMin", 50.0, "rotMax", 90.0);
        legit("SafeAnchor", "silent", false, "rotMin", 25.0, "rotMax", 55.0);
        smart("SafeAnchor", "silent", true, "rotMin", 50.0, "rotMax", 90.0);

        legit("PearlCatch", "gapMin", 220.0, "gapMax", 300.0, "lookPitch", 90.0);
        smart("PearlCatch", "gapMin", 150.0, "gapMax", 220.0, "lookPitch", 90.0);
    }

    /** True for modules that get a Profile setting. */
    public static boolean has(String module) {
        return LEGIT.containsKey(module) || SMART.containsKey(module);
    }

    public static Map<String, Object> preset(String module, String mode) {
        Map<String, Object> m = "LEGIT".equals(mode) ? LEGIT.get(module) : "SMART".equals(mode) ? SMART.get(module) : null;
        return m != null ? m : Map.of();
    }
}
