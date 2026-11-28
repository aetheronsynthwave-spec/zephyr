v1.19.0 - ESP memory and hidden-data hints update
  - StorageESP, AmethystFinder and BaseFinder share one scanner over the chunks your
    client holds. What it finds is remembered (drawn fainter) per server and dimension in
    config/zephyr/esp-memory, so it stays visible from the surface. It is forgotten only
    after two close rescans miss it ("Forget only within", default 16 blocks).
    Singleplayer is remembered for the session only.
  - Nothing can show data the server never sent. Walk or fly near a place once to learn it.
  - Chunk hints (square at your height, line down): section palette lists storage or
    amethyst that no block shows; bud-strength light beside geodes; impossible light edges;
    block light underground; grown-out amethyst areas; hives; storage and amethyst sounds.
    Hints are clues, not proof. A removed block can stay listed in a palette.
  - Which of these DonutSMP leaks is unknown. To find out: StorageESP > Advanced > "Write
    probe file now" on the surface, again next to a known stash underground, and compare
    the two config/zephyr/esp-probe-*.txt files.
  - BaseFinder, BlockESP and CrystalESP now draw through walls ("Through walls", on).
    BlockESP also remembers its finds for the session.
  - Sees little on a server you expect more from? AmethystFinder / BaseFinder Advanced hold
    the thresholds. "Count growth by" AUTO reads palettes on addresses containing donutsmp.
  - Hint methods adapted from Spectre by 4ldenz via Donut Client 4.0 are GPL-3.0-only; see
    src/main/resources/licenses/. Not run in game.

Auto flip and CraftAutomate update (part of v1.19.0)
  - Every module menu shows a few basic settings; the rest are under "Advanced".
  - AuctionFlipper, Auto flip on (default): browses /ah page by page, searches
    /ah <item name> to read the whole market, asks [Y]/[N], buys, searches again and lists
    with /ah sell <price> just under the cheapest, only if that is at least cost plus
    "Min profit %". Otherwise it holds the item and tries again later. After listing it
    searches once more and notes whether yours is the cheapest. It does not relist when
    someone undercuts you afterwards.
  - Auto flip off: it only marks deals in the window you open and asks before buying.
  - CraftAutomate: spruce logs to planks to crafting tables, sold one at a time the same
    way. Keep 4 inventory slots empty and no spruce logs or planks of your own in it.
    Recipes: config/zephyr/craft.json. Budget, fee, keys and the list are shared with
    AuctionFlipper; max price and min profit are its own.
  - Both open and close windows by themselves. Their module keys stop them, also while a
    window is open.
  - askFirst off needs a max price and a budget above 0. Max 10 buys per session by
    default (maxBuys); /zephyr ah reset starts a new session.
  - Auto flip only checks items that are 30% under what at least 5 other listings of the
    same item ask (Advanced: discount, samples, minProfit, minRoi).
  - The first time, click confirm yourself in the window the server opens, once for buying
    and once for selling. Both buttons are remembered.
  - Your money is unknown until you set these in config/zephyr/auction.json, then run
    /zephyr ah reload: "balanceCommand" (the command, no slash) and "balanceMessage" (the
    line it prints, with {price} for the amount).
  - Not seen on the server yet, so check them: the next page button (slot 53, the arrow),
    that /ah <name> opens a window with the same layout, and that /ah sell takes the price
    for the whole held stack. Advanced "cheapestFirst": only if search results are sorted
    cheapest first; otherwise every page is read, up to checkPages (10).
  - Abbreviated prices ("1.1K") are undercut from the lowest value they can stand for.
  - /zephyr ah log (or bind ledgerKey): bought and sold list with Sell and Sold buttons.
  - "Sold" entries: no sale message is known yet. Use the Sold button, or turn on logChat,
    copy the sale line into soldMessage in config/zephyr/auction.json using {item},
    {price} and optionally {count}, {player}, {any}, then /zephyr ah reload.
  - Item rules also live in auction.json:
      "rules": { "blacklist": ["minecraft:dirt"], "whitelist": [],
                 "maxUnitPrice": {"minecraft:diamond": 900},
                 "resaleUnitPrice": {"Netherite Ingot": 450000} }
  - /zephyr ah status | forget | reset | learn confirm | learn refresh | learn cancel.

v1.18.1 - Menus and AuctionFlipper update
  - Every module menu shows a few basic settings; the rest are under "Advanced".
  - AuctionFlipper asks before buying: [Y] buys, [N] skips (askFirst off = no question,
    and then maxPrice and budget must both be above 0).
  - The first time, click confirm yourself in the window the server opens. The button is
    remembered once chat says "You bought ...". The first /ah sell works the same way:
    click confirm yourself; it is remembered once the item has left your inventory.
  - /zephyr ah log (or bind ledgerKey): bought and sold list, sale prices, Sell buttons.
  - autoSell relists bought stacks with /ah sell <price> once all windows are closed.
  - "Sold" entries: no sale message is known yet. Use the Sold button, or turn on logChat,
    copy the sale line into soldMessage in config/zephyr/auction.json using {item},
    {price} and optionally {count}, {player}, {any}, then /zephyr ah reload.
  - Item rules also live in auction.json:
      "rules": { "blacklist": ["minecraft:dirt"], "whitelist": [],
                 "maxUnitPrice": {"minecraft:diamond": 900},
                 "resaleUnitPrice": {"Netherite Ingot": 450000} }
  - /zephyr ah status | forget | reset | learn confirm | learn refresh | learn cancel.

v1.18.0
  - New: AutoCrystalPvP (crystal and anchor modules as one bot, flat pearl chase under a
    flung target), DonutSMP section (AmethystFinder, StorageESP, AuctionFlipper which only
    marks deals, BaseFinder), WalkInventory, XRay, CustomSky, HandChams, Cosmetics.
  - AutoHitCrystal: faster, with settings for aim, same-tick break and place and delays.
  - SmartMaceBot: high ground, pearl catches, stun slams, rocketless gliding, fall save.
  - ElytraMaceBot: prefers a Wind Burst I mace; dives follow a moving target (down to 45
    degrees) and swing at anything in reach, so it is no longer top-only. In test about
    90-97% hits on still, walking and strafing targets. A target that jumps or teleports
    aside as you commit still beats it.
  - Black and white rounded UI, menu size control; ESP box edges all draw now.
  - Not tried: on DonutSMP itself, or WalkInventory with real key presses.

v1.17.0 - ElytraMaceBot: vertical drops only
  - Attacks only from directly above: gets over the target, looks down, rockets straight
    down, takes the elytra off and smashes. No angled dives and no hits from the side
    (everything must be within 25 degrees of vertical).
  - Chains off the Wind Burst bounce without climbing: the next drop starts at the top of
    the bounce. New setting rocketDiveFrom (default 2.5 blocks over the head).
  - Strike aim picks the smallest turn that puts the crosshair on the head.
  - In-game test (30 s, still target): about 31-36 hits, ~0.8 s per hit. The older angled
    dives managed 12-18. Moving targets: 10-24 hits; strafing targets remain the weakest.
  - AI mode now starts from trained scores shipped in the jar. In testing it did NOT beat
    the default settings, so leave aiMode off unless you want it to adapt to your own play.
  - Wind charge fall save fires earlier when a drop can no longer hit.

v1.16.0
  - ElytraMaceBot and Fly are no longer limited to singleplayer / hosted LAN worlds.
    This replaces the restriction described in older entries. Servers may kick or ban for it.
  - ElytraMaceBot: can fetch an elytra from the hotbar or inventory; revised aiming and
    wind charge use.
  - Spotify lyrics HUD layout and shared combat code updated.

v1.15.0 - ElytraMaceBot: rocket-down chain (practice only)
  - Chains hits off a Wind Burst I bounce: elytra on, rocket DOWN, elytra off, strike.
  - Waits out the target's invulnerability window, so swings are not wasted.
  - Shields: breaks them with a hotbar axe first, or comes down behind without one.
  - Aims ahead of fast targets. AI mode (off by default) adjusts timing from hits/misses.
  - Hotbar: Wind Burst I mace, firework rockets, chestplate, wind charges, axe. Wear the
    elytra and keep a totem in the offhand.
  - Still singleplayer / hosted LAN worlds only.

v1.14.0 - ElytraMaceBot rebuilt (practice only)
  - Runs only in singleplayer or a LAN world you host; it switches itself off elsewhere.
  - Picks its dive by simulating elytra flight each tick, and takes the elytra off only
    when the simulated free fall reaches the target.
  - Wind charge fall save for missed drops: keep wind charges in the hotbar. Without them
    a missed drop can kill you. Even with them a save can fail if the target moves
    very suddenly in the last few ticks, so keep a totem in your offhand.
  - Hotbar: Wind Burst mace, firework rockets, chestplate, wind charges. Wear the elytra.
  - Settings are now: range, height, minFall, reach, minHealth, windBurst, respectShield,
    targetMobs. The old rotation/jitter/profile settings were removed.

Zephyr Client v1.13.3 - Minecraft 1.21.11 (Fabric)
===================================================

WHAT'S NEW IN v1.13.3 (PearlCatch packet hygiene)
-------------------------------------------------
PearlCatch could emit malformed, out-of-order or repeated use-item packets while
throwing pearls. Root causes verified against the mapped 1.21.11 client jar:

* Vanilla 1.21.11 ClientPlayerInteractionManager.interactItem sends
  PlayerInteractItemC2SPacket BEFORE the client prediction runs - and still sends
  it when the prediction returns PASS (item on cooldown). Both ender pearls
  (useCooldown(1.0) = 20 ticks) and wind charges (useCooldown(0.5) = 10 ticks)
  carry a use-cooldown component, applied client-side by ItemStack.use ->
  applyRemainderAndCooldown the moment a throw is predicted. PearlCatch advanced
  its stages only on ActionResult.isAccepted(), so a non-accepted prediction left
  the stage armed with the packet already out - a re-fire loop that duplicates
  use-item packets every tick (e.g. a server-plugin pearl cooldown synced via
  CooldownUpdateS2CPacket). Stages now advance as soon as the use went out
  through the vanilla manager (result != ActionResult.FAIL): one throw, one
  packet, prediction outcome never retried.
* No item-use dedup existed: PearlCatch's wind charge and another module's throw
  (FastXP bottle, macebot firework) could both emit PlayerInteractItemC2SPacket
  in the same tick - impossible from vanilla's single doItemUse pass and a
  textbook macro signature. ActionState now tracks per-tick item uses
  (noted by a mixin at interactItem HEAD, so vanilla right-clicks count too);
  CombatActions.interactItem / interactItemAt are gated by the new
  prepareItemUse (one use per tick; interactBlock keeps the looser vanilla
  block-then-item order). PearlCatch additionally waits for a swing-free tick:
  no pearl interleaved with an attack, dig, use-release, open screen or settling
  inventory click.
* holdItem could clickSlot into a foreign screen handler: with a chest or
  inventory screen open mid-sequence, findHandlerSlot searched the OPEN
  handler's slots and the SWAP click landed in the wrong container. The
  inventory-swap fallback now requires currentScreen == null (slot selection
  itself stays legal - hotbar keys work in screens, exactly like vanilla).
* The RESTORE stage could wedge forever: if another module took the one
  slot-change-per-tick budget every tick (SwapManager), the restore retried
  endlessly and the bind never re-armed (keypress only works from IDLE).
  Restore is now bounded (40 ticks) and always returns to IDLE; world
  join/leave reset the FSM so a stale returnSlot from the old world is never
  restored in the new one (the onWorldJoin/onWorldLeave hooks existed but were
  never dispatched - ZephyrClient now calls them for every module on
  ClientPlayConnectionEvents JOIN/DISCONNECT).
* Cooldown handling: isCoolingDown(stack) is checked immediately before every
  throw; stage timeouts now exceed the component cooldowns (PEARL 24 > 20,
  WIND 48 > 10), so a re-press inside the cooldown window waits it out and
  still throws instead of giving up (the old 20-tick pearl timeout expired
  exactly when a 1s cooldown ended). Slot changes remain strictly 0..8, one
  server-visible change per tick through SwapManager's vanilla
  syncSelectedSlot() (no redundant packets), keeping the per-tick packet order
  identical to a human pressing 1-9 then right-clicking:
  UpdateSelectedSlot -> PlayerInteractItem -> HandSwing -> movement.

Zephyr Client v1.13.2 - Minecraft 1.21.11 (Fabric)
===================================================

WHAT'S NEW IN v1.13.2 (macebot strike fixes)
--------------------------------------------
The three mace bots dove correctly but pulled back up right before the hit instead of
landing the smash. Root causes found by replaying the dive math against the real
1.21.11 mechanics (glide flag is server-authoritative; armour clicks are not predicted
client-side, so the elytra stays on for a full round trip after the swap click):

* ElytraMaceBot: the strike window was counted from the chest-swap CLICK while the
  player keeps gliding until the server confirms the swap - at normal ping the whole
  window burned away during that lag and every drop ended in the miss->climb swoop.
  The window now counts from the tick the elytra actually comes off, the equipment
  wait scales with ping (was a fixed 3 ticks), the free-fall impact forecast includes
  the swap round trip so the swap fires early enough, and the strike reach keeps a
  small margin under the server's 3.0 interaction limit (a hit at exactly 3.0
  client-side was being dropped as out of range).
* SmartMaceBot: launches from up to 14 blocks away (launchRange) - the wind charge is a
  vertical boost and air control is ~2% of ground control, so the descent sailed past
  and the bot just bounced up and down. Launch range is now capped at smash range
  (default 5, the bot walks the rest first). The strike was also held for a 10-degree
  aim lock while the aim point slews 15-25 deg/tick during a fast fall - the reach
  window flew past with the hit still blocked; during a qualifying fall the tolerance
  now widens instead (the server only needs reach + line of sight). A wind-charge
  throw delayed past the jump apex aborts and finishes the drop instead of boosting
  the bot back up right before the smash.
* AutoMace: TRIGGER strikes at 2.85 instead of exactly 3.0 (server-side margin);
  STANDARD/DENSITY widen the aim gate during fast qualifying falls for the same
  reason as SmartMaceBot.

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
