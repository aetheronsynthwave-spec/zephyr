package dev.zephyr.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One-line, player-facing description of every module, keyed by module name. Shown as the
 * ClickGui hover tooltip, matched by the GUI search, and printed by {@code /zephyr info}.
 * Kept in one table so wording stays consistent and new modules are easy to document.
 */
public final class ModuleDescriptions {

    private ModuleDescriptions() {
    }

    private static final Map<String, String> TEXT = new HashMap<>();

    private static void d(String module, String description) {
        TEXT.put(module.toLowerCase(Locale.ROOT), description);
    }

    static {
        // Combat
        d("AimAssist", "Gently pulls your view toward the nearest enemy while you hold attack. Never snaps.");
        d("AutoMace", "Strikes the moment you fall onto a player in reach (TRIGGER default), swapping to the mace for that hit and back.");
        d("ElytraMaceBot", "Elytra mace bot: gets over the target, looks straight down, rockets, takes the elytra off and mace smashes from above, then chains off the Wind Burst bounce.");
        d("SmartMaceBot", "No-elytra mace bot: weighs height, fall, reach and weapons to launch (wind charge), drop-smash, melee or approach.");
        d("BreachSwap", "Swaps every melee hit to your Breach mace for that hit, then back to your sword.");
        d("JumpReset", "Jumps in the same tick you take a hit to reduce knockback.");
        d("LegitAura", "Attacks a visible nearby target with smooth rotations and full-charge timing.");
        d("LungeMacro", "Press the bind to swap to your spear, jab (fires Lunge), and swap back.");
        d("STap", "Briefly taps back or releases forward after a sprint hit to keep combos going.");
        d("ShieldBreaker", "Axe-hits a raised shield to disable it, then swaps back. With AutoMace: stun slam.");
        d("TriggerBot", "Attacks when your crosshair is on an enemy and your weapon is charged. Holds off for AutoMace and ShieldBreaker.");
        // Crystal
        d("AnchorMacro", "Places, charges and detonates respawn anchors near your target.");
        d("AutoCrystal", "Places and breaks end crystals at the best damage spot near your target.");
        d("AutoHitCrystal", "Melee hit, then a crystal place/break chain, then back to your sword. Repeats.");
        d("SafeAnchor", "Anchor macro that places a glowstone shield between you and the anchor first.");
        // Survival
        d("AutoLog", "Disconnects when your health or totem count drops below your limits.");
        d("AutoTotem", "Keeps a totem in your offhand, moving one from your inventory when needed.");
        d("FastXP", "Throws XP bottles quickly at your feet for fast armour mending.");
        d("PearlCatch", "Press the bind: throws a pearl up, then hits it mid-air with a wind charge. Your view never moves.");
        d("AutoRespawn", "Clicks Respawn for you on the death screen after a short delay.");
        d("DurabilityAlert", "Warns you on screen (with a sound) when held items or armour are about to break.");
        // Utility
        d("NameHider", "Replaces your own name with an alias in chat on your screen only. For streaming.");
        d("SoundDetector", "Tells you the direction and distance of sounds like explosions, totems and chests.");
        d("StaffDetector", "Alerts you about likely staff: listed names, spectators and invisible players.");
        d("AutoTool", "While mining, switches to the fastest hotbar tool for the block, then back.");
        d("Zoom", "Hold the bind to zoom in smoothly, like a spyglass. Lower mouse sensitivity while zoomed.");
        // Movement
        d("Fly", "Creative-style flight (double-tap jump).");
        d("Freecam", "Moves the camera freely while your player stays still.");
        d("Freelook", "Hold the bind to look around without changing where you walk or face.");
        d("NoDelay", "Removes the vanilla attack, item-use and jump delays.");
        d("NoPush", "Stops entities, knockback and water from pushing you.");
        d("Sprint", "Sprints automatically whenever you can (toggle sprint).");
        // Render / HUD
        d("BaseFinder", "Highlights signs of player bases in loaded chunks: chests, spawners, beds and more.");
        d("BlockESP", "Outlines chosen blocks (ores, debris, amethyst, spawners) through walls.");
        d("CrystalESP", "Outlines end crystals, coloured by how much damage they would do to you.");
        d("DamageNumbers", "Shows floating numbers when nearby players take damage.");
        d("ESP", "Draws boxes around players (optionally mobs) visible through walls.");
        d("FancyOutlines", "Draws a glowing outline around your current target.");
        d("Fullbright", "Makes everything fully bright without changing your saved brightness.");
        d("LightDebug", "Marks blocks where hostile mobs can spawn right now.");
        d("NameTags", "Shows cleaner player name tags with live health.");
        d("TargetHUD", "Shows your current target's health, armour, items and distance on screen.");
        d("Tracers", "Draws a line from your crosshair to every nearby player.");
        d("HUD", "Shows the Zephyr logo and your active modules in the top-left corner.");
        d("Hotbar", "Blurs your hotbar and offhand items on screen. For streaming privacy.");
        d("ProgressBar", "Streaming overlay with Overworld/Nether/End milestones and a bases-found counter.");
        d("InfoHUD", "Shows coordinates, nether/overworld coords, facing, speed, FPS and ping.");
        d("ArmorHUD", "Shows your armour and held items with durability above the hotbar.");
        d("SpotifyLyrics", "Shows synced lyrics for the song playing in the Spotify desktop app (Windows).");
        d("SessionHUD", "Shows this session's play time, distance travelled, hits and deaths.");
        d("Waypoints", "Saved markers with beams and distance. /zephyr wp add|remove|list|clear.");
        d("Keystrokes", "Shows WASD, jump and mouse buttons on screen, with clicks per second.");
        // Gui
        d("ClickGui", "Opens this menu. Default key: Right Shift.");
    }

    /** @return the description for {@code moduleName}, or an empty string if none is registered. */
    public static String of(String moduleName) {
        return TEXT.getOrDefault(moduleName.toLowerCase(Locale.ROOT), "");
    }
}
