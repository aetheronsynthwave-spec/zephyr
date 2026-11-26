Zephyr Client v1.11.0 - Minecraft 1.21.11 (Fabric)
===================================================

WHAT'S NEW IN v1.11.0
---------------------
* PearlCatch: wind charge now intercepts the pearl (solved intercept, ping-compensated,
  interpolated aim), both throws silent.
* TargetHUD: X/Y sliders now work (percent of screen, auto-switch to CUSTOM, clamped).
* New: SpotifyLyrics, Waypoints (/zephyr wp), SessionHUD, /zephyr coords|calc|roll.
* Fixes: smooth NameTags (were stepping at 20 Hz), smooth DamageNumbers, fewer
  per-frame allocations in billboard text.

Zephyr Client v1.10.0
---------------------

WHAT'S NEW IN v1.10.0
---------------------
* PearlCatch rewritten: silent throws (camera never moves), pearl straight up,
  wind charge at your feet, launch timed with vanilla physics + ping, self-calibrating.
* Combat modules cooperate: TriggerBot / LegitAura hold off for AutoMace slams and
  ShieldBreaker; ShieldBreaker + AutoMace = stun slam (axe opens shield, mace next tick).
* Action states: no attacks while using an item, with a screen open, in the same tick
  as a release or inventory click, or twice in one tick; active digging is aborted
  (ABORT_DESTROY_BLOCK) before an attack; no right-click while digging.
* FPS: ESP/Tracers no longer scan the whole world every frame, shared entity cache
  fixed, allocation-free box/line drawing, cheaper HUD text and module lookups.

Zephyr Client v1.9.0
--------------------

WHAT'S NEW IN v1.9.0
--------------------
* Every module now has a short description: hover a module in the ClickGui to
  read it, search matches descriptions too, and /zephyr info <module> prints it.
* Slot-switching / attack packet ordering cleanup: modules tick in a
  pre-movement stage (before this tick's movement packet), hotbar changes go
  through vanilla syncSelectedSlot (no duplicate slot packets), slot indices are
  validated 0..8, and at most one slot change is sent per tick.
* New modules:
    Zoom            - hold C to zoom (smooth, lower sensitivity while zoomed)
    AutoTool        - fastest hotbar tool while mining, then back
    InfoHUD         - coords, nether/overworld coords, facing, speed, FPS, ping
    ArmorHUD        - armour + held items with durability above the hotbar
    Keystrokes      - WASD / mouse (with CPS) / jump overlay
    DurabilityAlert - warning + sound before gear breaks
    AutoRespawn     - auto-clicks Respawn and prints your death coordinates

Zephyr Client v1.3.0 - Minecraft 1.21.11 (Fabric)
==================================================

WHAT THIS BUILD FIXES (v1.2 -> v1.3)
------------------------------------
Root-caused with a static mixin auditor (every hook verified against the real
1.21.11 intermediary mappings - 17/17 PASS) and a scripted in-game selftest
(real client launch, framebuffer screenshots, real click path).

1. GUI ("glitched") - The 1/3 matrix scale made text unreadably microscopic.
   Now renders at a readable half scale with a live "GUI scale" setting
   (ClickGui -> GUI scale, 0.25x-1.5x; drag the slider to rescale instantly).
   Verified in-game: columns, title bar (ZEPHYR + mods/fps stats), readable
   module names, correct click toggling.

2. Freelook - The v1.2 mouse hook targeted onCursorPos->changeLookDirection,
   which NO LONGER EXISTS in 1.21.11 (the call moved to Mouse.tick()).
   The redirect failed silently. Input interception now runs at
   Entity.changeLookDirection (call-site independent) and the mouse-level
   hook was retargeted to tick(). Default bind: LEFT ALT.

3. FPS - The crystal solver's explosion-damage math ran 27 world raycasts
   per candidate blast cell, hundreds of block-walks per tick while the
   crystal modules were armed. DamageCalculator now memoizes exposure per
   (blast cell, entity) per tick (identical numbers, 5-20x fewer raycasts),
   far-range damage estimates skip the ray bundle entirely, and the
   solver's evaluation budget dropped 12 -> 8. Render helpers no longer
   allocate per entity per frame, and the RNG is a shared instance
   (v1.2 allocated a new Random per roll).
   Type /zephyr profile to see live per-tick/per-frame costs in chat.

4. TargetHUD - previously only showed a target while a combat module was
   armed; now acquires its own target each tick, so the panel sits next to
   the enemy with: name, live HP BAR (green->amber->red), absorption,
   totem indicator, hurt flash, distance, held item, armor points.

5. Default binds out of the box: Freelook = LAlt, PearlCatch = G,
   LungeMacro = H, ClickGui = Right Shift. Unbound modules now display
   "-" instead of "K0" and are never polled.

6. Module faults now log to the console with a stack trace before
   disabling (silent "nothing works" states are visible in the log).

7. JumpReset default success chance 1.0 (deterministic single-tick reset).

MODULE REFERENCE (enable in GUI: Right Shift)
---------------------------------------------
COMBAT
  AutoMace      - mace smash timing (fall tracking, 0-tick window)
  BreachSwap    - breach armor swap chain
  JumpReset     - same-tick jump on damage (upward knockback reset)
  LegitAura     - legit rotations, cooldown-aware hits
  LungeMacro    - H = attribute swap to spear, kills lunge cooldown
  STap          - S-tap combo rhythm after landed hits
CRYSTAL
  AnchorMacro   - respawn-anchor detonation chain
  AutoCrystal   - full place/detonate crystal pipeline (solver-driven)
  AutoHitCrystal- the full engagement loop: melee hit with ANY item
                  (sword-cooldown aware) -> instantly solve the best
                  obsidian/crystal placement math -> place -> detonate
                  at randomized CPS until the target drops or escapes
                  -> swap back to the sword -> repeat. Fully VISIBLE
                  rotations (looks at block, places, looks at player,
                  hits, looks at crystal, breaks).
  SafeAnchor    - self-safe anchor detonation
SURVIVAL
  AutoLog       - health-gated auto disconnect
  AutoTotem     - inventory-walking offhand restock (human-paced clicks)
  FastXP        - rapid XP bottle use
  PearlCatch    - G = look up -> throw pearl -> tiny delay -> wind charge
MOVEMENT
  Freelook      - LAlt = detached camera, vanilla movement
  NoDelay       - attack/use/jump delay removal
  NoPush        - entity/knockback/fluid push cancellation
RENDER
  CrystalESP    - crystal outlines tinted by danger
  DamageNumbers - floating damage popups
  ESP           - player box outlines
  FancyOutlines - double-pass glow on the current target
  HUD           - active module list (top-left)
  NameTags      - player nameplates with live HP
  TargetHUD     - enemy detail panel next to the target
  Tracers       - one line per player (ultra-light)

COMMANDS
--------
  /zephyr list              all modules + state
  /zephyr toggle <module>
  /zephyr set <module> <setting> <value>
  /zephyr bind <module> <glfw keycode>
  /zephyr save
  /zephyr profile           live perf report every 10s
  /zephyr mixins            hook status

INSTALL
-------
  Drop zephyr-1.3.0.jar into .minecraft/mods/ with Fabric Loader >= 0.18
  and Fabric API 0.141.x for Minecraft 1.21.11.
  Compatible with Sodium (they can coexist in the same mods folder).

PERF NOTES
----------
  If your 250 fps baseline comes from a performance mod (Sodium etc.),
  make sure it is still loaded alongside zephyr - the mod itself adds
  well under 1 ms per frame when its modules are idle; use
  /zephyr profile to confirm.

SOURCE
------
  Full source in this zip: src/ + build.gradle + gradle.properties.
  Build with: gradle build  (Fabric Loom 1.14, Java 21).
